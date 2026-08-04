from __future__ import annotations

import asyncio
import base64
import logging
import random
import time
from dataclasses import dataclass
from typing import Any
from urllib.parse import urljoin

from .qwen_challenge import (
    BACKGROUND_SELECTORS,
    CONTROL_SELECTOR,
    PIECE_SELECTORS,
    SLIDER_SELECTORS,
    _captcha_image_url,
    _fuse_qwen_gap,
    _image_width,
    _piece_target_to_drag,
    _qwen_gap_estimates,
)
from .qwen_settings import settings

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class QwenInferenceChallengeResult:
    attempts: int
    diagnostic: str


class QwenInferenceChallengeSolver:
    """Clears a Baxia slider in the browser context that received the punish URL."""

    async def solve(self, page: Any, punish_url: str) -> QwenInferenceChallengeResult:
        image_bag: dict[str, bytes] = {}
        pending: set[asyncio.Task[None]] = set()

        def observe(response: Any) -> None:
            task = asyncio.create_task(_capture_response_image(response, image_bag))
            pending.add(task)
            task.add_done_callback(pending.discard)

        page.on("response", observe)
        try:
            await page.goto(punish_url, wait_until="domcontentloaded", timeout=60_000)
            await page.wait_for_timeout(1_500)
            diagnostics: list[str] = []
            for attempt in range(1, max(1, settings().qwen_signup_attempts) + 1):
                slider = await _first_visible(page, SLIDER_SELECTORS, 8_000)
                if slider is None:
                    if await _challenge_cleared(page):
                        return QwenInferenceChallengeResult(
                            attempt, "challenge_cleared_without_drag"
                        )
                    raise RuntimeError("Qwen inference slider handle is unavailable")

                background = await _first_visible(page, BACKGROUND_SELECTORS, 1_500)
                piece = await _first_visible(page, PIECE_SELECTORS, 1_500)
                await _drain(pending)
                background_bytes = (
                    await _image_bytes(page, background) if background is not None else None
                ) or image_bag.get("background")
                piece_bytes = (
                    await _image_bytes(page, piece) if piece is not None else None
                ) or image_bag.get("piece")

                slider_box = await slider.bounding_box()
                if not slider_box:
                    raise RuntimeError("Qwen inference slider geometry is unavailable")
                max_travel = await _max_slider_travel(page, slider, slider_box)
                if background_bytes and piece_bytes:
                    estimates = await asyncio.to_thread(
                        _qwen_gap_estimates, background_bytes, piece_bytes
                    )
                    if not estimates:
                        raise RuntimeError(
                            "Qwen inference slider produced no deterministic estimate"
                        )
                    fused, confidence = _fuse_qwen_gap(estimates)
                    background_box = (
                        await background.bounding_box() if background is not None else None
                    )
                    display_width = float(
                        background_box["width"]
                        if background_box
                        else max_travel + slider_box["width"]
                    )
                    natural_width = (
                        await _natural_width(background) if background is not None else 0.0
                    ) or float(_image_width(background_bytes) or display_width)
                    target_piece = float(fused) * display_width / natural_width
                    final_piece, elapsed, samples = await _drag_slider_to_piece_target(
                        page,
                        slider,
                        target_piece,
                        max_travel,
                        settings().qwen_slider_drag_offset_px,
                        min(
                            settings().qwen_slider_drag_budget_ms / 1000,
                            random.uniform(0.75, 0.95),
                        ),
                    )
                    diagnostic = (
                        f"attempt={attempt}, mode=puzzle, fused={fused:.1f}, "
                        f"confidence={confidence:.2f}, target={target_piece:.1f}, "
                        f"final={final_piece:.1f}, error={final_piece - target_piece:.1f}, "
                        f"elapsed={elapsed:.2f}, samples={samples}"
                    )
                else:
                    surface = await _surface_diagnostic(page, slider)
                    logger.info("qwen_inference_slider_surface %s", surface)
                    elapsed = await _drag_slider_full_track(page, slider, max_travel)
                    diagnostic = (
                        f"attempt={attempt}, mode=full_track, travel={max_travel:.1f}, "
                        f"elapsed={elapsed:.2f}, surface={surface}"
                    )
                diagnostics.append(diagnostic)
                if await _wait_until_cleared(page, 6_000):
                    return QwenInferenceChallengeResult(attempt, diagnostic)
                await _refresh(page)
                image_bag.clear()
                await page.wait_for_timeout(random.randint(800, 1_300))
            raise RuntimeError(
                "Qwen inference slider exhausted all attempts: " + "; ".join(diagnostics)
            )
        finally:
            page.remove_listener("response", observe)
            await _drain(pending)


