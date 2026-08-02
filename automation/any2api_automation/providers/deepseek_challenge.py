from __future__ import annotations

import io
import logging
import random
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from PIL import Image, ImageStat

from ..captcha.registry import registry
from .deepseek_settings import settings

logger = logging.getLogger("any2api_automation.providers.deepseek.challenge")


class DeepseekHcaptchaChallenge:
    def __init__(self) -> None:
        self.last_diagnostic = "not_started"

    def solve(self, page: Any, *, completed: Callable[[], bool] | None = None) -> None:
        config = settings()
        deadline = time.monotonic() + config.deepseek_hcaptcha_timeout_seconds
        idle_deadline = time.monotonic() + 20
        checkbox_clicked = False
        checkbox_retries = 0
        last_checkbox_click = 0.0
        attempts = 0
        while time.monotonic() < deadline:
            if completed is not None and completed():
                self.last_diagnostic = "provider_response"
                return
            frame, surface = _challenge_surface(page)
            if frame is None or surface is None:
                retry_required = _checkbox_retry_required(page)
                can_retry = (
                    retry_required
                    and checkbox_retries < 3
                    and time.monotonic() - last_checkbox_click >= 1.5
                )
                if not checkbox_clicked or can_retry:
                    checkbox_clicked = _click_checkbox(page)
                    if checkbox_clicked:
                        last_checkbox_click = time.monotonic()
                        if retry_required:
                            checkbox_retries += 1
                            self.last_diagnostic = f"checkbox_retry:attempt={checkbox_retries}"
                if checkbox_clicked:
                    idle_deadline = max(idle_deadline, time.monotonic() + 5)
                if retry_required and checkbox_retries >= 3:
                    raise RuntimeError("DeepSeek hCaptcha checkbox retry budget was exhausted")
                if time.monotonic() >= idle_deadline:
                    raise RuntimeError("DeepSeek hCaptcha did not produce a provider response")
                page.wait_for_timeout(250)
                continue
            attempts += 1
            if attempts > config.deepseek_hcaptcha_attempts:
                raise RuntimeError(
                    f"DeepSeek hCaptcha attempts were exhausted diagnostic={self.last_diagnostic}"
                )
            prompt = _prompt(frame)
            task = _ready_surface_image(page, surface)
            actions = registry.solve_visual_actions_sync(
                task.image,
                "Solve this hCaptcha semantic image challenge. Instruction: "
                + prompt
                + "\nFirst reason internally about every object, the row/column pattern, and the "
                "empty target. Use click actions for selection tasks. For drag tasks, return one "
                'ACTIONS=[{"type":"drag","from":[x1,y1],"to":[x2,y2]}] action '
                "from the movable object's center to the target center. Coordinates must be "
                "decimal fractions from 0.0 to 1.0 relative to the supplied task image; never "
                "return pixel coordinates or values above 1. Do not click the submit button.",
                timeout_seconds=max(10, deadline - time.monotonic()),
            )
            if not actions or any(action.type not in {"click", "drag"} for action in actions):
                self.last_diagnostic = "solver_rejected:" + registry.visual_diagnostic()
                _refresh(frame)
                page.wait_for_timeout(random.randint(600, 1000))
                continue
            box = surface.bounding_box()
            if not box or float(box.get("width") or 0) < 80 or float(box.get("height") or 0) < 80:
                raise RuntimeError("DeepSeek hCaptcha image geometry is unavailable")
            for action in actions:
                if action.type == "click":
                    if action.at is None:
                        raise RuntimeError("DeepSeek hCaptcha solver returned an incomplete click")
                    x, y = _point(box, task, action.at)
                    page.mouse.move(x, y, steps=random.randint(4, 9))
                    page.wait_for_timeout(random.randint(70, 180))
                    page.mouse.click(x, y, delay=random.randint(45, 110))
                    page.wait_for_timeout(random.randint(120, 280))
                    continue
                if action.start is None or action.end is None:
                    raise RuntimeError("DeepSeek hCaptcha solver returned an incomplete drag")
                _drag(
                    page,
                    _point(box, task, action.start),
                    _point(box, task, action.end),
                )
            _submit(frame, required=all(action.type == "click" for action in actions))
            if _wait_for_completion(page, completed):
                self.last_diagnostic = (
                    f"solved:attempt={attempts}:" + registry.visual_diagnostic()
                )[:600]
                return
            self.last_diagnostic = (f"retry:attempt={attempts}:" + registry.visual_diagnostic())[
                :600
            ]
        raise TimeoutError(
            "DeepSeek hCaptcha did not complete before the deadline "
            f"diagnostic={self.last_diagnostic}"
        )


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


