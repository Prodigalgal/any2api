from __future__ import annotations

import base64
import io
import random
import time
from dataclasses import dataclass, field
from typing import Any
from urllib.parse import urljoin

from ..captcha.models import SolverEstimate
from ..captcha.registry import registry
from ..captcha.strategy import (
    ChallengeAttemptResult,
    ChallengeDetection,
    ChallengePolicy,
    ChallengeRunner,
    ChallengeStrategy,
)
from ..lifecycle.browser import first_visible
from .qwen_settings import settings

SLIDER_SELECTORS = (
    "#aliyunCaptcha-sliding-slider",
    "div.slider-move",
    "#aliyunCaptcha-sliding-body .slider-move",
    ".btn_slide",
    "#nc_1_n1z",
)
BACKGROUND_SELECTORS = (
    "#aliyunCaptcha-img",
    "img#aliyunCaptcha-img",
    "img.puzzle",
    'img[src*="back.png"]',
)
PIECE_SELECTORS = (
    "#aliyunCaptcha-puzzle",
    "img#aliyunCaptcha-puzzle",
    'img[src*="shadow.png"]',
)
CONTROL_SELECTOR = ", ".join(("#waf_nc_block", "#WAF_NC_WRAPPER", *SLIDER_SELECTORS))


@dataclass
class QwenSignupChallenge(ChallengeStrategy):
    image_bag: dict[str, bytes] = field(default_factory=dict)
    signup_responses: list[dict[str, Any]] = field(default_factory=list)
    token: str = ""
    user_id: str = ""
    last_diagnostic: str = "unavailable"

    @property
    def strategy_id(self) -> str:
        return "feilin-slider"

    def attach(self, page) -> None:
        page.on("response", self._observe_response)

    def submit_and_solve(self, page, submit) -> None:
        config = settings()
        runner = ChallengeRunner()
        for submission in range(2):
            if self.succeeded():
                return
            captcha_already_visible = self._captcha_visible(page)
            if not captcha_already_visible:
                self.image_bag.clear()
                submit.click()
                page.wait_for_timeout(_jitter(1_500, 3_000))

            deadline = time.monotonic() + config.qwen_captcha_appear_ms / 1000
            while time.monotonic() < deadline:
                if self.succeeded() or self._verification_hint(page):
                    return
                if self._captcha_visible(page):
                    break
                page.wait_for_timeout(500)
            result = runner.run(
                page,
                self,
                ChallengePolicy(
                    max_attempts=max(1, config.qwen_signup_attempts),
                    first_detection_timeout_ms=5_000,
                    retry_settle_ms=_jitter(900, 1_400),
                ),
            )
            if result.present and not result.solved:
                raise RuntimeError(
                    "Qwen signup challenge exhausted all attempts "
                    f"({result.diagnostic}, responses={self._response_summary()})"
                )

            deadline = time.monotonic() + 8
            while time.monotonic() < deadline:
                if self.succeeded() or self._verification_hint(page):
                    return
                page.wait_for_timeout(120)
            if submission == 0 and submit.is_visible() and not submit.is_disabled():
                page.wait_for_timeout(_jitter(1_200, 2_000))

        if self.succeeded() or self._verification_hint(page):
            return
        raise RuntimeError(
            "Qwen signup challenge exhausted all attempts "
            f"({self.last_diagnostic}, responses={self._response_summary()})"
        )

    def detect(self, page, timeout_ms: int) -> ChallengeDetection | None:
        slider = first_visible(page, SLIDER_SELECTORS, timeout_ms=timeout_ms)
        return ChallengeDetection(kind="slider", target=slider) if slider is not None else None

    def solve(
        self,
        page,
        detection: ChallengeDetection,
        attempt: int,
    ) -> ChallengeAttemptResult:
        self._solve_once(page, detection.target, attempt)
        return ChallengeAttemptResult(diagnostic=self.last_diagnostic)

    def verify(
        self,
        page,
        detection: ChallengeDetection,
        attempt: ChallengeAttemptResult,
    ) -> bool:
        del detection, attempt
        config = settings()
        clear_streak = 0
        for _ in range(50):
            page.wait_for_timeout(100)
            if self.succeeded():
                return True
            visible = _any_visible(page, CONTROL_SELECTOR)
            clear_streak = 0 if visible else clear_streak + 1
            if clear_streak >= config.qwen_slider_clear_streak:
                return True
        return False

    def refresh(self, page, detection: ChallengeDetection) -> None:
        del detection
        _refresh_qwen_puzzle(page, self.image_bag)

    def succeeded(self) -> bool:
        return any(item.get("successful") for item in self.signup_responses)

    def diagnostics(self) -> dict[str, str]:
        return {
            "challenge": self.last_diagnostic,
            "signup_responses": self._response_summary(),
        }

    def _observe_response(self, response) -> None:
        try:
            url = response.url
            lowered = url.lower()
            if "/api/v1/auths/signup" in lowered and response.request.method == "POST":
                content_type = response.headers.get("content-type", "")
                entry: dict[str, Any] = {
                    "status": response.status,
                    "json": "application/json" in content_type,
                    "u_atoken": "u_atoken" in lowered,
                    "successful": False,
                }
                if entry["json"]:
                    payload = response.json()
                    if isinstance(payload, dict):
                        entry["successful"] = response.status == 200 and not payload.get("error")
                        token = payload.get("token") or (payload.get("data") or {}).get("token")
                        user_id = payload.get("id") or (payload.get("data") or {}).get("id")
                        if token:
                            self.token = str(token)
                        if user_id:
                            self.user_id = str(user_id)
                elif response.status == 200 and entry["u_atoken"]:
                    entry["successful"] = True
                self.signup_responses.append(entry)

            if not _captcha_image_url(lowered):
                return
            body = response.body()
            if not body:
                return
            if any(marker in lowered for marker in ("shadow", "piece", "slider")):
                self.image_bag["piece"] = body
            elif any(marker in lowered for marker in ("back", "bg", "puzzle")):
                self.image_bag["background"] = body
        except Exception:  # noqa: BLE001 - navigation can dispose response bodies
            return

    def _solve_once(self, page, slider, attempt: int) -> None:
        config = settings()
        page.wait_for_timeout(_jitter(500, 1_000))
        current_left = _read_piece_left(page)
        if current_left is not None and current_left > 8:
            _refresh_qwen_puzzle(page, self.image_bag)
            page.wait_for_timeout(_jitter(600, 1_000))

        slider = first_visible(page, SLIDER_SELECTORS, timeout_ms=5_000)
        if slider is None:
            raise RuntimeError("Qwen slider handle is unavailable")
        background = first_visible(page, BACKGROUND_SELECTORS, timeout_ms=2_500)
        piece = first_visible(page, PIECE_SELECTORS, timeout_ms=2_500)

        background_bytes = (
            _image_bytes(page, background) if background is not None else None
        ) or self.image_bag.get("background")
        piece_bytes = (
            _image_bytes(page, piece) if piece is not None else None
        ) or self.image_bag.get("piece")

        slider_box = slider.bounding_box()
        background_box = background.bounding_box() if background is not None else None
        body = page.locator("#aliyunCaptcha-sliding-body").first
        body_box = body.bounding_box() if body.count() else None
        if not slider_box:
            raise RuntimeError("Qwen slider handle has no geometry")
        display_width = background_box["width"] if background_box else 300.0
        natural_width = _natural_width(background) if background is not None else 0.0
        natural_width = natural_width or (_image_width(background_bytes) if background_bytes else 0)
        natural_width = natural_width or display_width
        max_travel = max(
            1.0,
            (body_box["width"] - slider_box["width"]) if body_box else 260.0,
        )
        estimates: list[SolverEstimate] = []
        fusion_confidence = 0.0
        if background_bytes and piece_bytes:
            estimates = _qwen_gap_estimates(background_bytes, piece_bytes)
            if not estimates:
                raise RuntimeError("Qwen slider solver produced no estimate")
            fused, fusion_confidence = _fuse_qwen_gap(estimates)
            target_piece = float(fused) * display_width / natural_width
        else:
            fused = random.randint(120, 200)
            target_piece = float(fused)
        duration = min(
            config.qwen_slider_drag_budget_ms / 1000,
            random.uniform(0.75, 0.95),
        )
        final_piece, elapsed, samples = _drag_slider_to_piece_target(
            page,
            slider,
            target_piece,
            max_travel,
            config.qwen_slider_drag_offset_px,
            duration,
        )
        estimate_summary = ",".join(
            f"{item.solver}:{float(item.value):.1f}/{item.confidence:.2f}"
            for item in estimates
            if isinstance(item.value, (int, float))
        )
        self.last_diagnostic = (
            f"attempt={attempt}, estimates=[{estimate_summary}], fused={fused:.1f}, "
            f"fusionConfidence={fusion_confidence:.2f}, "
            f"natural={natural_width:.1f}, display={display_width:.1f}, "
            f"travel={max_travel:.1f}, target={target_piece:.1f}, "
            f"final={final_piece:.1f}, error={final_piece - target_piece:.1f}, "
            f"duration={duration:.2f}, elapsed={elapsed:.2f}, samples={samples}"
        )

    def _captcha_visible(self, page) -> bool:
        return _any_visible(page, CONTROL_SELECTOR) or bool(
            self.image_bag.get("background") and self.image_bag.get("piece")
        )

    def _verification_hint(self, page) -> bool:
        try:
            body = page.locator("body").inner_text(timeout=1_000).lower()
        except Exception:  # noqa: BLE001 - page can navigate during the probe
            return False
        return any(
            marker in body
            for marker in ("check your email", "verify your email", "激活", "验证邮件", "收件箱")
        )

    def _response_summary(self) -> str:
        return (
            ",".join(
                f"{item.get('status')}:{'json' if item.get('json') else 'html'}:"
                f"{'ok' if item.get('successful') else 'rejected'}"
                for item in self.signup_responses[-5:]
            )
            or "none"
        )