async def _capture_response_image(response: Any, image_bag: dict[str, bytes]) -> None:
    try:
        lowered = response.url.lower()
        if not _captcha_image_url(lowered):
            return
        body = await response.body()
        if not body:
            return
        if any(marker in lowered for marker in ("shadow", "piece", "slider")):
            image_bag["piece"] = body
        elif any(marker in lowered for marker in ("back", "bg", "puzzle")):
            image_bag["background"] = body
    except Exception:  # noqa: BLE001 - navigation can dispose response bodies
        return


async def _drain(tasks: set[asyncio.Task[None]]) -> None:
    if tasks:
        await asyncio.gather(*tuple(tasks), return_exceptions=True)


async def _max_slider_travel(
    page: Any,
    slider: Any,
    slider_box: dict[str, float],
) -> float:
    body = page.locator("#aliyunCaptcha-sliding-body").first
    body_box = await body.bounding_box() if await body.count() else None
    if body_box and body_box["width"] > slider_box["width"]:
        return max(1.0, float(body_box["width"] - slider_box["width"]))
    candidate = await slider.evaluate(
        """element => {
          const handle = element.getBoundingClientRect();
          let current = element.parentElement;
          let best = 0;
          for (let depth = 0; current && depth < 6; depth += 1, current = current.parentElement) {
            const rect = current.getBoundingClientRect();
            if (rect.width >= 180 && rect.width <= 640 && rect.height <= 240) {
              best = Math.max(best, rect.width - handle.width);
            }
          }
          return best;
        }"""
    )
    return max(1.0, min(float(candidate or 260.0), 600.0))


async def _surface_diagnostic(page: Any, slider: Any) -> str:
    try:
        value = await slider.evaluate(
            """element => ({
              tag: element.tagName,
              id: element.id || '',
              className: String(element.className || '').slice(0, 160),
              text: String(element.parentElement?.innerText || '').trim().slice(0, 160),
              images: document.querySelectorAll('img').length,
              canvases: document.querySelectorAll('canvas').length
            })"""
        )
        return str(value)
    except Exception as error:  # noqa: BLE001 - diagnostics must not mask the solver result
        return f"unavailable:{type(error).__name__}"


async def _drag_slider_full_track(page: Any, slider: Any, max_travel: float) -> float:
    box = await slider.bounding_box()
    if not box:
        raise RuntimeError("Qwen inference slider handle has no geometry")
    start_x = box["x"] + box["width"] / 2.0
    start_y = box["y"] + box["height"] / 2.0
    target = start_x + max(1.0, float(max_travel)) - random.uniform(0.2, 1.2)
    duration = random.uniform(0.78, 0.95)
    steps = random.randint(34, 42)
    await page.mouse.move(start_x - random.uniform(8, 14), start_y)
    await page.wait_for_timeout(random.randint(20, 45))
    await page.mouse.move(start_x, start_y)
    await page.wait_for_timeout(random.randint(30, 60))
    await page.mouse.down()
    started = time.time()
    for index in range(1, steps + 1):
        ratio = index / steps
        eased = ratio * ratio * (3 - 2 * ratio)
        await page.mouse.move(
            start_x + (target - start_x) * eased,
            start_y + random.uniform(-0.35, 0.35),
        )
        await page.wait_for_timeout(max(5, int(duration * 1_000 / steps)))
    await page.wait_for_timeout(random.randint(40, 90))
    await page.mouse.up()
    return time.time() - started


async def _first_visible(page: Any, selectors: tuple[str, ...], timeout_ms: int) -> Any | None:
    deadline = time.monotonic() + timeout_ms / 1000
    while time.monotonic() < deadline:
        for selector in selectors:
            locator = page.locator(selector).first
            try:
                if await locator.count() and await locator.is_visible():
                    return locator
            except Exception:  # noqa: BLE001,S112 - the challenge can replace its DOM mid-poll
                continue
        await page.wait_for_timeout(100)
    return None


async def _challenge_cleared(page: Any) -> bool:
    try:
        cookies = await page.context.cookies()
        if any(cookie.get("name", "").lower().startswith("x5sec") for cookie in cookies):
            return True
        if "/punish" in page.url:
            return False
        controls = page.locator(CONTROL_SELECTOR)
        return not any(
            await controls.nth(index).is_visible() for index in range(await controls.count())
        )
    except Exception:  # noqa: BLE001 - navigation during success is treated as cleared
        return True


