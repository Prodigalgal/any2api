from __future__ import annotations

import io
import logging
import random
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from PIL import Image, ImageStat

from ..captcha.artifacts import record_captcha_artifact
from ..captcha.models import VisualAction
from ..captcha.policy import CaptchaAiPolicy
from ..captcha.registry import registry
from .deepseek_settings import settings

logger = logging.getLogger("any2api_automation.providers.deepseek.challenge")

_ANIMAL_MATRIX_INSTRUCTION = "drag one of the animals into the empty spot"
_ANIMAL_MATRIX_SOURCE_CENTERS = ((0.11, 0.14), (0.11, 0.40))
_ANIMAL_MATRIX_GRID_X = (0.40, 0.56, 0.72, 0.88)
_ANIMAL_MATRIX_GRID_Y = (0.14, 0.39, 0.64, 0.88)


class DeepseekHcaptchaChallenge:
    def __init__(self) -> None:
        self.last_diagnostic = "not_started"

    def solve(
        self,
        page: Any,
        *,
        completed: Callable[[], bool] | None = None,
        ai_policy: CaptchaAiPolicy | None = None,
    ) -> None:
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
            artifact = record_captcha_artifact("deepseek-hcaptcha", task.image)
            empty_targets = _animal_matrix_empty_targets(prompt, task.image)
            actions = registry.solve_visual_actions_sync(
                task.image,
                _solver_prompt(prompt),
                timeout_seconds=max(10, deadline - time.monotonic()),
                ai_policy=ai_policy,
                action_normalizer=lambda values, task_prompt=prompt, targets=empty_targets: (
                    _normalize_actions(task_prompt, values, targets)
                ),
            )
            solver_diagnostic = registry.visual_diagnostic()
            evidence = _challenge_evidence(prompt, artifact, actions)
            if not actions or any(action.type not in {"click", "drag"} for action in actions):
                self.last_diagnostic = f"solver_rejected:{evidence}:solver={solver_diagnostic}"[
                    :600
                ]
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
            submitted = _submit(frame, required=all(action.type == "click" for action in actions))
            if not submitted and any(action.type == "drag" for action in actions):
                page.wait_for_timeout(750)
                submitted = _submit(frame, required=False)
            if _wait_for_completion(
                page,
                completed,
                timeout_seconds=min(
                    config.deepseek_hcaptcha_result_wait_seconds,
                    max(1.0, deadline - time.monotonic()),
                ),
            ):
                self.last_diagnostic = (
                    f"solved:attempt={attempts}:submitted={submitted}:{evidence}:"
                    f"solver={solver_diagnostic}"
                )[:600]
                return
            after_artifact = _surface_artifact(surface, "deepseek-hcaptcha-after")
            self.last_diagnostic = (
                f"retry:attempt={attempts}:submitted={submitted}:"
                f"after={after_artifact or 'unavailable'}:{evidence}:"
                f"solver={solver_diagnostic}"
            )[:600]
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


def _solver_prompt(prompt: str) -> str:
    rule = ""
    if _ANIMAL_MATRIX_INSTRUCTION in prompt.casefold():
        rule = (
            "\nThis specific puzzle has two movable animal candidates and a species-row grid. "
            "The grid can show more than one empty-looking cell. Return exactly ONE drag: choose "
            "the movable animal whose species has an incomplete row, and drop it into the empty "
            "cell in that same species row. The other candidate is a decoy whose species row is "
            "already complete. Never move a candidate into a different species row."
        )
    return (
        "Solve this hCaptcha semantic image challenge. Instruction: "
        + prompt
        + rule
        + "\nFirst reason internally about every object, the row/column pattern, and the empty "
        "target. Use click actions for selection tasks. For drag tasks, return one drag action "
        "per object required by the instruction; return multiple drag actions only when the "
        "instruction explicitly requires multiple objects. Each drag must use "
        '{"type":"drag","from":[x1,y1],"to":[x2,y2]} '
        "from the movable object's center to its target center. Coordinates must be decimal "
        "fractions from 0.0 to 1.0 relative to the supplied task image; never return pixel "
        "coordinates or values above 1. Do not click the submit button."
    )


