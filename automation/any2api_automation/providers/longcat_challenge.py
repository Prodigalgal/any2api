from __future__ import annotations

import io
import math
import random
import re
import secrets
import time
from dataclasses import dataclass
from typing import Any

from PIL import Image

from ..captcha.artifacts import record_captcha_artifact
from ..captcha.registry import registry
from ..captcha.strategy import (
    ChallengeAttemptResult,
    ChallengeDetection,
    ChallengePolicy,
    ChallengeRunner,
    ChallengeRunResult,
    ChallengeStrategy,
)
from ..lifecycle.browser import first_visible
from .longcat_settings import settings

YODA_SELECTOR = (
    "#yodaVerify, .yoda-verify-container, .yoda-global-inference-wrapper, "
    ".yoda-sudoku-wrap, .global-puzzle-main, div.box-static, "
    "canvas.yoda-global-inference-question"
)

LONGCAT_VISUAL_POINTS_PROMPT = (
    "This is a Keeta Yoda verification image. It is either: "
    "(A) Tap icons in following order, with ordered target icons above an icon grid; or "
    "(B) Connect the colored dots using the shortest line. "
    "Return the target centers in required order as normalized coordinates relative to the "
    "whole image. Output exactly one line: POINTS=x1,y1;x2,y2;x3,y3"
)


@dataclass(frozen=True)
class LongCatGapPlan:
    target_piece: float
    drag_distance: float
    gap_left: float
    piece_offset: float
    piece_width: float


@dataclass(frozen=True)
class LongCatPieceGeometry:
    image: bytes
    offset_x: int
    visual_width: int


@dataclass(frozen=True)
class LongCatGapEstimate:
    solver: str
    value: float
    confidence: float
    weight: float
    detail: str = ""


