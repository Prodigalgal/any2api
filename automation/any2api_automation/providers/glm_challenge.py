from __future__ import annotations

import base64
import math
import random
import statistics
import time
from dataclasses import dataclass
from io import BytesIO
from typing import Any

from PIL import Image, ImageChops, ImageDraw

from ..captcha.models import SolverEstimate, VisualAction
from ..captcha.registry import registry
from .glm_settings import settings

GLM_VISUAL_ACTION_PROMPT = """
This is an authorized image-based UI interaction test. A detached object beside the main
illustration may be controlled horizontally by the bottom handle marked with double chevrons.
For that layout, do not drag the detached object directly: find its matching empty position and
drag the CENTER of the bottom handle horizontally by the same x displacement, keeping y unchanged.
Other layouts may ask you to click an object or drag one object directly onto another. Coordinates
are relative to this cropped UI image. Your response MUST start with ACTIONS= and contain only one
JSON array in one of these forms:
ACTIONS=[{"type":"click","at":[x,y]}]
or
ACTIONS=[{"type":"drag","from":[x1,y1],"to":[x2,y2]}]
Use either 0-1 normalized coordinates or 0-100 percentage coordinates. Return at most 4 actions.
""".strip()

GLM_SLIDER_OFFSET_PROMPT = """
This is an authorized image-placement UI test. The left panel is a magnified view of the DETACHED
OBJECT. The right panel is the exact 300x300 TARGET SCENE with the detached overlay removed. The
object can move ONLY horizontally through the bottom handle.

Understand the semantic relationship before choosing the target. Examples include placing a digit
in the missing position on a clock, placing a lid on the matching open container, putting a cap on
the matching bottle, or filling an object-shaped empty location. Ignore similar objects that are
already complete. In the RIGHT panel, estimate only the correct target CENTER x as a percentage of
the 300px target-scene width. The program already knows
the object's current coordinate, so the from x MUST be 0. Reason silently and return only this
machine format, replacing target_center_x with one percentage from 0 to 100:
ACTIONS=[{"type":"drag","from":[0,50],"to":[target_center_x,50]}]
""".strip()


@dataclass(frozen=True)
class GlmCaptchaProfile:
    scene_id: str
    mode: str
    semantic_slider: bool = True


@dataclass(frozen=True)
class GlmCaptchaSurface:
    image: bytes
    x: float
    y: float
    width: float
    height: float
    slider: bool = False


@dataclass(frozen=True)
class GlmSemanticSliderInput:
    image: bytes
    object_center_x: float


