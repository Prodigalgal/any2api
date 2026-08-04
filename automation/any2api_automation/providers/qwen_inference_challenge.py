from __future__ import annotations

import asyncio
import base64
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
    _fuse_qwen_gap,
    _piece_target_to_drag,
    _qwen_gap_estimates,
)
from .qwen_settings import settings


@dataclass(frozen=True)
class QwenInferenceChallengeResult:
    attempts: int
    diagnostic: str


class QwenInferenceChallengeSolver:
    """Clears a Baxia slider in the browser context that received the punish URL."""

    async def solve(self, page: Any, punish_url: str) -> QwenInferenceChallengeResult:
        await page.goto(punish_url, wait_until="domcontentloaded", timeout=60_000)
        await page.wait_for_timeout(1_500)
        diagnostics: list[str] = []
        for attempt in range(1, max(1, settings().qwen_signup_attempts) + 1):
            slider = await _first_visible(page, SLIDER_SELECTORS, 8_000)
            if slider is None:
                if await _challenge_cleared(page):
                    return QwenInferenceChallengeResult(attempt, "challenge_cleared_without_drag")
                raise RuntimeError("Qwen inference slider handle is unavailable")

            background = await _first_visible(page, BACKGROUND_SELECTORS, 3_000)
            piece = await _first_visible(page, PIECE_SELECTORS, 3_000)
            if background is None or piece is None:
                raise RuntimeError("Qwen inference slider images are unavailable")
            background_bytes, piece_bytes = await asyncio.gather(
                _image_bytes(page, background),
                _image_bytes(page, piece),
            )
            if not background_bytes or not piece_bytes:
                raise RuntimeError("Qwen inference slider images could not be captured")

            estimates = await asyncio.to_thread(_qwen_gap_estimates, background_bytes, piece_bytes)
            if not estimates:
                raise RuntimeError("Qwen inference slider produced no deterministic estimate")
            fused, confidence = _fuse_qwen_gap(estimates)
            slider_box = await slider.bounding_box()
            background_box = await background.bounding_box()
            body = page.locator("#aliyunCaptcha-sliding-body").first
            body_box = await body.bounding_box() if await body.count() else None
            if not slider_box or not background_box:
                raise RuntimeError("Qwen inference slider geometry is unavailable")
            natural_width = await _natural_width(background)
            natural_width = natural_width or float(background_box["width"])
            display_width = float(background_box["width"])
            max_travel = max(
                1.0,
                float(body_box["width"] - slider_box["width"]) if body_box else 260.0,
            )
            target_piece = float(fused) * display_width / natural_width
            final_piece, elapsed, samples = await _drag_slider_to_piece_target(
                page,
                slider,
                target_piece,
                max_travel,
                settings().qwen_slider_drag_offset_px,
                min(settings().qwen_slider_drag_budget_ms / 1000, random.uniform(0.75, 0.95)),
            )
            diagnostic = (
                f"attempt={attempt}, fused={fused:.1f}, confidence={confidence:.2f}, "
                f"target={target_piece:.1f}, final={final_piece:.1f}, "
                f"error={final_piece - target_piece:.1f}, elapsed={elapsed:.2f}, samples={samples}"
            )
            diagnostics.append(diagnostic)
            if await _wait_until_cleared(page, 6_000):
                return QwenInferenceChallengeResult(attempt, diagnostic)
            await _refresh(page)
            await page.wait_for_timeout(random.randint(800, 1_300))
        raise RuntimeError(
            "Qwen inference slider exhausted all attempts: " + "; ".join(diagnostics)
        )


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
