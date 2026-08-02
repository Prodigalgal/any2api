from __future__ import annotations

import logging
import random
import time
from typing import Any

from ..captcha.registry import registry
from .deepseek_settings import settings

logger = logging.getLogger("any2api_automation.providers.deepseek.challenge")


class DeepseekHcaptchaChallenge:
    def __init__(self) -> None:
        self.last_diagnostic = "not_started"

    def solve(self, page: Any) -> None:
        config = settings()
        deadline = time.monotonic() + config.deepseek_hcaptcha_timeout_seconds
        idle_deadline = time.monotonic() + 8
        checkbox_clicked = False
        attempts = 0
        while time.monotonic() < deadline:
            frame, surface = _challenge_surface(page)
            if frame is None or surface is None:
                if not checkbox_clicked:
                    checkbox_clicked = _click_checkbox(page)
                if checkbox_clicked:
                    idle_deadline = max(idle_deadline, time.monotonic() + 5)
                if time.monotonic() >= idle_deadline:
                    self.last_diagnostic = "passive_or_no_challenge"
                    return
                page.wait_for_timeout(250)
                continue
            attempts += 1
            if attempts > config.deepseek_hcaptcha_attempts:
                raise RuntimeError("DeepSeek hCaptcha attempts were exhausted")
            prompt = _prompt(frame)
            image = surface.screenshot(type="png")
            actions = registry.solve_visual_actions_sync(
                image,
                "Solve this hCaptcha image challenge. Instruction: "
                + prompt
                + '\nReturn ACTIONS=[{"type":"click","at":[x,y]}]. '
                "Coordinates must be normalized to the supplied challenge image. "
                "Return one click per matching target and no submit-button click.",
                timeout_seconds=max(10, deadline - time.monotonic()),
            )
            if not actions or any(action.type != "click" for action in actions):
                self.last_diagnostic = "solver_rejected:" + registry.visual_diagnostic()
                _refresh(frame)
                page.wait_for_timeout(random.randint(600, 1000))
                continue
            box = surface.bounding_box()
            if not box or float(box.get("width") or 0) < 80 or float(box.get("height") or 0) < 80:
                raise RuntimeError("DeepSeek hCaptcha image geometry is unavailable")
            for action in actions:
                if action.at is None:
                    raise RuntimeError("DeepSeek hCaptcha solver returned an incomplete click")
                x = float(box["x"]) + action.at[0] * float(box["width"])
                y = float(box["y"]) + action.at[1] * float(box["height"])
                page.mouse.move(x, y, steps=random.randint(4, 9))
                page.wait_for_timeout(random.randint(70, 180))
                page.mouse.click(x, y, delay=random.randint(45, 110))
                page.wait_for_timeout(random.randint(120, 280))
            _submit(frame)
            page.wait_for_timeout(random.randint(1200, 1800))
            if _challenge_surface(page)[0] is None:
                self.last_diagnostic = (
                    f"solved:attempt={attempts}:" + registry.visual_diagnostic()
                )[:600]
                return
            self.last_diagnostic = (f"retry:attempt={attempts}:" + registry.visual_diagnostic())[
                :600
            ]
        raise TimeoutError("DeepSeek hCaptcha did not complete before the deadline")


def _frames(page: Any) -> list[Any]:
    return [
        frame
        for frame in page.frames
        if frame is not page.main_frame and "hcaptcha.com" in str(frame.url).lower()
    ]


def _challenge_surface(page: Any) -> tuple[Any | None, Any | None]:
    selectors = (
        ".task-grid",
        ".challenge-view .task-image",
        ".challenge-view",
        ".task-image",
        ".challenge-container",
    )
    for frame in _frames(page):
        for selector in selectors:
            locator = frame.locator(selector).first
            try:
                if locator.count() and locator.is_visible():
                    box = locator.bounding_box()
                    if box and float(box.get("width") or 0) >= 160:
                        return frame, locator
            except Exception:  # noqa: BLE001,S112 - hCaptcha replaces frames during polling
                continue
    return None, None


def _click_checkbox(page: Any) -> bool:
    for frame in _frames(page):
        for selector in ("#checkbox", "[role='checkbox']", ".checkbox"):
            locator = frame.locator(selector).first
            try:
                if locator.count() and locator.is_visible():
                    locator.click(timeout=5_000)
                    return True
            except Exception:  # noqa: BLE001,S112 - another frame may own the checkbox
                continue
    return False


def _prompt(frame: Any) -> str:
    for selector in (".prompt-text", ".challenge-header", "[class*='prompt']"):
        locator = frame.locator(selector).first
        try:
            if locator.count() and locator.is_visible():
                value = " ".join(locator.inner_text().split())
                if value:
                    return value[:500]
        except Exception:  # noqa: BLE001,S112 - optional prompt selector
            continue
    return "Select every matching target described by the challenge"


def _submit(frame: Any) -> None:
    for selector in (
        "button.button-submit",
        ".button-submit",
        "button[data-button-action='submit']",
        "button:has-text('Verify')",
        "button:has-text('Next')",
    ):
        locator = frame.locator(selector).first
        try:
            if locator.count() and locator.is_visible():
                locator.click(timeout=5_000)
                return
        except Exception:  # noqa: BLE001,S112 - try the next official selector
            continue
    raise RuntimeError("DeepSeek hCaptcha submit control is unavailable")


def _refresh(frame: Any) -> None:
    for selector in (
        "button[data-button-action='refresh']",
        ".refresh button",
        ".button-refresh",
    ):
        locator = frame.locator(selector).first
        try:
            if locator.count() and locator.is_visible():
                locator.click(timeout=5_000)
                return
        except Exception:  # noqa: BLE001,S112 - refresh is best effort
            continue