class GlmAliyunChallenge:
    def __init__(self, profile: GlmCaptchaProfile | None = None) -> None:
        config = settings()
        self.profile = profile or GlmCaptchaProfile(
            scene_id=config.glm_chat_captcha_scene_id,
            mode="popup",
        )
        if self.profile.mode not in {"embed", "popup"}:
            raise ValueError("GLM captcha mode must be embed or popup")
        self.last_diagnostic = "unavailable"

    @classmethod
    def for_authentication(cls) -> GlmAliyunChallenge:
        config = settings()
        return cls(
            GlmCaptchaProfile(
                scene_id=config.glm_auth_captcha_scene_id,
                mode="embed",
            )
        )

    @classmethod
    def for_chat(cls) -> GlmAliyunChallenge:
        return cls()

    def solve(self, page: Any, *, timeout_seconds: int | None = None) -> str:
        config = settings()
        self._install(page)
        deadline = time.monotonic() + (timeout_seconds or config.glm_captcha_timeout_seconds)
        page.wait_for_timeout(1_500)
        if self._start_if_required(page):
            page.wait_for_timeout(1_800)
        for attempt in range(1, config.glm_captcha_attempts + 1):
            state = self._state(page)
            ticket = self._accepted_ticket(state)
            if ticket:
                self.last_diagnostic = "mode=traceless, attempts=0, ticket=accepted"
                return ticket
            if state.get("status") in {"error", "closed"}:
                raise RuntimeError("GLM captcha service did not produce a usable challenge")
            for round_number in range(1, config.glm_captcha_rounds_per_attempt + 1):
                remaining = deadline - time.monotonic()
                if remaining <= 1:
                    break
                state = self._state(page)
                ticket = self._accepted_ticket(state)
                if ticket:
                    self.last_diagnostic = (
                        f"mode=visual, attempt={attempt}, round={round_number - 1}, ticket=accepted"
                    )
                    return ticket
                if state.get("status") in {"error", "closed"}:
                    raise RuntimeError("GLM captcha service did not produce a usable challenge")
                if state.get("status") == "failed":
                    break
                if not self._wait_for_visual_surface(page, min(remaining, 12)):
                    self.last_diagnostic = (
                        f"mode=visual, attempt={attempt}, round={round_number}, surface=not_ready"
                    )
                    continue
                surface = self._capture_surface(page)
                actions = self._solve_actions(page, surface, remaining)
                if not actions:
                    self.last_diagnostic = (
                        f"mode=visual, attempt={attempt}, round={round_number}, "
                        f"ai={registry.visual_diagnostic()}"
                    )
                    break
                self._execute(page, actions, surface)
                self.last_diagnostic = (
                    f"mode=visual, attempt={attempt}, round={round_number}, actions="
                    + ",".join(action.type for action in actions)
                )
                for _ in range(12):
                    page.wait_for_timeout(200)
                    state = self._state(page)
                    ticket = self._accepted_ticket(state)
                    if ticket:
                        self.last_diagnostic = (
                            f"mode=visual, attempt={attempt}, round={round_number}, ticket=accepted"
                        )
                        return ticket
                    if state.get("status") in {"failed", "error", "closed"}:
                        break
                if state.get("status") in {"error", "closed"}:
                    raise RuntimeError("GLM captcha service did not produce a usable challenge")
                if state.get("status") == "failed":
                    break
            if time.monotonic() < deadline and attempt < config.glm_captcha_attempts:
                self._refresh(page)
        raise RuntimeError(
            "GLM Aliyun captcha did not reach the official success callback "
            f"({self.last_diagnostic})"
        )

    def _accepted_ticket(self, state: dict[str, Any]) -> str:
        if state.get("status") != "success":
            return ""
        ticket = str(state.get("ticket") or "").strip()
        if len(ticket) < 40:
            raise RuntimeError("GLM captcha returned an incomplete verification ticket")
        return ticket

    def _install(self, page: Any) -> None:
        config = settings()
        page.evaluate(
            _INSTALL_CAPTCHA,
            {
                "scriptUrl": config.glm_captcha_script_url,
                "region": config.glm_captcha_region,
                "prefix": config.glm_captcha_prefix,
                "sceneId": self.profile.scene_id,
                "mode": self.profile.mode,
            },
        )
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            state = self._state(page)
            if state.get("status") in {"ready", "running", "success"}:
                return
            if state.get("status") == "error":
                raise RuntimeError("GLM Aliyun captcha initialization failed")
            page.wait_for_timeout(200)
        state = self._state(page)
        status = str(state.get("status") or "unknown")[:40]
        error = " ".join(str(state.get("error") or "none").split())[:160]
        raise RuntimeError(
            f"GLM Aliyun captcha initialization timed out status={status} error={error}"
        )

    def _state(self, page: Any) -> dict[str, Any]:
        value = page.evaluate(
            "() => ({...(window.__any2apiGlmCaptchaState || {status: 'missing'})})"
        )
        return value if isinstance(value, dict) else {"status": "missing"}

    def _refresh(self, page: Any) -> None:
        page.evaluate(_REFRESH_CAPTCHA)
        page.wait_for_timeout(800 + random.randint(0, 500))
        if self._start_if_required(page):
            page.wait_for_timeout(1_800)

    def _start_if_required(self, page: Any) -> bool:
        if self._visual_surface_ready(page):
            return False
        try:
            for selector in (
                "#aliyunCaptcha-float-wrapper",
                "#any2api-glm-captcha-element",
            ):
                starter = page.locator(selector).get_by_text("Click to verify", exact=False).first
                if starter.count() and starter.is_visible():
                    starter.click()
                    return True
        except Exception:  # noqa: BLE001,S110 - visual solving remains the fallback
            pass
        return False

    def _wait_for_visual_surface(self, page: Any, timeout_seconds: float) -> bool:
        deadline = time.monotonic() + max(0.5, timeout_seconds)
        start_attempted = False
        while time.monotonic() < deadline:
            state = self._state(page)
            if self._accepted_ticket(state):
                return True
            if state.get("status") in {"failed", "error", "closed"}:
                return False
            if self._visual_surface_ready(page):
                return True
            if not start_attempted:
                start_attempted = self._start_if_required(page)
            page.wait_for_timeout(200)
        return False

    def _visual_surface_ready(self, page: Any) -> bool:
        try:
            return bool(page.evaluate(_CAPTCHA_VISUAL_READY))
        except Exception:  # noqa: BLE001 - a transient DOM replacement is not fatal
            return False

    def _capture_surface(self, page: Any) -> GlmCaptchaSurface:
        viewport = page.evaluate("() => ({width: innerWidth, height: innerHeight})")
        viewport_width = float(viewport.get("width") or 0)
        viewport_height = float(viewport.get("height") or 0)
        if viewport_width < 200 or viewport_height < 200:
            raise RuntimeError("GLM captcha viewport geometry is unavailable")
        try:
            captcha_image = page.locator("#aliyunCaptcha-img").first
            image_box = captcha_image.bounding_box() if captcha_image.is_visible() else None
            if image_box and float(image_box.get("width") or 0) >= 240:
                x = float(image_box["x"])
                y = float(image_box["y"])
                width = float(image_box["width"])
                height = float(image_box["height"])
                image = page.screenshot(
                    type="png",
                    clip={"x": x, "y": y, "width": width, "height": height},
                )
                return GlmCaptchaSurface(
                    image=image,
                    x=x,
                    y=y,
                    width=width,
                    height=height,
                    slider=True,
                )
        except Exception:  # noqa: BLE001,S110 - semantic direct-drag fallback follows
            pass
        rect = page.evaluate(_CAPTCHA_SURFACE_RECT)
        x = max(0.0, float((rect or {}).get("x") or 0))
        y = max(0.0, float((rect or {}).get("y") or 0))
        width = min(viewport_width - x, float((rect or {}).get("width") or viewport_width))
        height = min(viewport_height - y, float((rect or {}).get("height") or viewport_height))
        if width < 240 or height < 200:
            x, y, width, height = 0.0, 0.0, viewport_width, viewport_height
        image = page.screenshot(
            type="png",
            clip={"x": x, "y": y, "width": width, "height": height},
        )
        return GlmCaptchaSurface(image=image, x=x, y=y, width=width, height=height)

    def _solve_actions(
        self,
        page: Any,
        surface: GlmCaptchaSurface,
        timeout_seconds: float,
    ) -> list[VisualAction]:
        if not self.profile.semantic_slider:
            local_action, _ = self._local_slider_action(page, surface)
            if local_action is not None:
                return [local_action]
        if surface.slider:
            semantic = self._semantic_slider_input(page, surface.image)
            image = semantic.image
            prompt = GLM_SLIDER_OFFSET_PROMPT
        else:
            image = surface.image
            prompt = GLM_VISUAL_ACTION_PROMPT
        actions = registry.solve_visual_actions_sync(
            image,
            prompt,
            timeout_seconds=timeout_seconds,
        )
        if surface.slider and (
            len(actions) != 1
            or actions[0].type != "drag"
            or actions[0].start is None
            or actions[0].end is None
        ):
            return []
        if surface.slider:
            target_x = actions[0].end[0]
            if target_x <= semantic.object_center_x + 0.025:
                return []
            return [
                VisualAction(
                    type="drag",
                    start=(semantic.object_center_x, 0.5),
                    end=(target_x, 0.5),
                )
            ]
        return actions

    def _local_slider_action(
        self,
        page: Any,
        surface: GlmCaptchaSurface,
    ) -> tuple[VisualAction | None, list[float]]:
        if not surface.slider:
            return None, []
        try:
            background, piece = self._slider_images(page)
            estimates = registry.solve_slider_sync(background, piece)
        except (TypeError, ValueError):
            return None, []
        return self._slider_action_from_estimates(estimates, surface.width)

    def _semantic_slider_input(
        self,
        page: Any,
        rendered_scene: bytes,
    ) -> GlmSemanticSliderInput:
        object_center_x = self._slider_object_center_x(page)
        try:
            background, piece = self._slider_images(page)
            image = self._compose_semantic_slider_input(rendered_scene, piece, background)
        except (TypeError, ValueError):
            buffer = BytesIO()
            Image.new("RGBA", (1, 1), (0, 0, 0, 0)).save(buffer, format="PNG")
            image = self._compose_semantic_slider_input(rendered_scene, buffer.getvalue())
        return GlmSemanticSliderInput(image, object_center_x)

    def _slider_object_center_x(self, page: Any) -> float:
        try:
            value = float(page.evaluate(_CAPTCHA_SLIDER_OBJECT_CENTER))
            if 0 <= value <= 0.35:
                return value
        except Exception:  # noqa: BLE001,S110 - DOM may be replaced during refresh
            pass
        return 0.05

    def _slider_images(self, page: Any) -> tuple[bytes, bytes]:
        sources = page.evaluate(_CAPTCHA_IMAGE_SOURCES)
        if not isinstance(sources, dict):
            raise TypeError("GLM captcha image sources are unavailable")
        return (
            self._data_image(str(sources.get("background") or "")),
            self._data_image(str(sources.get("piece") or "")),
        )

    def _compose_semantic_slider_input(
        self,
        rendered_scene: bytes,
        piece: bytes,
        background: bytes | None = None,
    ) -> bytes:
        with (
            Image.open(BytesIO(rendered_scene)) as scene_source,
            Image.open(BytesIO(piece)) as piece_source,
        ):
            scene = scene_source.convert("RGBA")
            detached = piece_source.convert("RGBA")
            object_box = self._foreground_box(detached)
            if object_box is None and background:
                detached, object_box = self._foreground_from_scene_difference(
                    scene,
                    background,
                )
            if object_box is None:
                edge_width = max(64, round(scene.width * 0.35))
                detached = scene.crop((0, 0, edge_width, scene.height)).resize(
                    (260, 280),
                    Image.Resampling.LANCZOS,
                )
            else:
                detached = detached.crop(object_box)
                scale = min(220 / detached.width, 220 / detached.height)
                detached = detached.resize(
                    (
                        max(1, round(detached.width * scale)),
                        max(1, round(detached.height * scale)),
                    ),
                    Image.Resampling.LANCZOS,
                )
            canvas = Image.new("RGB", (640, 340), "white")
            draw = ImageDraw.Draw(canvas)
            draw.text((16, 10), "DETACHED OBJECT", fill="black")
            draw.text((326, 10), "TARGET SCENE (300px)", fill="black")
            object_x = 160 - detached.width // 2
            object_y = 180 - detached.height // 2
            canvas.paste(detached.convert("RGB"), (object_x, object_y), detached.getchannel("A"))
            target = scene
            if background:
                with Image.open(BytesIO(background)) as background_source:
                    target = background_source.convert("RGBA")
            target = target.resize((300, 300), Image.Resampling.LANCZOS).convert("RGB")
            canvas.paste(target, (326, 32))
            draw.rectangle((325, 31, 626, 332), outline="black", width=2)
            output = BytesIO()
            canvas.save(output, format="PNG")
            return output.getvalue()

    def _foreground_from_scene_difference(
        self,
        rendered_scene: Image.Image,
        background: bytes,
    ) -> tuple[Image.Image, tuple[int, int, int, int] | None]:
        with Image.open(BytesIO(background)) as background_source:
            baseline = background_source.convert("RGB").resize(
                rendered_scene.size,
                Image.Resampling.LANCZOS,
            )
        rendered = rendered_scene.convert("RGB")
        difference = ImageChops.difference(rendered, baseline).convert("L")
        mask = difference.point(lambda value: 255 if value >= 24 else 0)
        left_width = max(1, round(rendered.width * 0.35))
        left_mask = mask.crop((0, 0, left_width, rendered.height))
        box = left_mask.getbbox()
        if box is None:
            return rendered_scene, None
        padded = (
            max(0, box[0] - 3),
            max(0, box[1] - 3),
            min(left_width, box[2] + 3),
            min(rendered.height, box[3] + 3),
        )
        alpha = Image.new("L", rendered.size, 0)
        alpha.paste(mask.crop(padded), padded)
        detached = rendered_scene.copy().convert("RGBA")
        detached.putalpha(alpha)
        return detached, padded

    def _foreground_box(self, image: Image.Image) -> tuple[int, int, int, int] | None:
        alpha = image.getchannel("A")
        if alpha.getextrema()[0] < 255:
            box = alpha.getbbox()
            if box is not None:
                return box
        background = Image.new("RGB", image.size, image.convert("RGB").getpixel((0, 0)))
        difference = ImageChops.difference(image.convert("RGB"), background)
        return difference.getbbox()

    def _slider_action_from_estimates(
        self,
        estimates: list[SolverEstimate],
        width: float,
    ) -> tuple[VisualAction | None, list[float]]:
        candidates = sorted(
            {
                round(float(estimate.value), 2)
                for estimate in estimates
                if isinstance(estimate.value, int | float)
                and 4 < float(estimate.value) < width - 20
            }
        )
        if len(candidates) >= 2 and candidates[-1] - candidates[0] <= 12:
            offset = statistics.median(candidates)
            return VisualAction(
                type="drag",
                start=(0, 0.5),
                end=(offset / width, 0.5),
            ), candidates
        return None, candidates

    def _data_image(self, value: str) -> bytes:
        if not value.startswith("data:image/") or "," not in value:
            raise ValueError("GLM captcha image source is unavailable")
        encoded = value.split(",", 1)[1]
        if len(encoded) > 2_000_000:
            raise ValueError("GLM captcha image source is too large")
        result = base64.b64decode(encoded, validate=True)
        if not result:
            raise ValueError("GLM captcha image source is empty")
        return result

    def _execute(
        self,
        page: Any,
        actions: list[VisualAction],
        surface: GlmCaptchaSurface | None = None,
    ) -> None:
        if surface is None:
            viewport = page.evaluate("() => ({width: innerWidth, height: innerHeight})")
            surface = GlmCaptchaSurface(
                image=b"",
                x=0,
                y=0,
                width=float(viewport.get("width") or 0),
                height=float(viewport.get("height") or 0),
            )
        if surface.width < 200 or surface.height < 200:
            raise RuntimeError("GLM captcha surface geometry is unavailable")
        slider_box = self._slider_box(page)
        for action in actions:
            if action.type == "click" and action.at is not None:
                x, y = self._screen_point(action.at, surface)
                page.mouse.move(x, y, steps=random.randint(4, 8))
                page.wait_for_timeout(random.randint(80, 220))
                page.mouse.click(x, y, delay=random.randint(45, 120))
            elif action.type == "drag" and action.start is not None and action.end is not None:
                if slider_box is not None:
                    start, end = self._slider_drag_points(action, surface, slider_box)
                else:
                    start = self._screen_point(action.start, surface)
                    end = self._screen_point(action.end, surface)
                self._drag(page, start, end)
            else:
                raise RuntimeError("GLM visual solver returned an incomplete action")
            page.wait_for_timeout(random.randint(250, 600))

    def _slider_box(self, page: Any) -> dict[str, float] | None:
        try:
            slider = page.locator("#aliyunCaptcha-sliding-slider").first
            if slider.count() and slider.is_visible():
                box = slider.bounding_box()
                if box and float(box.get("width") or 0) >= 10:
                    return {key: float(box[key]) for key in ("x", "y", "width", "height")}
        except Exception:  # noqa: BLE001,S110 - direct-drag challenges have no slider
            pass
        return None

    def _slider_drag_points(
        self,
        action: VisualAction,
        surface: GlmCaptchaSurface,
        slider_box: dict[str, float],
    ) -> tuple[tuple[float, float], tuple[float, float]]:
        if action.start is None or action.end is None:
            raise RuntimeError("GLM slider action is incomplete")
        start = (
            slider_box["x"] + slider_box["width"] / 2,
            slider_box["y"] + slider_box["height"] / 2,
        )
        delta_x = (action.end[0] - action.start[0]) * surface.width
        end = (
            max(start[0] + 4, min(surface.x + surface.width - 4, start[0] + delta_x)),
            start[1],
        )
        return start, end

    def _screen_point(
        self,
        point: tuple[float, float],
        surface: GlmCaptchaSurface,
    ) -> tuple[float, float]:
        return (
            surface.x + max(2.0, min(surface.width - 2, point[0] * surface.width)),
            surface.y + max(2.0, min(surface.height - 2, point[1] * surface.height)),
        )

    def _drag(
        self,
        page: Any,
        start: tuple[float, float],
        end: tuple[float, float],
    ) -> None:
        page.mouse.move(*start, steps=random.randint(4, 8))
        page.wait_for_timeout(random.randint(90, 220))
        page.mouse.down()
        distance = math.dist(start, end)
        steps = max(14, min(38, round(distance / 12)))
        bend = random.uniform(-0.08, 0.08) * max(30.0, distance)
        for index in range(1, steps + 1):
            progress = index / steps
            eased = 3 * progress**2 - 2 * progress**3
            x = start[0] + (end[0] - start[0]) * eased
            y = start[1] + (end[1] - start[1]) * eased
            y += math.sin(math.pi * progress) * bend
            page.mouse.move(x, y)
            page.wait_for_timeout(random.randint(8, 24))
        page.wait_for_timeout(random.randint(80, 180))
        page.mouse.up()