class LongCatYodaStrategy(ChallengeStrategy):
    def __init__(self) -> None:
        self.last_diagnostic = "unavailable"

    @property
    def strategy_id(self) -> str:
        return "yoda-global"

    def detect(self, page, timeout_ms: int) -> ChallengeDetection | None:
        deadline = time.monotonic() + max(0, timeout_ms) / 1000
        saw_yoda = False
        while True:
            text = _yoda_text(page)
            lowered = text.lower()
            if _visible(page, "div.box-static, .global-puzzle-slider-box-wrap") or re.search(
                r"move the slider|complete the puzzle|向右滑动|拖动滑块", lowered
            ):
                return ChallengeDetection("slider", metadata={"text": lowered[:160]})
            if _visible(page, ".yoda-sudoku-wrap, canvas.sudoku-canvas") or re.search(
                r"connect the dots|shortest line|连线|最短", lowered
            ):
                return ChallengeDetection("dots", metadata={"text": lowered[:160]})
            if re.search(r"tap icons|following order|点选|按顺序点击|按顺序点", lowered) or (
                _visible(page, ".yoda-global-inference-wrapper")
                and _visible(
                    page,
                    ".yoda-global-inference-answer, .yoda-global-inference-control, "
                    ".yoda-global-inference-title + div",
                )
            ):
                return ChallengeDetection("tap", metadata={"text": lowered[:160]})
            visible = _visible(page, YODA_SELECTOR)
            if visible and not saw_yoda:
                saw_yoda = True
                # Yoda mounts its wrapper before the title and canvas are painted.
                deadline = max(deadline, time.monotonic() + 20)
            if time.monotonic() >= deadline:
                if saw_yoda:
                    return ChallengeDetection("unknown", metadata={"text": lowered[:160]})
                return None
            page.wait_for_timeout(250)

    def solve(
        self,
        page,
        detection: ChallengeDetection,
        attempt: int,
    ) -> ChallengeAttemptResult:
        try:
            if detection.kind == "slider":
                self.last_diagnostic = self._solve_slider(page, attempt)
            elif detection.kind == "dots":
                self.last_diagnostic = self._solve_dots(page, detection, attempt)
            elif detection.kind == "tap":
                self.last_diagnostic = self._solve_tap(page, attempt)
            elif detection.kind == "unknown":
                self.last_diagnostic = "kind=unknown, rendered=false"
            else:
                raise RuntimeError(f"unsupported LongCat Yoda kind: {detection.kind}")
        except RuntimeError as error:
            detail = " ".join(str(error).split())[:240]
            self.last_diagnostic = f"kind={detection.kind}, error={detail}"
        return ChallengeAttemptResult(self.last_diagnostic)

    def verify(
        self,
        page,
        detection: ChallengeDetection,
        attempt: ChallengeAttemptResult,
    ) -> bool:
        del detection, attempt
        clear_streak = 0
        for _ in range(20):
            page.wait_for_timeout(250)
            clear_streak = 0 if yoda_visible(page) else clear_streak + 1
            if clear_streak >= 3:
                return True
        return False

    def refresh(self, page, detection: ChallengeDetection) -> None:
        del detection
        action = first_visible(
            page,
            (
                ".global-puzzle-slider-operate-refresh",
                ".sudoku-operate-refresh",
                '#yodaVerify img[src*="refresh"]',
                '[class*="refresh"]',
            ),
            timeout_ms=2_000,
        )
        if action is not None:
            action.click(force=True)

    def _solve_slider(self, page, attempt: int) -> str:
        handle = first_visible(
            page,
            (
                "div.box-static",
                ".global-puzzle-slider-box-wrap .box-static",
                ".yoda-slider-btn",
                ".slider-move-btn",
            ),
            timeout_ms=8_000,
        )
        background = page.locator(".global-puzzle-slider-image").first
        piece = page.locator(".global-puzzle-slider-drag").first
        track = page.locator(".global-puzzle-slider-box-wrap").first
        if handle is None or not background.count() or not piece.count() or not track.count():
            raise RuntimeError("LongCat slider challenge geometry is unavailable")
        handle_box = handle.bounding_box()
        background_box = background.bounding_box()
        piece_box = piece.bounding_box()
        track_box = track.bounding_box()
        if not handle_box or not background_box or not piece_box or not track_box:
            raise RuntimeError("LongCat slider bounding boxes are unavailable")

        piece_bytes = _safe_locator_screenshot(page, piece)
        background_bytes = _clean_background_screenshot(page, background)
        geometry = _extract_longcat_piece(background_bytes, piece_bytes)
        solver_piece = geometry.image if geometry is not None else piece_bytes
        natural_width = _image_size(background_bytes)[0]
        piece_natural_width = _image_size(piece_bytes)[0]
        visual_natural_width = (
            geometry.visual_width if geometry is not None else piece_natural_width
        )
        estimates, estimate_diagnostics = _longcat_gap_estimates(
            background_bytes,
            solver_piece,
            natural_width,
            visual_natural_width,
        )
        if not estimates:
            raise RuntimeError(
                "LongCat slider solver produced no estimate: " + ",".join(estimate_diagnostics)
            )
        gap, fusion_confidence = _fuse_longcat_gap(estimates)
        estimate_spread = max(item.value for item in estimates) - min(
            item.value for item in estimates
        )
        if len(estimates) > 1 and fusion_confidence < 0.5 and estimate_spread > 36:
            raise RuntimeError(
                "LongCat slider estimators disagree: " + ",".join(estimate_diagnostics)
            )

        scale = background_box["width"] / max(1, natural_width)
        fallback_piece_width = min(piece_box["width"], max(32.0, handle_box["width"]))
        visual_width = (
            geometry.visual_width * scale if geometry is not None else fallback_piece_width
        )
        piece_offset = (
            geometry.offset_x * scale
            if geometry is not None
            else max(0.0, (piece_box["width"] - fallback_piece_width) / 2)
        )
        max_travel = max(40.0, track_box["width"] - handle_box["width"])
        current_piece = max(0.0, _read_piece_left(page) or 0.0)
        plan = _map_longcat_gap(
            float(gap) * scale,
            current_piece,
            max_travel,
            background_box["width"],
            visual_width,
            piece_offset,
            settings().longcat_slider_fudge_px,
        )

        last_final = current_piece
        for offset in (0.0, -3.0, 3.0):
            if not yoda_visible(page):
                break
            current_handle = page.locator("div.box-static").first
            current_box = current_handle.bounding_box() if current_handle.count() else handle_box
            if not current_box:
                raise RuntimeError("LongCat slider handle disappeared")
            handle_offset = current_box["x"] - track_box["x"]
            if handle_offset > 25:
                if _wait_yoda_cleared(page, 1_200):
                    break
                return (
                    f"kind=slider, attempt={attempt}, handleMidTrack={handle_offset:.1f}, "
                    "refreshRequired=true"
                )
            start = (
                current_box["x"] + current_box["width"] / 2,
                current_box["y"] + current_box["height"] / 2,
            )
            last_final, grabbed = _drag_longcat_closed_loop(
                page,
                start,
                max(18.0, min(max_travel - 2, plan.target_piece + offset)),
                max_travel,
            )
            if not grabbed:
                current_piece = float(_read_piece_left(page) or 0.0)
                current_box = current_handle.bounding_box() if current_handle.count() else None
                if current_box:
                    start = (
                        current_box["x"] + current_box["width"] / 2,
                        current_box["y"] + current_box["height"] / 2,
                    )
                    distance = (
                        plan.target_piece
                        if current_piece < 8
                        else max(0.0, plan.target_piece - current_piece)
                    )
                    if distance > 3:
                        last_final = _drag_longcat_open_loop(page, start, distance)
            if _wait_yoda_cleared(page, 5_000):
                break
            page.wait_for_timeout(200)

        estimate_summary = ",".join(
            f"{item.solver}:{float(item.value):.1f}/{item.confidence:.2f}"
            for item in estimates
            if isinstance(item.value, (int, float))
        )
        return (
            f"kind=slider, attempt={attempt}, estimates=[{estimate_summary}], "
            f"fusionConfidence={fusion_confidence:.2f}, "
            f"gap={plan.gap_left:.1f}, pieceOffset={plan.piece_offset:.1f}, "
            f"target={plan.target_piece:.1f}, final={last_final:.1f}"
        )

    def _solve_dots(self, page, detection: ChallengeDetection, attempt: int) -> str:
        canvas = _wait_painted_canvas(page)
        box = canvas.bounding_box()
        if not box:
            raise RuntimeError("LongCat dots challenge has no geometry")
        color = _yoda_color(str(detection.metadata.get("text") or ""))
        screenshot = _safe_locator_screenshot(page, canvas, required=False)
        points: list[tuple[float, float]] = []
        if screenshot is not None:
            try:
                points = _best_points(registry.solve_dots_sync(screenshot, color), "dots")
            except RuntimeError:
                pass
        if len(points) < 2:
            points = _in_page_dots(page, canvas, color)
            if len(points) < 2:
                if attempt >= settings().longcat_yoda_attempts:
                    return self._solve_visual_points(page, "dots", attempt)
                width, height = (
                    _image_size(screenshot)
                    if screenshot is not None
                    else (round(box["width"]), round(box["height"]))
                )
                return (
                    f"LongCat dots solver produced no candidate (color={color}, "
                    f"canvas={width}x{height})"
                )
        page.mouse.move(
            box["x"] + points[0][0] * box["width"],
            box["y"] + points[0][1] * box["height"],
        )
        page.wait_for_timeout(60)
        page.mouse.down()
        for x, y in points[1:]:
            page.mouse.move(box["x"] + x * box["width"], box["y"] + y * box["height"], steps=10)
            page.wait_for_timeout(30)
        page.mouse.up()
        if not _wait_yoda_cleared(page, 5_000) and attempt >= settings().longcat_yoda_attempts:
            return self._solve_visual_points(page, "dots", attempt)
        return f"kind=dots, color={color}, points={len(points)}"

    def _solve_tap(self, page, attempt: int) -> str:
        _wait_painted_canvas(page)
        targets = first_visible(
            page,
            (
                ".yoda-global-inference-answer",
                ".yoda-global-inference-control",
                ".yoda-global-inference-title + div",
            ),
            timeout_ms=2_500,
        )
        panel = first_visible(
            page,
            (
                "canvas.yoda-global-inference-question",
                ".yoda-global-inference-panel",
                ".yoda-global-inference-wrapper canvas",
            ),
            timeout_ms=2_500,
        )
        wrapper_fallback = False
        if targets is None or panel is None:
            wrapper = first_visible(
                page,
                (".yoda-global-inference-wrapper", ".yoda-modal-content"),
                timeout_ms=3_000,
            )
            if wrapper is None:
                raise RuntimeError("LongCat tap challenge images are unavailable")
            targets = targets or wrapper
            panel = panel or wrapper
            wrapper_fallback = True
        target_image = _safe_locator_screenshot(page, targets, required=False)
        panel_image = _safe_locator_screenshot(page, panel, required=False)
        _record_captcha_artifact("longcat-tap-targets", target_image)
        _record_captcha_artifact("longcat-tap-panel", panel_image)
        try:
            if target_image is None or panel_image is None:
                raise RuntimeError("LongCat tap screenshots are unavailable")
            points = _best_points(
                registry.solve_tap_sync(target_image, panel_image),
                "tap",
            )
        except RuntimeError:
            if attempt >= settings().longcat_yoda_attempts:
                return self._solve_visual_points(page, "tap", attempt)
            return "kind=tap, local=no-candidate"
        box = panel.bounding_box()
        if not box:
            raise RuntimeError("LongCat tap challenge has no geometry")
        for x, y in points:
            page.mouse.click(
                box["x"] + x * box["width"],
                box["y"] + y * box["height"],
                delay=35,
            )
            page.wait_for_timeout(220)
        if not _wait_yoda_cleared(page, 5_000) and attempt >= settings().longcat_yoda_attempts:
            return self._solve_visual_points(page, "tap", attempt)
        return f"kind=tap, points={len(points)}, wrapperFallback={str(wrapper_fallback).lower()}"

    def _solve_visual_points(self, page, kind: str, attempt: int) -> str:
        for round_number in range(1, 4):
            target = first_visible(
                page,
                (
                    ".yoda-global-inference-wrapper",
                    ".yoda-sudoku-wrap",
                    ".yoda-modal-content",
                    "canvas.yoda-global-inference-question",
                    "canvas.sudoku-canvas",
                ),
                timeout_ms=3_000,
            )
            box = target.bounding_box() if target is not None else None
            screenshot = (
                _safe_locator_screenshot(page, target, required=False)
                if target is not None and box
                else None
            )
            _record_captcha_artifact(f"longcat-{kind}-vision-{round_number}", screenshot)
            estimate = (
                registry.solve_visual_points_sync(screenshot, LONGCAT_VISUAL_POINTS_PROMPT)
                if screenshot is not None
                else None
            )
            if estimate is not None:
                points = [(float(x), float(y)) for x, y in estimate.value]
                if kind == "dots":
                    page.mouse.move(
                        box["x"] + points[0][0] * box["width"],
                        box["y"] + points[0][1] * box["height"],
                    )
                    page.wait_for_timeout(60)
                    page.mouse.down()
                    for x, y in points[1:]:
                        page.mouse.move(
                            box["x"] + x * box["width"],
                            box["y"] + y * box["height"],
                            steps=10,
                        )
                        page.wait_for_timeout(30)
                    page.mouse.up()
                else:
                    for x, y in points:
                        page.mouse.click(
                            box["x"] + x * box["width"],
                            box["y"] + y * box["height"],
                            delay=40,
                        )
                        page.wait_for_timeout(280)
                if _wait_yoda_cleared(page, 5_000):
                    return (
                        f"kind={kind}, attempt={attempt}, solver=vision_points, "
                        f"round={round_number}, points={len(points)}"
                    )
            if round_number < 3:
                self.refresh(page, ChallengeDetection(kind))
                page.wait_for_timeout(1_000)
        return (
            f"kind={kind}, attempt={attempt}, solver=vision_points, exhausted=true, "
            f"visual={registry.visual_diagnostic()}"
        )


