"""Bounded Arena image challenge handling using the shared internal vision service."""

from __future__ import annotations

import asyncio
import hashlib
import io
import logging
import math
from dataclasses import dataclass, replace
from enum import StrEnum
from typing import Any

from PIL import Image, ImageDraw, ImageFont

from ..blocking_work import run_blocking
from ..captcha.policy import current_captcha_policy
from ..captcha.registry import registry
from .arena_settings import settings as arena_settings

logger = logging.getLogger(__name__)
_MAX_AI_CALLS = 3
_AI_TIMEOUT_SECONDS = 30
_CONTROL_TIMEOUT_MS = 4000
_POLL_SECONDS = 0.25


class ArenaChallengeOutcome(StrEnum):
    AI_UNAVAILABLE = "ai_unavailable"
    NOT_READY = "not_ready_or_missing"
    UNCHANGED = "unchanged_after_submission"
    DEADLINE = "deadline"
    INVALID = "recognition_invalid"
    CHANGED = "changed_during_recognition"
    CLOSED = "widget_closed"
    DYNAMIC = "dynamic_replacement"
    MISMATCH = "selection_mismatch"
    SUBMITTED = "submitted_awaiting_official_result"
    EXHAUSTED = "recognition_budget_exhausted"
    CANCELLED = "cancelled"
    FAILED = "operation_failed"


@dataclass(frozen=True)
class ArenaChallengeResult:
    outcome: ArenaChallengeOutcome
    ai_calls: int
    submissions: int

    @property
    def should_finish_pending_widget(self) -> bool:
        return self.outcome not in {ArenaChallengeOutcome.SUBMITTED, ArenaChallengeOutcome.CLOSED}


_GRID_STATE = """tiles => {
  const button = document.querySelector('#recaptcha-verify-button');
  const instruction = document.querySelector('.rc-imageselect-desc-wrapper');
  const payload = document.querySelector('.rc-imageselect-payload').getBoundingClientRect();
  const pending = tiles.some(tile => tile.classList.contains('rc-imageselect-dynamic-selected')
    || Array.from(tile.querySelectorAll('img')).some(image => !image.complete));
  const maxBorderOverhang = 2;
  return {
    cellBoxes: tiles.map(tile => {
      const bounds = tile.getBoundingClientRect();
      // Google's real table extends 2 CSS px beyond the screenshot on either edge.
      // Intersect only that observed overflow; larger geometry errors stay invalid.
      const clipEdges = Math.max(payload.left - bounds.left, payload.top - bounds.top,
        bounds.right - payload.right, bounds.bottom - payload.bottom) <= maxBorderOverhang + 1e-6;
      const left = clipEdges ? Math.max(bounds.left, payload.left) : bounds.left;
      const top = clipEdges ? Math.max(bounds.top, payload.top) : bounds.top;
      const right = clipEdges ? Math.min(bounds.right, payload.right) : bounds.right;
      const bottom = clipEdges ? Math.min(bounds.bottom, payload.bottom) : bounds.bottom;
      return [(left - payload.left) / payload.width, (top - payload.top) / payload.height,
        (right - left) / payload.width, (bottom - top) / payload.height];
    }),
    signature: JSON.stringify([instruction?.textContent || '', tiles.map(tile =>
      Array.from(tile.querySelectorAll('img')).map(image => image.currentSrc || image.src))]),
    selected: tiles.flatMap((tile, index) =>
      tile.classList.contains('rc-imageselect-tileselected') ? [index + 1] : []),
    ready: Boolean(button && !button.disabled && button.getAttribute('aria-disabled') !== 'true'
      && !button.classList.contains('rc-button-disabled') && !pending)
  };
}"""


@dataclass(frozen=True)
class GridSnapshot:
    image: bytes
    signature: str
    selected: frozenset[int]
    columns: int
    ready: bool
    recognition_image: bytes | None = None

    @property
    def digest(self) -> str:
        return hashlib.sha256(self.image).hexdigest()