_INSTALL_CAPTCHA = """
async config => {
  const state = window.__any2apiGlmCaptchaState = {
    status: 'loading', ticket: '', error: ''
  };
  const hostId = 'any2api-glm-captcha-element';
  const buttonId = 'any2api-glm-captcha-trigger';
  let host = document.getElementById(hostId);
  if (!host) {
    host = document.createElement('div');
    host.id = hostId;
    host.style.cssText = config.mode === 'embed'
      ? 'position:fixed;inset:0;display:flex;align-items:center;justify-content:center;background:rgba(255,255,255,.96);z-index:2147483646;'
      : 'position:absolute;left:-99999px;top:-99999px;width:0;height:0;overflow:hidden;';
    document.body.appendChild(host);
  } else {
    host.style.cssText = config.mode === 'embed'
      ? 'position:fixed;inset:0;display:flex;align-items:center;justify-content:center;background:rgba(255,255,255,.96);z-index:2147483646;'
      : 'position:absolute;left:-99999px;top:-99999px;width:0;height:0;overflow:hidden;';
  }
  let button = document.getElementById(buttonId);
  if (!button) {
    button = document.createElement('button');
    button.id = buttonId;
    button.type = 'button';
    button.style.cssText = 'position:absolute;left:-99999px;top:-99999px;width:1px;height:1px;opacity:0;';
    document.body.appendChild(button);
  }
  window.AliyunCaptchaConfig = {region: config.region, prefix: config.prefix};
  if (!window.initAliyunCaptcha) {
    await new Promise((resolve, reject) => {
      const existing = document.querySelector(`script[src="${config.scriptUrl}"]`);
      if (existing) {
        existing.addEventListener('load', resolve, {once: true});
        existing.addEventListener('error', reject, {once: true});
        return;
      }
      const script = document.createElement('script');
      script.src = config.scriptUrl;
      script.onload = resolve;
      script.onerror = reject;
      document.head.appendChild(script);
    });
  }
  if (!window.initAliyunCaptcha) {
    state.status = 'error';
    state.error = 'initAliyunCaptcha missing';
    return;
  }
  window.initAliyunCaptcha({
    SceneId: config.sceneId,
    mode: config.mode,
    element: `#${hostId}`,
    button: `#${buttonId}`,
    slideStyle: {width: 320, height: 40},
    language: 'en',
    timeout: 10000,
    delayBeforeSuccess: false,
    success: value => {
      state.ticket = typeof value === 'string' ? value : JSON.stringify(value);
      state.status = 'success';
      if (config.mode === 'embed') host.style.display = 'none';
    },
    fail: value => {
      state.status = 'failed';
      state.error = typeof value === 'string' ? value : 'challenge failed';
    },
    onError: value => {
      state.status = 'error';
      state.error = typeof value === 'string' ? value : 'captcha service error';
    },
    onClose: () => { state.status = 'closed'; },
    getInstance: instance => {
      window.__any2apiGlmCaptchaInstance = instance;
      state.status = 'ready';
      button.click();
      if (state.status === 'ready') state.status = 'running';
    }
  });
}
"""