def solve_yoda_if_present(page) -> ChallengeRunResult:
    config = settings()
    strategy = LongCatYodaStrategy()
    result = ChallengeRunner().run(
        page,
        strategy,
        ChallengePolicy(
            max_attempts=max(1, config.longcat_yoda_attempts),
            first_detection_timeout_ms=500,
            retry_detection_timeout_ms=15_000,
            retry_settle_ms=900,
        ),
    )
    if result.present and not result.solved:
        raise RuntimeError(
            f"LongCat Yoda challenge exhausted all local attempts ({result.diagnostic})"
        )
    return result


def yoda_visible(page) -> bool:
    return _visible(page, YODA_SELECTOR)


def _clean_background_screenshot(page, background) -> bytes:
    page.evaluate(
        """() => {
          for (const el of document.querySelectorAll(
            '.global-puzzle-slider-drag, .moveing-bar, div.box-static')) {
            el.dataset.any2apiVisibility = el.style.visibility || '';
            el.style.visibility = 'hidden';
          }
        }"""
    )
    try:
        return _safe_locator_screenshot(page, background)
    finally:
        page.evaluate(
            """() => {
              for (const el of document.querySelectorAll(
                '.global-puzzle-slider-drag, .moveing-bar, div.box-static')) {
                if (el.dataset.any2apiVisibility !== undefined) {
                  el.style.visibility = el.dataset.any2apiVisibility;
                  delete el.dataset.any2apiVisibility;
                }
              }
            }"""
        )