def _active_challenge_image(image: bytes) -> bool:
    with Image.open(io.BytesIO(image)) as source:
        header = source.convert("RGB").crop((0, 0, source.width, min(100, source.height)))
        pixels = header.tobytes()
    colors = zip(pixels[0::3], pixels[1::3], pixels[2::3])
    return (
        bool(pixels)
        and sum(blue > 180 and blue > red + 60 and blue > green + 40 for red, green, blue in colors)
        / (len(pixels) // 3)
        > 0.1
    )


def _numbered_grid_image(image: bytes, cell_boxes: list[list[float]]) -> bytes:
    if len(cell_boxes) not in {9, 16}:
        raise ValueError("unsupported Arena challenge cell geometry")
    with Image.open(io.BytesIO(image)) as source:
        numbered = source.convert("RGB")
    draw = ImageDraw.Draw(numbered)
    font = ImageFont.load_default(size=14)
    for index, bounds in enumerate(cell_boxes, start=1):
        if len(bounds) != 4 or any(
            type(value) not in {int, float} or not math.isfinite(value) for value in bounds
        ):
            raise ValueError("invalid Arena challenge cell geometry")
        left, top, width, height = bounds
        if (
            min(left, top) < 0
            or min(width, height) <= 0
            or left + width > 1.000001
            or top + height > 1.000001
        ):
            raise ValueError("Arena challenge cell is outside the image")
        label = str(index)
        _, _, text_width, text_height = draw.textbbox((0, 0), label, font=font)
        if numbered.width * width < text_width + 8 or numbered.height * height < text_height + 8:
            raise ValueError("Arena challenge cell is too small to label")
        x, y = round(left * numbered.width) + 2, round(top * numbered.height) + 2
        draw.rectangle(
            (x, y, x + text_width + 6, y + text_height + 6), fill="white", outline="black"
        )
        draw.text((x + 3, y + 2), label, fill="black", font=font)
    output = io.BytesIO()
    numbered.save(output, format="PNG")
    return output.getvalue()


class ArenaRecaptchaGrid:
    def __init__(self, page: Any) -> None:
        frame = page.frame_locator('iframe[src*="recaptcha"][src*="bframe"]:visible').first
        self.payload = frame.locator(".rc-imageselect-payload").first
        self.tiles = frame.locator(".rc-imageselect-tile")
        self.submit_button = frame.locator("#recaptcha-verify-button")

    async def read(self) -> GridSnapshot | None:
        if not await self.payload.count() or not await self.payload.is_visible():
            return None
        count = await self.tiles.count()
        if count not in {9, 16}:
            raise ValueError("unsupported Arena challenge layout")
        state = await self.tiles.evaluate_all(_GRID_STATE)
        image = await self.payload.screenshot(type="png", timeout=_CONTROL_TIMEOUT_MS)
        ready = state["ready"] and _active_challenge_image(image)
        return GridSnapshot(
            image=image,
            signature=state["signature"],
            selected=frozenset(state["selected"]),
            columns=3 if count == 9 else 4,
            ready=ready,
            recognition_image=_numbered_grid_image(image, state["cellBoxes"]) if ready else None,
        )

    async def toggle(self, cell: int) -> None:
        await self.tiles.nth(cell - 1).click(timeout=_CONTROL_TIMEOUT_MS)

    async def submit(self) -> None:
        await self.submit_button.press("Enter", timeout=_CONTROL_TIMEOUT_MS)


async def _wait_ready(
    surface: ArenaRecaptchaGrid, deadline: float, *, previous: GridSnapshot | None = None
) -> GridSnapshot | None:
    loop = asyncio.get_running_loop()
    wait_deadline = min(deadline, loop.time() + 5)
    while loop.time() < wait_deadline:
        current = await surface.read()
        if current is None:
            if previous is not None:
                return None
        elif current.ready and (
            previous is None
            or current.signature != previous.signature
            or current.digest != previous.digest
        ):
            return current
        await asyncio.sleep(_POLL_SECONDS)
    return None


async def solve_arena_image_challenge(page: Any, *, deadline: float) -> ArenaChallengeResult:
    policy = current_captcha_policy()
    candidates = policy.internal_models or (
        policy.internal_model or arena_settings().arena_captcha_ai_internal_model,
    )
    models = tuple(
        model for model in candidates if "/" in model and model.split("/", 1)[0].lower() != "arena"
    )
    policy = replace(
        policy,
        mode="internal",
        internal_model=models[0] if models else "",
        internal_models=models,
    )
    if not policy.enabled or not models or not registry.visual_ai_available(policy):
        logger.info("arena_image_challenge outcome=ai_unavailable ai_calls=0 submissions=0")
        return ArenaChallengeResult(ArenaChallengeOutcome.AI_UNAVAILABLE, 0, 0)
    calls = submissions = 0
    outcome = ArenaChallengeOutcome.NOT_READY
    try:
        async with asyncio.timeout_at(deadline):
            surface = ArenaRecaptchaGrid(page)
            current = await _wait_ready(surface, deadline)
            recognized: set[str] = set()
            while current is not None and calls < _MAX_AI_CALLS:
                if current.digest in recognized:
                    outcome = ArenaChallengeOutcome.UNCHANGED
                    break
                remaining = deadline - asyncio.get_running_loop().time()
                if remaining < 2:
                    outcome = ArenaChallengeOutcome.DEADLINE
                    break
                calls += 1
                recognition_budget = min(_AI_TIMEOUT_SECONDS, remaining - 1)
                async with asyncio.timeout(recognition_budget):
                    target = await run_blocking(
                        registry.solve_visual_grid_sync,
                        current.recognition_image or current.image,
                        current.columns,
                        timeout_seconds=recognition_budget,
                        ai_policy=policy,
                    )
                if target is None:
                    outcome = ArenaChallengeOutcome.INVALID
                    fresh = await surface.read()
                    if fresh is None or not fresh.ready or fresh.digest != current.digest:
                        break
                    await asyncio.sleep(_POLL_SECONDS)
                    continue
                recognized.add(current.digest)
                fresh = await surface.read()
                if fresh is None:
                    outcome = ArenaChallengeOutcome.CLOSED
                    break
                if not fresh.ready:
                    outcome = ArenaChallengeOutcome.CHANGED
                    current = await _wait_ready(surface, deadline)
                    continue
                if fresh.digest != current.digest or fresh.signature != current.signature:
                    outcome = ArenaChallengeOutcome.CHANGED
                    current = fresh
                    continue
                changed = False
                for cell in sorted(frozenset(target) ^ current.selected):
                    fresh = await surface.read()
                    if fresh is None or not fresh.ready:
                        changed = True
                        break
                    if fresh.signature != current.signature:
                        changed = True
                        break
                    await surface.toggle(cell)
                fresh = await surface.read()
                if fresh is None:
                    outcome = ArenaChallengeOutcome.CLOSED
                    break
                if changed or not fresh.ready or fresh.signature != current.signature:
                    outcome = ArenaChallengeOutcome.DYNAMIC
                    current = await _wait_ready(surface, deadline)
                    continue
                if fresh.selected != frozenset(target):
                    outcome = ArenaChallengeOutcome.MISMATCH
                    break
                await surface.submit()
                submissions += 1
                outcome = ArenaChallengeOutcome.SUBMITTED
                current = await _wait_ready(surface, deadline, previous=fresh)
            else:
                if calls >= _MAX_AI_CALLS and current is not None:
                    outcome = ArenaChallengeOutcome.EXHAUSTED
    except asyncio.CancelledError:
        outcome = ArenaChallengeOutcome.CANCELLED
        raise
    except TimeoutError:
        outcome = ArenaChallengeOutcome.DEADLINE
    except Exception as error:  # noqa: BLE001 - metadata only at this optional challenge boundary
        outcome = ArenaChallengeOutcome.FAILED
        logger.warning("arena_image_challenge error_type=%s", type(error).__name__)
    finally:
        logger.info(
            "arena_image_challenge outcome=%s ai_calls=%s submissions=%s",
            outcome,
            calls,
            submissions,
        )
    return ArenaChallengeResult(outcome, calls, submissions)