async def _wait_until_cleared(page: Any, timeout_ms: int) -> bool:
    clear_streak = 0
    for _ in range(max(1, timeout_ms // 100)):
        await page.wait_for_timeout(100)
        if await _challenge_cleared(page):
            clear_streak += 1
            if clear_streak >= settings().qwen_slider_clear_streak:
                return True
        else:
            clear_streak = 0
    return False


async def _refresh(page: Any) -> None:
    refresh = page.locator("#aliyunCaptcha-btn-refresh").first
    try:
        if await refresh.count() and await refresh.is_visible():
            await refresh.click(timeout=2_000)
    except Exception:  # noqa: BLE001 - the next attempt re-detects the live surface
        return


async def _image_bytes(page: Any, locator: Any) -> bytes | None:
    try:
        source = str(await locator.get_attribute("src") or "").strip()
        if source.startswith("data:") and "," in source:
            return base64.b64decode(source.split(",", 1)[1])
        if source and not source.startswith(("data:", "blob:")):
            response = await page.request.get(urljoin(page.url, source), timeout=20_000)
            if response.ok:
                return await response.body()
    except Exception:  # noqa: BLE001,S110 - a screenshot is the stable fallback
        pass
    try:
        return await locator.screenshot(type="png")
    except Exception:  # noqa: BLE001 - challenge refresh can dispose the locator
        return None


async def _natural_width(locator: Any) -> float:
    try:
        return float(await locator.evaluate("el => el.naturalWidth || el.width || 0") or 0)
    except Exception:  # noqa: BLE001 - displayed width is the fallback
        return 0.0


async def _read_piece_left(page: Any) -> float | None:
    try:
        value = await page.evaluate(
            """() => {
              const piece = document.getElementById('aliyunCaptcha-puzzle');
              const image = document.getElementById('aliyunCaptcha-img');
              if (!piece || !image) return null;
              return piece.getBoundingClientRect().x - image.getBoundingClientRect().x;
            }"""
        )
        return float(value) if value is not None else None
    except Exception:  # noqa: BLE001 - transient replacement is retried by feedback control
        return None


async def _drag_slider_to_piece_target(
    page: Any,
    slider: Any,
    target_piece: float,
    max_travel: float,
    drag_offset: float,
    duration: float,
) -> tuple[float, float, int]:
    box = await slider.bounding_box()
    if not box:
        raise RuntimeError("Qwen inference slider handle has no geometry")
    travel = max(float(max_travel), 1.0)
    target = max(0.0, min(float(target_piece) + float(drag_offset) * 0.2, travel))
    start_x = box["x"] + box["width"] / 2.0
    start_y = box["y"] + box["height"] / 2.0
    mouse_limit = start_x + travel + 8.0
    duration = max(0.65, min(float(duration), 1.0))
    kick = max(8.0, min(_piece_target_to_drag(target * 0.5, travel), travel * 0.65))

    await page.mouse.move(start_x - random.uniform(8, 16), start_y)
    await page.wait_for_timeout(8)
    await page.mouse.move(start_x, start_y)
    await page.wait_for_timeout(8)
    await page.mouse.down()
    await page.wait_for_timeout(10)

    mouse_x = start_x
    started = time.time()
    kick_steps = max(8, int(kick / 12))
    per_step_ms = max(4, max(80, int(duration * 1_000 * 0.32)) // kick_steps)
    for index in range(1, kick_steps + 1):
        ratio = index / kick_steps
        eased = ratio * ratio * (3 - 2 * ratio)
        mouse_x = start_x + kick * eased
        await page.mouse.move(mouse_x, start_y + random.uniform(-0.3, 0.3))
        await page.wait_for_timeout(per_step_ms)

    gain = 1.7
    last_piece = float(await _read_piece_left(page) or 0.0)
    last_mouse = mouse_x
    correction_deadline = time.time() + duration
    samples = 0
    while time.time() < correction_deadline:
        observed_piece = await _read_piece_left(page)
        piece = float(last_piece if observed_piece is None else observed_piece)
        error = target - piece
        if abs(error) <= settings().qwen_slider_tolerance_px:
            await page.mouse.up()
            return piece, time.time() - started, samples
        piece_delta = piece - last_piece
        mouse_delta = mouse_x - last_mouse
        if piece_delta > 0.6 and abs(mouse_delta) > 0.5:
            observed_gain = max(0.95, min(abs(mouse_delta) / piece_delta, 3.8))
            gain = 0.6 * gain + 0.4 * observed_gain
        last_piece, last_mouse = piece, mouse_x
        if abs(error) > 40:
            step, sleep_ms = max(-22.0, min(22.0, error * gain * 0.55)), 5
        elif abs(error) > 15:
            step, sleep_ms = max(-12.0, min(12.0, error * gain * 0.42)), 6
        elif abs(error) > 6:
            step, sleep_ms = max(-6.0, min(6.0, error * gain * 0.35)), 6
        else:
            step, sleep_ms = max(-2.5, min(2.5, error * gain * 0.3)), 5
        if abs(step) < 0.6:
            step = 0.7 if error > 0 else -0.7
        mouse_x = max(start_x, min(mouse_limit, mouse_x + step))
        await page.mouse.move(mouse_x, start_y + random.uniform(-0.25, 0.25))
        await page.wait_for_timeout(sleep_ms)
        samples += 1
        if samples > 60:
            break
    await page.mouse.up()
    final = float(await _read_piece_left(page) or last_piece)
    return final, time.time() - started, samples