def _safe_locator_screenshot(
    page,
    locator,
    timeout_ms: int = 3_000,
    *,
    required: bool = True,
) -> bytes | None:
    try:
        if not locator.is_visible():
            raise RuntimeError("LongCat captcha screenshot target is not visible")
        box = locator.bounding_box()
        if box and box["width"] > 8 and box["height"] > 8:
            return page.screenshot(
                type="png",
                clip={
                    "x": max(0, int(box["x"])),
                    "y": max(0, int(box["y"])),
                    "width": max(1, min(1_200, math.ceil(box["width"]))),
                    "height": max(1, min(900, math.ceil(box["height"]))),
                },
                timeout=timeout_ms,
            )
        return locator.screenshot(type="png", timeout=min(1_500, timeout_ms))
    except Exception as error:
        if not required:
            return None
        raise RuntimeError(f"LongCat captcha screenshot failed ({type(error).__name__})") from error


def _record_captcha_artifact(label: str, image: bytes | None) -> None:
    record_captcha_artifact(label, image)


def _wait_painted_canvas(page):
    selector = (
        "canvas.sudoku-canvas, canvas.yoda-global-inference-question, "
        ".sudoku-image canvas, .yoda-sudoku-wrap canvas, "
        ".yoda-modal-content canvas"
    )
    deadline = time.monotonic() + 16
    visible_fallback = None
    while time.monotonic() < deadline:
        if _visible(page, '.sudoku-loading:visible, label:has-text("Loading")'):
            page.wait_for_timeout(500)
            continue
        candidates = page.locator(selector)
        for index in range(candidates.count()):
            canvas = candidates.nth(index)
            try:
                box = canvas.bounding_box()
                if not canvas.is_visible() or not box or box["width"] <= 40 or box["height"] <= 40:
                    continue
                visible_fallback = canvas
                painted = canvas.evaluate(
                    """element => {
                      try {
                        const width = element.width || element.clientWidth;
                        const height = element.height || element.clientHeight;
                        const context = element.getContext?.('2d', {willReadFrequently: true});
                        if (!context || width < 20 || height < 20) return false;
                        const pixels = context.getImageData(0, 0, width, height).data;
                        let min = 255, max = 0, opaque = 0;
                        const stride = Math.max(4, Math.floor(width * height / 3000) * 4);
                        for (let offset = 0; offset < pixels.length; offset += stride) {
                          if (pixels[offset + 3] > 20) opaque++;
                          const luminance = (pixels[offset] + pixels[offset + 1] +
                            pixels[offset + 2]) / 3;
                          min = Math.min(min, luminance);
                          max = Math.max(max, luminance);
                        }
                        return opaque > 30 && max - min > 18;
                      } catch (_) {
                        return false;
                      }
                    }"""
                )
                if painted:
                    return canvas
            except Exception:  # noqa: BLE001,S112 - canvases can rotate while inspected
                continue
        page.wait_for_timeout(400)
    if visible_fallback is not None:
        return visible_fallback
    raise RuntimeError("LongCat Yoda canvas remained blank or unrendered")


def _in_page_dots(page, canvas, color: str) -> list[tuple[float, float]]:
    del page
    value = canvas.evaluate(
        """(element, colorHint) => {
          const width = element.width || element.clientWidth;
          const height = element.height || element.clientHeight;
          const context = element.getContext('2d', {willReadFrequently: true});
          let pixels;
          try {
            pixels = context.getImageData(0, 0, width, height).data;
          } catch (_) {
            return [];
          }
          const matches = (r, g, b, a) => {
            if (a < 80) return false;
            const max = Math.max(r, g, b), min = Math.min(r, g, b), sat = max - min;
            if (colorHint === 'yellow') return r > 160 && g > 140 && b < 120 && r + g > b * 2.2;
            if (colorHint === 'green') return g > 120 && g > r * 1.15 && g > b * 1.15 && sat > 30;
            if (colorHint === 'orange') return r > 160 && g > 70 && g < 180 && b < 100 && r > g;
            if (colorHint === 'purple') return r > 80 && b > 100 && g < r * 0.9 && b > g;
            if (colorHint === 'blue') return b > 120 && b > r * 1.1 && b > g * 1.05;
            if (colorHint === 'red') return r > 150 && r > g * 1.3 && r > b * 1.3;
            return sat > 50 && max > 120;
          };
          const step = Math.max(2, Math.floor(Math.min(width, height) / 120));
          const cells = [];
          for (let y = 4; y < height - 4; y += step) {
            for (let x = 4; x < width - 4; x += step) {
              const offset = (y * width + x) * 4;
              if (matches(pixels[offset], pixels[offset + 1], pixels[offset + 2], pixels[offset + 3])) {
                cells.push([x, y]);
              }
            }
          }
          if (cells.length < 8) return [];
          const used = new Array(cells.length).fill(false), clusters = [];
          const radius = Math.max(10, Math.min(width, height) * 0.06);
          for (let index = 0; index < cells.length; index++) {
            if (used[index]) continue;
            const queue = [index]; used[index] = true;
            let sumX = 0, sumY = 0, count = 0;
            while (queue.length) {
              const current = queue.pop(), [x, y] = cells[current];
              sumX += x; sumY += y; count++;
              for (let candidate = 0; candidate < cells.length; candidate++) {
                if (used[candidate]) continue;
                const dx = cells[candidate][0] - x, dy = cells[candidate][1] - y;
                if (dx * dx + dy * dy <= radius * radius) {
                  used[candidate] = true; queue.push(candidate);
                }
              }
            }
            if (count >= 3) clusters.push({x: sumX / count, y: sumY / count, count});
          }
          clusters.sort((a, b) => b.count - a.count);
          const points = clusters.slice(0, 8);
          points.sort((a, b) => a.x - b.x || a.y - b.y);
          if (points.length < 2) return [];
          const ordered = [points.shift()];
          while (points.length) {
            const last = ordered[ordered.length - 1];
            let best = 0, distance = Infinity;
            for (let index = 0; index < points.length; index++) {
              const dx = points[index].x - last.x, dy = points[index].y - last.y;
              const current = dx * dx + dy * dy;
              if (current < distance) { distance = current; best = index; }
            }
            ordered.push(points.splice(best, 1)[0]);
          }
          return ordered.map(point => [point.x / width, point.y / height]);
        }""",
        color,
    )
    return [(float(point[0]), float(point[1])) for point in value]