def pace(page, minimum_ms: int, maximum_ms: int) -> None:
    page.wait_for_timeout(_jitter(minimum_ms, maximum_ms))


def _captcha_image_url(url: str) -> bool:
    if not any(marker in url for marker in ("static-captcha", "puzzle", "captcha")):
        return False
    return any(marker in url for marker in (".png", ".jpg", ".jpeg", ".webp", "back", "shadow"))


def _refresh_qwen_puzzle(page, image_bag: dict[str, bytes]) -> None:
    image_bag.clear()
    refresh = page.locator("#aliyunCaptcha-btn-refresh").first
    try:
        if refresh.count() and refresh.is_visible():
            refresh.click(timeout=2_000)
    except Exception:  # noqa: BLE001 - the retry detection will validate refreshed state
        return


def _qwen_gap_estimates(background: bytes, piece: bytes) -> list[SolverEstimate]:
    estimates: list[SolverEstimate] = []
    for simple_target, solver_name, confidence in (
        (False, "ddddocr_crop", 0.88),
        (True, "ddddocr_full", 0.75),
    ):
        estimate = registry.solve_slider_ddddocr_variant_sync(
            background,
            piece,
            simple_target=simple_target,
            solver_name=solver_name,
            confidence=confidence,
        )
        if estimate is not None:
            estimates.append(estimate)
    recognizer = registry.solve_slider_recognizer_sync(background)
    if recognizer is not None:
        estimates.append(
            SolverEstimate(
                solver="recognizer",
                value=round(float(recognizer.value)),
                confidence=recognizer.confidence,
                detail=recognizer.detail,
            )
        )
    opencv = _qwen_opencv_estimate(background, piece)
    if opencv is not None:
        estimates.append(opencv)
    return estimates