_REFRESH_CAPTCHA = """
() => {
  const state = window.__any2apiGlmCaptchaState;
  if (!state || state.status === 'success') return;
  state.status = 'running';
  state.error = '';
  try { window.__any2apiGlmCaptchaInstance?.refresh?.(); } catch (_) {}
  document.getElementById('any2api-glm-captcha-trigger')?.click();
}
"""

_CAPTCHA_SURFACE_RECT = """
() => {
  const labels = [...document.querySelectorAll('body *')].filter(element => {
    const ownText = [...element.childNodes]
      .filter(node => node.nodeType === Node.TEXT_NODE)
      .map(node => node.textContent || '')
      .join(' ')
      .trim();
    return /please complete (the )?captcha/i.test(ownText);
  });
  let element = labels[0] || null;
  while (element && element !== document.body) {
    const box = element.getBoundingClientRect();
    if (box.width >= 280 && box.height >= 320 && box.width <= 700 && box.height <= 800) {
      const margin = 16;
      const x = Math.max(0, box.x - margin);
      const y = Math.max(0, box.y - margin);
      return {
        x,
        y,
        width: Math.min(innerWidth - x, box.width + margin * 2),
        height: Math.min(innerHeight - y, box.height + margin * 2)
      };
    }
    element = element.parentElement;
  }
  return {x: 0, y: 0, width: innerWidth, height: innerHeight};
}
"""