def _map_longcat_gap(
    gap_left: float,
    piece_left: float,
    max_travel: float,
    image_width: float,
    piece_width: float,
    piece_offset: float,
    fudge: float,
) -> LongCatGapPlan:
    if gap_left < piece_width * 0.55:
        raise RuntimeError("LongCat slider estimate is inside the home piece strip")
    hole_max = max(40.0, image_width - piece_width - 1)
    if gap_left > hole_max + 8 or gap_left / max(image_width, 1) > 0.78:
        raise RuntimeError("LongCat slider estimate is outside the valid hole range")
    sanitized_gap = min(gap_left, hole_max)
    target = sanitized_gap - max(0.0, piece_offset) + fudge
    target = max(0.0, min(max_travel - 2, target))
    distance = (target - max(0.0, piece_left)) * settings().longcat_slider_distance_scale
    distance = max(18.0, min(max_travel - 2, distance))
    return LongCatGapPlan(target, distance, sanitized_gap, piece_offset, piece_width)


def _drag_longcat_closed_loop(
    page,
    start: tuple[float, float],
    target_piece: float,
    max_travel: float,
) -> tuple[float, bool]:
    config = settings()
    start_x, start_y = start
    target = max(0.0, min(max_travel - 1, target_piece))
    piece_start = float(_read_piece_left(page) or 0.0)
    kick = max(20.0, min(max_travel * 0.88, target * config.longcat_slider_kick_fraction))

    page.mouse.move(start_x - 12 - secrets.randbelow(9), start_y + _tremor(1.0))
    page.wait_for_timeout(20)
    page.mouse.move(start_x, start_y, steps=3)
    page.wait_for_timeout(30)
    page.mouse.down()
    page.wait_for_timeout(45)

    mouse_x = start_x
    kick_steps = max(12, int(kick / 7))
    for index in range(1, kick_steps + 1):
        ratio = index / kick_steps
        eased = ratio * ratio * (3 - 2 * ratio)
        mouse_x = start_x + kick * eased
        page.mouse.move(mouse_x, start_y + _tremor(0.3), steps=2)
        page.wait_for_timeout(6)

    mid_piece = float(_read_piece_left(page) or piece_start)
    if mid_piece - piece_start < 8 and kick > 25:
        page.mouse.up()
        page.wait_for_timeout(40)
        handle = page.evaluate(
            """() => {
              const element = document.querySelector('div.box-static');
              if (!element) return null;
              const box = element.getBoundingClientRect();
              return {x: box.x + box.width / 2, y: box.y + box.height / 2};
            }"""
        )
        regrab_x = float((handle or {}).get("x", start_x))
        regrab_y = float((handle or {}).get("y", start_y))
        page.mouse.move(regrab_x, regrab_y, steps=2)
        page.wait_for_timeout(25)
        page.mouse.down()
        page.wait_for_timeout(50)
        remainder = max(12.0, target - float(_read_piece_left(page) or 0.0))
        remainder_steps = max(10, int(remainder / 6))
        mouse_x = regrab_x
        for index in range(1, remainder_steps + 1):
            mouse_x = regrab_x + remainder * index / remainder_steps
            page.mouse.move(mouse_x, regrab_y + _tremor(0.25), steps=2)
            page.wait_for_timeout(7)
        mid_piece = float(_read_piece_left(page) or mid_piece)

    gain = 1.05
    last_piece = mid_piece
    last_mouse = mouse_x
    stable_hits = 0
    no_move_streak = 0
    deadline = time.monotonic() + config.longcat_slider_loop_ms / 1000
    samples = 0
    while time.monotonic() < deadline and samples < 55:
        piece = _read_piece_left(page)
        if piece is None:
            break
        error = target - piece
        if abs(error) <= config.longcat_slider_tolerance_px:
            stable_hits += 1
            if stable_hits >= 2:
                page.mouse.up()
                page.wait_for_timeout(220 + secrets.randbelow(81))
                return float(piece), True
            page.wait_for_timeout(10)
            samples += 1
            continue
        stable_hits = 0

        piece_delta = piece - last_piece
        mouse_delta = mouse_x - last_mouse
        no_move_streak = (
            no_move_streak + 1 if abs(piece_delta) < 0.3 and abs(mouse_delta) > 2 else 0
        )
        if no_move_streak >= 8 and piece < 5:
            page.mouse.up()
            return float(piece), False
        if abs(piece_delta) > 0.6 and abs(mouse_delta) > 0.5:
            observed = max(0.8, min(abs(mouse_delta) / abs(piece_delta), 1.8))
            gain = 0.6 * gain + 0.4 * observed
        last_piece, last_mouse = float(piece), mouse_x

        magnitude = abs(error)
        factor = (
            0.55 if magnitude > 30 else 0.42 if magnitude > 12 else 0.32 if magnitude > 5 else 0.22
        )
        step = max(-16.0, min(16.0, error * gain * factor))
        if abs(step) < 0.5:
            step = 0.6 if error > 0 else -0.6
        mouse_x = max(start_x - 2, min(start_x + max_travel + 6, mouse_x + step))
        page.mouse.move(mouse_x, start_y + _tremor(0.2))
        page.wait_for_timeout(7)
        samples += 1

    page.mouse.up()
    final = float(_read_piece_left(page) or last_piece)
    page.wait_for_timeout(200)
    return final, abs(final - target) <= config.longcat_slider_tolerance_px * 2.5