def _qwen_opencv_estimate(background: bytes, piece: bytes) -> SolverEstimate | None:
    try:
        import cv2
        import numpy as np

        bg = cv2.imdecode(np.frombuffer(background, np.uint8), cv2.IMREAD_UNCHANGED)
        pc = cv2.imdecode(np.frombuffer(piece, np.uint8), cv2.IMREAD_UNCHANGED)
        if bg is None or pc is None:
            return None
        if bg.ndim == 2:
            bg_gray = bg
        elif bg.shape[2] == 4:
            bg_gray = cv2.cvtColor(bg, cv2.COLOR_BGRA2GRAY)
        else:
            bg_gray = cv2.cvtColor(bg, cv2.COLOR_BGR2GRAY)

        alpha = None
        if pc.ndim == 2:
            pc_gray = pc
        elif pc.shape[2] == 4:
            alpha = pc[:, :, 3]
            pc_gray = cv2.cvtColor(pc, cv2.COLOR_BGRA2GRAY)
            ys, xs = np.where(alpha > 20)
            if len(xs) > 50:
                y0, y1 = int(ys.min()), int(ys.max()) + 1
                x0, x1 = int(xs.min()), int(xs.max()) + 1
                pc_gray = pc_gray[y0:y1, x0:x1]
                alpha = alpha[y0:y1, x0:x1]
        else:
            pc_gray = cv2.cvtColor(pc, cv2.COLOR_BGR2GRAY)

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
        result[:, : max(16, int(bg_edges.shape[1] * 0.12))] = -1.0
        _, score, _, location = cv2.minMaxLoc(result)
        if location[0] < 20:
            return None
        confidence = 0.45 + 0.45 * max(0.0, min(1.0, float(score)))
        return SolverEstimate(
            solver="opencv",
            value=float(location[0]),
            confidence=confidence,
            detail=f"score={score:.3f}",
        )
    except Exception:  # noqa: BLE001 - one estimator failure must not abort fusion
        return None