def _checkbox_retry_required(page: Any) -> bool:
    for frame in _frames(page):
        try:
            body = " ".join(frame.locator("body").inner_text().lower().split())[:500]
            if "please try again" in body or "try again" in body:
                return True
        except Exception:  # noqa: BLE001,S112 - hCaptcha replaces frames while retrying
            continue
    return False


@dataclass(frozen=True)
class _TaskImage:
    image: bytes
    left: float
    top: float
    width: float
    height: float


def _ready_surface_image(page: Any, surface: Any) -> _TaskImage:
    deadline = time.monotonic() + 12
    while time.monotonic() < deadline:
        image = surface.screenshot(type="png")
        task = _extract_task_image(image)
        if task is not None:
            return task
        page.wait_for_timeout(250)
    raise RuntimeError("DeepSeek hCaptcha task image did not finish loading")


def _extract_task_image(image: bytes) -> _TaskImage | None:
    import numpy as np

    with Image.open(io.BytesIO(image)) as source:
        rgb = source.convert("RGB")
        width, height = rgb.size
        if width < 160 or height < 160:
            return None
        pixels = np.asarray(rgb, dtype=np.float32)
        row_detail = pixels.std(axis=(1, 2))
        start = int(height * 0.2)
        runs = _runs(row_detail[start:] >= 12, offset=start)
        if not runs:
            return None
        top, bottom = max(runs, key=lambda value: value[1] - value[0])
        if bottom - top < height * 0.25:
            return None
        column_detail = pixels[top:bottom].std(axis=(0, 2))
        columns = _runs(column_detail >= 12)
        if not columns:
            return None
        left, right = max(columns, key=lambda value: value[1] - value[0])
        if right - left < width * 0.35:
            return None
        left = max(0, left - 2)
        right = min(width, right + 2)
        top = max(0, top - 2)
        bottom = min(height, bottom + 2)
        cropped = rgb.crop((left, top, right, bottom))
        stats = ImageStat.Stat(cropped.convert("L"))
        if stats.stddev[0] < 8:
            return None
        output = io.BytesIO()
        cropped.save(output, format="PNG", optimize=True)
        return _TaskImage(
            output.getvalue(),
            left / width,
            top / height,
            (right - left) / width,
            (bottom - top) / height,
        )


def _runs(mask: Any, *, offset: int = 0) -> list[tuple[int, int]]:
    runs: list[tuple[int, int]] = []
    start: int | None = None
    for index, active in enumerate(mask):
        if bool(active) and start is None:
            start = index
        if not bool(active) and start is not None:
            runs.append((start + offset, index + offset))
            start = None
    if start is not None:
        runs.append((start + offset, len(mask) + offset))
    return runs


def _point(
    box: dict[str, float],
    task: _TaskImage,
    point: tuple[float, float],
) -> tuple[float, float]:
    return (
        float(box["x"]) + (task.left + point[0] * task.width) * float(box["width"]),
        float(box["y"]) + (task.top + point[1] * task.height) * float(box["height"]),
    )


def _drag(page: Any, start: tuple[float, float], end: tuple[float, float]) -> None:
    page.mouse.move(*start, steps=random.randint(5, 9))
    page.wait_for_timeout(random.randint(90, 180))
    page.mouse.down()
    try:
        page.wait_for_timeout(random.randint(80, 160))
        page.mouse.move(*end, steps=random.randint(18, 28))
        page.wait_for_timeout(random.randint(120, 240))
    finally:
        page.mouse.up()


def _wait_for_completion(
    page: Any,
    completed: Callable[[], bool] | None,
    *,
    timeout_seconds: float = 5.0,
) -> bool:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if completed is not None and completed():
            return True
        page.wait_for_timeout(250)
    return completed is not None and completed()


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


def _submit(frame: Any, *, required: bool) -> bool:
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
                return True
        except Exception:  # noqa: BLE001,S112 - try the next official selector
            continue
    if required:
        raise RuntimeError("DeepSeek hCaptcha submit control is unavailable")
    return False


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