def _drag_longcat_open_loop(
    page,
    start: tuple[float, float],
    distance: float,
) -> float:
    start_x, start_y = start
    distance = max(18.0, min(320.0, float(distance)))
    duration = max(0.32, min(0.85, settings().longcat_slider_drag_seconds))
    steps = max(22, min(48, round(duration * 70)))
    end_x = start_x + distance
    end_y = start_y + random.uniform(-0.4, 0.4)
    control_one_x = start_x + distance * random.uniform(0.18, 0.32)
    control_two_x = start_x + distance * random.uniform(0.62, 0.8)
    control_one_y = start_y + random.uniform(-2.5, 2.5)
    control_two_y = start_y + random.uniform(-2.0, 2.0)
    points: list[tuple[float, float]] = []
    for index in range(steps):
        ratio = index / (steps - 1)
        inverse = 1.0 - ratio
        x = (
            inverse**3 * start_x
            + 3 * inverse**2 * ratio * control_one_x
            + 3 * inverse * ratio**2 * control_two_x
            + ratio**3 * end_x
        )
        y = (
            inverse**3 * start_y
            + 3 * inverse**2 * ratio * control_one_y
            + 3 * inverse * ratio**2 * control_two_y
            + ratio**3 * end_y
        )
        points.append((x, y))
    points[-1] = (end_x, end_y)
    cumulative = [0.0]
    for index in range(1, len(points)):
        cumulative.append(
            cumulative[-1]
            + math.hypot(
                points[index][0] - points[index - 1][0],
                points[index][1] - points[index - 1][1],
            )
        )
    total = max(cumulative[-1], 1e-6)
    raw_delays: list[float] = []
    for index, position in enumerate(cumulative):
        fraction = position / total
        segment = position - cumulative[index - 1] if index else total / steps
        if fraction < 0.12:
            speed = 0.55 + 0.55 * fraction / 0.12
        elif fraction > 0.92:
            speed = 1.05 - 0.2 * (fraction - 0.92) / 0.08
        else:
            speed = 1.18
        raw_delays.append(segment / max(0.85, min(1.3, speed)))
    delay_scale = duration / max(sum(raw_delays), 1e-6)

    page.mouse.move(start_x - 10, start_y)
    page.wait_for_timeout(15)
    page.mouse.move(start_x, start_y, steps=3)
    page.wait_for_timeout(25)
    page.mouse.down()
    page.wait_for_timeout(40)
    for index, (x, y) in enumerate(points):
        ratio = index / (steps - 1)
        tremor = random.uniform(-0.25, 0.25) * (1.0 - ratio * 0.85)
        page.mouse.move(x, y + tremor)
        delay_ms = max(1, round(raw_delays[index] * delay_scale * 1_000))
        if index == len(points) - 1:
            delay_ms = min(delay_ms, 4)
        if delay_ms > 1:
            page.wait_for_timeout(delay_ms)
    page.mouse.up()
    page.wait_for_timeout(280)
    return float(_read_piece_left(page) or 0.0)


def _extract_longcat_piece(background: bytes, piece: bytes) -> LongCatPieceGeometry | None:
    try:
        import cv2
        import numpy as np

        bg = cv2.imdecode(np.frombuffer(background, np.uint8), cv2.IMREAD_UNCHANGED)
        pc = cv2.imdecode(np.frombuffer(piece, np.uint8), cv2.IMREAD_UNCHANGED)
        if bg is None or pc is None:
            return None

        def bgr(image):
            if image.ndim == 2:
                return cv2.cvtColor(image, cv2.COLOR_GRAY2BGR)
            if image.shape[2] == 4:
                return cv2.cvtColor(image, cv2.COLOR_BGRA2BGR)
            return image[:, :, :3]

        bg_bgr = bgr(bg)
        pc_bgr = bgr(pc)
        height = min(bg_bgr.shape[0], pc_bgr.shape[0])
        width = min(bg_bgr.shape[1], pc_bgr.shape[1])
        if height < 30 or width < 20:
            return None
        delta = cv2.absdiff(pc_bgr[:height, :width], bg_bgr[:height, :width])
        mask = (delta.max(axis=2) >= 16).astype("uint8") * 255
        mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((2, 2), np.uint8))
        mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))
        xs = np.where((mask > 0).sum(axis=0) >= max(3, int(height * 0.025)))[0]
        ys = np.where((mask > 0).sum(axis=1) >= max(3, int(width * 0.04)))[0]
        if len(xs) < 12 or len(ys) < 12:
            return None
        x0, x1 = int(xs.min()), int(xs.max()) + 1
        y0, y1 = int(ys.min()), int(ys.max()) + 1
        visual_width = x1 - x0
        visual_height = y1 - y0
        if (
            visual_width < 12
            or visual_width > int(width * 0.9)
            or visual_height < 12
            or visual_height > int(height * 0.75)
        ):
            return None
        crop = pc_bgr[y0:y1, x0:x1]
        alpha = mask[y0:y1, x0:x1]
        rgba = cv2.cvtColor(crop, cv2.COLOR_BGR2BGRA)
        rgba[:, :, 3] = alpha
        encoded, output = cv2.imencode(".png", rgba)
        if not encoded:
            return None
        return LongCatPieceGeometry(output.tobytes(), x0, visual_width)
    except Exception:  # noqa: BLE001 - geometry is an optional enhancement
        return None