def _normalize_actions(
    prompt: str,
    actions: list[VisualAction],
    empty_targets: tuple[tuple[float, float], ...] = (),
) -> list[VisualAction]:
    if _ANIMAL_MATRIX_INSTRUCTION not in prompt.casefold() or len(actions) != 1:
        return actions
    action = actions[0]
    if action.type != "drag" or action.start is None or action.end is None:
        return actions
    if action.start[0] > 0.28 or action.end[0] < 0.20:
        return actions
    source = min(
        _ANIMAL_MATRIX_SOURCE_CENTERS,
        key=lambda point: abs(point[0] - action.start[0]) + abs(point[1] - action.start[1]),
    )
    targets = empty_targets or tuple(
        (x, y) for y in _ANIMAL_MATRIX_GRID_Y for x in _ANIMAL_MATRIX_GRID_X
    )
    target = min(
        targets,
        key=lambda point: abs(point[0] - action.end[0]) + abs(point[1] - action.end[1]),
    )
    return [VisualAction(type="drag", start=source, end=target)]


def _animal_matrix_empty_targets(
    prompt: str,
    image: bytes,
) -> tuple[tuple[float, float], ...]:
    if _ANIMAL_MATRIX_INSTRUCTION not in prompt.casefold():
        return ()
    try:
        import cv2
        import numpy as np

        source = cv2.imdecode(np.frombuffer(image, dtype=np.uint8), cv2.IMREAD_COLOR)
        if source is None or source.shape[0] < 160 or source.shape[1] < 240:
            return ()
        height, width = source.shape[:2]
        radius_x = max(8, round(width * 0.065))
        radius_y = max(8, round(height * 0.09))
        targets: list[tuple[float, float]] = []
        for y in _ANIMAL_MATRIX_GRID_Y:
            row: list[tuple[float, float, float]] = []
            for x in _ANIMAL_MATRIX_GRID_X:
                center_x, center_y = round(x * width), round(y * height)
                crop = source[
                    max(0, center_y - radius_y) : min(height, center_y + radius_y),
                    max(0, center_x - radius_x) : min(width, center_x + radius_x),
                ]
                gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
                laplacian = float(cv2.Laplacian(gray, cv2.CV_64F).var())
                edge_density = float((cv2.Canny(gray, 60, 140) > 0).mean())
                row.append((x, laplacian, edge_density))
            median_laplacian = max(1.0, float(np.median([value[1] for value in row])))
            median_edges = max(0.001, float(np.median([value[2] for value in row])))
            ranked = sorted(
                row,
                key=lambda value: value[1] / median_laplacian + value[2] / median_edges,
            )
            x, laplacian, edge_density = ranked[0]
            laplacian_ratio = laplacian / median_laplacian
            edge_ratio = edge_density / median_edges
            if laplacian_ratio + edge_ratio < 1.25 and (
                laplacian_ratio < 0.65 or edge_ratio < 0.55
            ):
                targets.append((x, y))
        return tuple(targets)
    except Exception:  # noqa: BLE001 - uncertain CV falls back to the full grid
        return ()


def _challenge_evidence(
    prompt: str,
    artifact: str,
    actions: list[VisualAction],
) -> str:
    safe_prompt = " ".join(prompt.split()).replace(":", ";")[:160]
    normalized = []
    for action in actions:
        if action.type == "click" and action.at is not None:
            normalized.append(f"click({action.at[0]:.3f},{action.at[1]:.3f})")
        elif action.type == "drag" and action.start is not None and action.end is not None:
            normalized.append(
                f"drag({action.start[0]:.3f},{action.start[1]:.3f};"
                f"{action.end[0]:.3f},{action.end[1]:.3f})"
            )
        else:
            normalized.append(str(action.type)[:20])
    action_text = ",".join(normalized) or "none"
    return f"prompt={safe_prompt}:actions={action_text}:artifact={artifact or 'disabled'}"


def _surface_artifact(surface: Any, label: str) -> str:
    try:
        return record_captcha_artifact(label, surface.screenshot(type="png"))
    except Exception:  # noqa: BLE001 - the provider may replace the challenge frame immediately
        return ""


def _submit(frame: Any, *, required: bool) -> bool:
    for selector in (
        "button.button-submit",
        ".button-submit",
        "button[data-button-action='submit']",
        "button:has-text('Submit')",
        "button:has-text('Check')",
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