def _qwen_estimate_weight(estimate: SolverEstimate) -> float:
    prior = {
        "ddddocr_crop": 1.0,
        "ddddocr_full": 0.75,
        "recognizer": 1.15,
        "opencv": 0.85,
    }.get(estimate.solver, 1.0)
    return prior * max(0.35, min(1.0, estimate.confidence))


def _fuse_qwen_gap(
    estimates: list[SolverEstimate], cluster_radius: float = 18.0
) -> tuple[int, float]:
    weighted = [
        (item.solver, round(float(item.value)), _qwen_estimate_weight(item))
        for item in estimates
        if isinstance(item.value, (int, float))
    ]
    if not weighted:
        raise RuntimeError("Qwen slider solver produced no numeric estimate")
    if len(weighted) == 1:
        return weighted[0][1], weighted[0][2]

    best_score = -1.0
    best_cluster: list[tuple[str, int, float]] = []
    for _, seed, _ in weighted:
        cluster = [item for item in weighted if abs(item[1] - seed) <= cluster_radius]
        score = sum(item[2] for item in cluster) * (1.0 + 0.15 * len(cluster))
        if len(cluster) >= 2:
            values = [item[1] for item in cluster]
            score *= 1.0 / (1.0 + (max(values) - min(values)) / 40.0)
        if any(name == "recognizer" and weight >= 1.0 for name, _, weight in cluster):
            score *= 1.25
        if score > best_score:
            best_score = score
            best_cluster = cluster

    recognizer = next((item for item in weighted if item[0] == "recognizer"), None)
    if recognizer is not None:
        _, recognizer_x, recognizer_weight = recognizer
        in_best = any(abs(recognizer_x - x) <= cluster_radius for _, x, _ in best_cluster)
        if not in_best and recognizer_weight >= 1.05:
            weak = sum(weight for _, _, weight in best_cluster) < recognizer_weight * 1.05
            if weak or len(best_cluster) <= 2:
                best_cluster = [recognizer]

    total_weight = sum(weight for _, _, weight in best_cluster)
    if total_weight <= 0:
        value = round(sum(x for _, x, _ in best_cluster) / len(best_cluster))
        confidence = 0.5
    else:
        value = round(sum(x * weight for _, x, weight in best_cluster) / total_weight)
        values = [x for _, x, _ in best_cluster]
        spread = max(values) - min(values) if len(values) > 1 else 0
        confidence = min(
            0.99,
            (total_weight / (total_weight + 0.8)) * (1.0 / (1.0 + spread / 25.0)),
        )
    return int(value), confidence


def _image_bytes(page, locator) -> bytes | None:
    try:
        source = str(locator.get_attribute("src") or "").strip()
        if source.startswith("data:") and "," in source:
            return base64.b64decode(source.split(",", 1)[1])
        if source and not source.startswith(("data:", "blob:")):
            response = page.request.get(urljoin(page.url, source), timeout=20_000)
            if response.ok:
                return response.body()
    except Exception:  # noqa: BLE001,S110 - DOM screenshot remains the fallback
        pass
    try:
        return locator.screenshot(type="png")
    except Exception:  # noqa: BLE001 - challenge may refresh between lookup and capture
        return None


def _natural_width(locator) -> float:
    try:
        return float(locator.evaluate("el => el.naturalWidth || el.width || 0") or 0)
    except Exception:  # noqa: BLE001 - geometry falls back to decoded image width
        return 0.0


def _any_visible(page, selector: str) -> bool:
    try:
        controls = page.locator(selector)
        return any(controls.nth(index).is_visible() for index in range(controls.count()))
    except Exception:  # noqa: BLE001 - transient DOM replacement is treated as still active
        return True


def _read_piece_left(page) -> float | None:
    try:
        value = page.evaluate(
            """() => {
              const piece = document.getElementById('aliyunCaptcha-puzzle');
              const image = document.getElementById('aliyunCaptcha-img');
              if (!piece || !image) return null;
              return piece.getBoundingClientRect().x - image.getBoundingClientRect().x;
            }"""
        )
        return float(value) if value is not None else None
    except Exception:  # noqa: BLE001 - a transient captcha rerender removes the elements
        return None