def _longcat_gap_estimates(
    background: bytes,
    piece: bytes,
    natural_width: int,
    piece_width: int,
) -> tuple[list[LongCatGapEstimate], list[str]]:
    estimates: list[LongCatGapEstimate] = []
    diagnostics: list[str] = []
    ddddocr = registry.solve_slider_ddddocr_variant_sync(
        background,
        piece,
        simple_target=False,
        solver_name="ddddocr",
        confidence=0.88,
    ) or registry.solve_slider_ddddocr_variant_sync(
        background,
        piece,
        simple_target=True,
        solver_name="ddddocr",
        confidence=0.72,
    )
    if ddddocr is not None and _plausible_longcat_gap(
        float(ddddocr.value), natural_width, piece_width
    ):
        estimates.append(
            LongCatGapEstimate(
                ddddocr.solver,
                float(ddddocr.value),
                ddddocr.confidence,
                ddddocr.confidence,
                ddddocr.detail,
            )
        )
        diagnostics.append(f"ddddocr={float(ddddocr.value):.1f}")
    else:
        value = float(ddddocr.value) if ddddocr is not None else None
        diagnostics.append(f"ddddocr={'none' if value is None else f'rejected:{value:.1f}'}")
    opencv = _longcat_opencv_estimate(background, piece)
    if opencv is not None and _plausible_longcat_gap(opencv.value, natural_width, piece_width):
        estimates.append(
            LongCatGapEstimate(
                opencv.solver,
                opencv.value,
                opencv.confidence,
                opencv.confidence * 0.9,
                opencv.detail,
            )
        )
        diagnostics.append(f"opencv={opencv.value:.1f}")
    else:
        value = opencv.value if opencv is not None else None
        diagnostics.append(f"opencv={'none' if value is None else f'rejected:{value:.1f}'}")
    recognizer = registry.solve_slider_recognizer_sync(background)
    if recognizer is not None and _plausible_longcat_gap(
        float(recognizer.value), natural_width, piece_width
    ):
        estimates.append(
            LongCatGapEstimate(
                "recognizer",
                float(round(float(recognizer.value))),
                recognizer.confidence,
                recognizer.confidence * 1.1,
                recognizer.detail,
            )
        )
        diagnostics.append(f"recognizer={float(recognizer.value):.1f}")
    else:
        value = float(recognizer.value) if recognizer is not None else None
        diagnostics.append(f"recognizer={'none' if value is None else f'rejected:{value:.1f}'}")
    edge = _longcat_edge_estimate(background, piece_width)
    if edge is not None and _plausible_longcat_gap(edge.value, natural_width, piece_width):
        estimates.append(
            LongCatGapEstimate(
                edge.solver,
                edge.value,
                edge.confidence,
                edge.confidence * (0.55 if estimates else 1.0),
                edge.detail,
            )
        )
        diagnostics.append(f"edge={edge.value:.1f}")
    else:
        value = edge.value if edge is not None else None
        diagnostics.append(f"edge={'none' if value is None else f'rejected:{value:.1f}'}")
    return estimates, diagnostics


def _longcat_opencv_estimate(background: bytes, piece: bytes) -> LongCatGapEstimate | None:
    try:
        import cv2
        import numpy as np

        bg = cv2.imdecode(np.frombuffer(background, np.uint8), cv2.IMREAD_UNCHANGED)
        pc = cv2.imdecode(np.frombuffer(piece, np.uint8), cv2.IMREAD_UNCHANGED)
        if bg is None or pc is None:
            return None

        def gray(image):
            if image.ndim == 2:
                return image
            return cv2.cvtColor(
                image,
                cv2.COLOR_BGRA2GRAY if image.shape[2] == 4 else cv2.COLOR_BGR2GRAY,
            )

        bg_gray = gray(bg)
        alpha = pc[:, :, 3] if pc.ndim == 3 and pc.shape[2] == 4 else None
        pc_gray = gray(pc)
        if alpha is not None:
            ys, xs = np.where(alpha > 20)
            if len(xs) > 50:
                y0, y1 = int(ys.min()), int(ys.max()) + 1
                x0, x1 = int(xs.min()), int(xs.max()) + 1
                pc_gray = pc_gray[y0:y1, x0:x1]
                alpha = alpha[y0:y1, x0:x1]
        bg_edges = cv2.Canny(bg_gray, 80, 180)
        piece_edges = cv2.Canny(pc_gray, 80, 180)
        if alpha is not None:
            piece_edges = cv2.bitwise_and(
                piece_edges,
                piece_edges,
                mask=(alpha > 20).astype("uint8") * 255,
            )
        if piece_edges.shape[0] >= bg_edges.shape[0] or piece_edges.shape[1] >= bg_edges.shape[1]:
            return None
        result = cv2.matchTemplate(bg_edges, piece_edges, cv2.TM_CCOEFF_NORMED)
        left_cut = max(16, int(bg_edges.shape[1] * 0.15), int(piece_edges.shape[1] * 0.5))
        result[:, :left_cut] = -1.0
        right_cut = max(
            left_cut + 8,
            int(bg_edges.shape[1] * 0.82) - piece_edges.shape[1],
        )
        if right_cut < result.shape[1]:
            result[:, right_cut:] = -1.0
        _, score, _, location = cv2.minMaxLoc(result)
        if location[0] < 12:
            return None
        confidence = 0.45 + 0.45 * max(0.0, min(1.0, float(score)))
        return LongCatGapEstimate(
            "opencv",
            float(location[0]),
            confidence,
            confidence,
            f"score={score:.3f}",
        )
    except Exception:  # noqa: BLE001 - one estimator failure must not abort fusion
        return None