_CAPTCHA_IMAGE_SOURCES = """
() => {
  const capture = selector => {
    const image = document.querySelector(selector);
    if (!(image instanceof HTMLImageElement)) return '';
    const width = image.naturalWidth || Math.round(image.getBoundingClientRect().width);
    const height = image.naturalHeight || Math.round(image.getBoundingClientRect().height);
    if (width < 1 || height < 1) return image.currentSrc || image.src || '';
    try {
      const canvas = document.createElement('canvas');
      canvas.width = width;
      canvas.height = height;
      const context = canvas.getContext('2d');
      if (!context) return image.currentSrc || image.src || '';
      context.clearRect(0, 0, width, height);
      context.drawImage(image, 0, 0, width, height);
      return canvas.toDataURL('image/png');
    } catch (_) {
      return image.currentSrc || image.src || '';
    }
  };
  return {
    background: capture('#aliyunCaptcha-img'),
    piece: capture('#aliyunCaptcha-puzzle')
  };
}
"""

_CAPTCHA_VISUAL_READY = """
() => {
  const image = document.querySelector('#aliyunCaptcha-img');
  const windowElement = document.querySelector('#aliyunCaptcha-window-float');
  if (!image || !windowElement) return false;
  const imageRect = image.getBoundingClientRect();
  const windowRect = windowElement.getBoundingClientRect();
  const imageStyle = getComputedStyle(image);
  const windowStyle = getComputedStyle(windowElement);
  return imageRect.width >= 240 && imageRect.height >= 200 &&
    windowRect.width >= 280 && windowRect.height >= 260 &&
    imageStyle.visibility !== 'hidden' && imageStyle.opacity !== '0' &&
    windowStyle.display !== 'none' && windowStyle.visibility !== 'hidden';
}
"""

_CAPTCHA_SLIDER_OBJECT_CENTER = """
() => {
  const background = document.querySelector('#aliyunCaptcha-img');
  const piece = document.querySelector('#aliyunCaptcha-puzzle');
  if (!background || !piece) return 0.05;
  const backgroundRect = background.getBoundingClientRect();
  const pieceRect = piece.getBoundingClientRect();
  if (backgroundRect.width < 1 || pieceRect.width < 1) return 0.05;
  return (pieceRect.x + pieceRect.width / 2 - backgroundRect.x) / backgroundRect.width;
}
"""