def _drag_slider_to_piece_target(
    page,
    slider,
    target_piece: float,
    max_travel: float,
    drag_offset: float,
    duration: float,
) -> tuple[float, float, int]:
    box = slider.bounding_box()
    if not box:
        raise RuntimeError("Qwen slider handle has no geometry")
    travel = max(float(max_travel), 1.0)
    target = max(0.0, min(float(target_piece) + float(drag_offset) * 0.2, travel))
    start_x = box["x"] + box["width"] / 2.0
    start_y = box["y"] + box["height"] / 2.0
    mouse_limit = start_x + travel + 8.0
    duration = max(0.65, min(float(duration), 1.0))
    kick = max(8.0, min(_piece_target_to_drag(target * 0.5, travel), travel * 0.65))

    page.mouse.move(start_x - random.uniform(8, 16), start_y)
    page.wait_for_timeout(8)
    page.mouse.move(start_x, start_y)
    page.wait_for_timeout(8)
    page.mouse.down()
    page.wait_for_timeout(10)

    mouse_x = start_x
    started = time.time()
    kick_steps = max(8, int(kick / 12))
    per_step_ms = max(4, max(80, int(duration * 1_000 * 0.32)) // kick_steps)
    for index in range(1, kick_steps + 1):
        ratio = index / kick_steps
        eased = ratio * ratio * (3 - 2 * ratio)
        mouse_x = start_x + kick * eased
        page.mouse.move(mouse_x, start_y + random.uniform(-0.3, 0.3))
        page.wait_for_timeout(per_step_ms)

    gain = 1.7
    last_piece = float(_read_piece_left(page) or 0.0)
    last_mouse = mouse_x
    correction_deadline = time.time() + duration
    samples = 0
    while time.time() < correction_deadline:
        observed_piece = _read_piece_left(page)
        piece = float(last_piece if observed_piece is None else observed_piece)
        error = target - piece
        if abs(error) <= settings().qwen_slider_tolerance_px:
            page.mouse.up()
            return piece, time.time() - started, samples

        piece_delta = piece - last_piece
        mouse_delta = mouse_x - last_mouse
        if piece_delta > 0.6 and abs(mouse_delta) > 0.5:
            observed_gain = max(0.95, min(abs(mouse_delta) / piece_delta, 3.8))
            gain = 0.6 * gain + 0.4 * observed_gain
        last_piece, last_mouse = piece, mouse_x

        if abs(error) > 40:
            step = max(-22.0, min(22.0, error * gain * 0.55))
            sleep_ms = 5
        elif abs(error) > 15:
            step = max(-12.0, min(12.0, error * gain * 0.42))
            sleep_ms = 6
        elif abs(error) > 6:
            step = max(-6.0, min(6.0, error * gain * 0.35))
            sleep_ms = 6
        else:
            step = max(-2.5, min(2.5, error * gain * 0.3))
            sleep_ms = 5
        if abs(step) < 0.6:
            step = 0.7 if error > 0 else -0.7
        mouse_x = max(start_x, min(mouse_limit, mouse_x + step))
        page.mouse.move(mouse_x, start_y + random.uniform(-0.25, 0.25))
        page.wait_for_timeout(sleep_ms)
        samples += 1
        if samples > 60:
            break

    page.mouse.up()
    final = float(_read_piece_left(page) or last_piece)
    return final, time.time() - started, samples


_DRAG_PIECE_CURVE = (
    (0.00, 0.0000),
    (20 / 260, 2.96 / 260),
    (40 / 260, 8.76 / 260),
    (60 / 260, 17.40 / 260),
    (80 / 260, 28.88 / 260),
    (100 / 260, 43.19 / 260),
    (120 / 260, 60.35 / 260),
    (140 / 260, 80.35 / 260),
    (160 / 260, 103.19 / 260),
    (180 / 260, 128.88 / 260),
    (200 / 260, 157.40 / 260),
    (220 / 260, 188.76 / 260),
    (240 / 260, 222.96 / 260),
    (1.00, 1.0000),
)


def _piece_target_to_drag(target_piece: float, max_travel: float) -> float:
    maximum = max(float(max_travel), 1.0)
    fraction = max(0.0, min(float(target_piece), maximum)) / maximum
    for index in range(1, len(_DRAG_PIECE_CURVE)):
        drag_before, piece_before = _DRAG_PIECE_CURVE[index - 1]
        drag_after, piece_after = _DRAG_PIECE_CURVE[index]
        if piece_before <= fraction <= piece_after:
            offset = (fraction - piece_before) / max(piece_after - piece_before, 1e-9)
            return min(maximum, (drag_before + offset * (drag_after - drag_before)) * maximum)
    return maximum


def _image_width(image: bytes) -> int:
    from PIL import Image

    with Image.open(io.BytesIO(image)) as source:
        return int(source.width)


def _jitter(minimum_ms: int, maximum_ms: int) -> int:
    if maximum_ms <= minimum_ms:
        return minimum_ms
    return random.randint(minimum_ms, maximum_ms)