def _longcat_edge_estimate(background: bytes, piece_width: int) -> LongCatGapEstimate | None:
    try:
        import cv2
        import numpy as np

        image = cv2.imdecode(np.frombuffer(background, np.uint8), cv2.IMREAD_UNCHANGED)
        if image is None:
            return None
        if image.ndim == 3:
            image = cv2.cvtColor(
                image,
                cv2.COLOR_BGRA2GRAY if image.shape[2] == 4 else cv2.COLOR_BGR2GRAY,
            )
        height, width = image.shape
        window = piece_width if piece_width > 20 else max(40, int(width * 0.3))
        window = min(window, int(width * 0.48))
        roi = image[int(height * 0.15) : int(height * 0.85), :]
        edges = cv2.Canny(roi, 50, 150)
        luminance = roi.mean(axis=0).astype(np.float64)
        edge_columns = edges.sum(axis=0).astype(np.float64)
        kernel = np.array([0.1, 0.2, 0.4, 0.2, 0.1], dtype=np.float64)
        smoothed = np.convolve(edge_columns, kernel, mode="same")
        global_luminance = float(luminance.mean()) if luminance.size else 128.0
        left_min = max(int(width * 0.16), int(window * 0.35))
        left_max = min(int(width * 0.72), width - window - 4)
        if left_max < left_min:
            return None
        best_left = (left_min + left_max) // 2
        best_score = float("-inf")
        for left in range(left_min, left_max + 1):
            right = left + window
            middle = luminance[left + int(window * 0.2) : left + int(window * 0.8)]
            interior = float(middle.mean()) if middle.size else global_luminance
            darkness = max(0.0, global_luminance - interior)
            left_edge = float(smoothed[max(0, left - 2) : min(width, left + 4)].max())
            right_edge = float(smoothed[max(0, right - 3) : min(width, right + 3)].max())
            if left_edge < right_edge * 0.35 and left_edge < 80:
                continue
            position_bias = 1.0 - 0.12 * ((left - left_min) / max(1, left_max - left_min))
            score = (
                darkness * 3.0
                + left_edge * 1.4
                + right_edge * 0.7
                + min(left_edge, right_edge) * 0.8
            ) * position_bias
            if score > best_score:
                best_score = score
                best_left = left
        refined = best_left
        peak = float(smoothed[best_left])
        for candidate in range(max(left_min, best_left - 6), min(left_max, best_left + 4) + 1):
            if float(smoothed[candidate]) > peak:
                peak = float(smoothed[candidate])
                refined = candidate
        confidence = 0.62 if best_score > 0 else 0.35
        return LongCatGapEstimate(
            "edge",
            float(refined),
            confidence,
            confidence,
            f"window={window}; score={best_score:.1f}",
        )
    except Exception:  # noqa: BLE001 - edge scan is the final optional estimator
        return None


def _plausible_longcat_gap(gap: float, width: int, piece_width: int) -> bool:
    left_min = max(int(width * 0.18), int(piece_width * 0.55) if piece_width else 0)
    return gap >= max(12, left_min) and gap <= int(width * 0.78)


def _fuse_longcat_gap(estimates: list[LongCatGapEstimate]) -> tuple[int, float]:
    if not estimates:
        raise RuntimeError("LongCat slider solver produced no estimate")
    if len(estimates) == 1:
        return round(estimates[0].value), estimates[0].weight
    best_score = -1.0
    best_cluster: list[LongCatGapEstimate] = []
    for seed in estimates:
        cluster = [item for item in estimates if abs(item.value - seed.value) <= 18.0]
        score = sum(item.weight for item in cluster) * (1.0 + 0.15 * len(cluster))
        if score > best_score:
            best_score = score
            best_cluster = cluster
    total = sum(item.weight for item in best_cluster) or 1.0
    value = round(sum(item.value * item.weight for item in best_cluster) / total)
    return value, min(0.99, total / (total + 0.8))


def _image_size(image: bytes) -> tuple[int, int]:
    with Image.open(io.BytesIO(image)) as source:
        return source.size


def _read_piece_left(page) -> float | None:
    try:
        value = page.evaluate(
            """() => {
              const piece = document.querySelector('.global-puzzle-slider-drag');
              const image = document.querySelector('.global-puzzle-slider-image') ||
                document.querySelector('.global-puzzle-main');
              if (!piece || !image) return null;
              return piece.getBoundingClientRect().x - image.getBoundingClientRect().x;
            }"""
        )
        return float(value) if value is not None else None
    except Exception:  # noqa: BLE001 - challenge DOM can rotate during reads
        return None


def _wait_yoda_cleared(page, timeout_ms: int) -> bool:
    deadline = time.monotonic() + timeout_ms / 1000
    clear_streak = 0
    while time.monotonic() < deadline:
        clear_streak = 0 if yoda_visible(page) else clear_streak + 1
        if clear_streak >= 3:
            return True
        page.wait_for_timeout(150)
    return False


def _visible(page, selector: str) -> bool:
    try:
        locator = page.locator(selector)
        return any(locator.nth(index).is_visible() for index in range(locator.count()))
    except Exception:  # noqa: BLE001 - DOM changes are expected during challenge transitions
        return False


def _yoda_text(page) -> str:
    values: list[str] = []
    for selector in (
        ".yoda-global-inference-title",
        ".sudoku-title",
        ".yoda-modal-content",
        ".global-puzzle-main",
        "#yodaVerify",
        "body",
    ):
        try:
            value = page.locator(selector).first.inner_text(timeout=750)
            if value:
                values.append(value)
        except Exception:  # noqa: BLE001,S112 - challenge layouts are mutually exclusive
            continue
    return "\n".join(values)


def _yoda_color(text: str) -> str:
    for color in ("yellow", "green", "orange", "purple", "blue", "red"):
        if color in text:
            return color
    return "any"


def _best_points(estimates: list[Any], challenge: str) -> list[tuple[float, float]]:
    candidates = [item for item in estimates if isinstance(item.value, list) and item.value]
    if not candidates:
        raise RuntimeError(f"LongCat {challenge} solver produced no candidate")
    value = max(candidates, key=lambda item: item.confidence).value
    return [(float(point[0]), float(point[1])) for point in value]


def _tremor(maximum: float) -> float:
    return (secrets.randbelow(2001) - 1000) / 1000 * maximum
