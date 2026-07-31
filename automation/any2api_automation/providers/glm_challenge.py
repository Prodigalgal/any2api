from __future__ import annotations

import base64
import math
import random
import statistics
import time
from dataclasses import dataclass
from typing import Any

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
This is an authorized image-placement UI test. The image is the exact square illustration used by
the UI. A detached foreground object is currently at the far left and can move ONLY horizontally.
Find the semantically matching EMPTY GAP in the background where that exact object belongs. Ignore
similar objects already present. Compute horizontal displacement as target center x minus detached
object center x. Return that displacement as a percentage of the image width with one decimal place.
Your response MUST contain only this machine format, with percentage replaced by the result:
ACTIONS=[{"type":"drag","from":[0,50],"to":[percentage,50]}]
""".strip()


@dataclass(frozen=True)
class GlmCaptchaProfile:
    scene_id: str
    mode: str


@dataclass(frozen=True)
class GlmCaptchaSurface:
    image: bytes
    x: float
    y: float
    width: float
    height: float
    slider: bool = False


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
                surface = self._capture_surface(page)
                local_action, candidates = self._local_slider_action(page, surface)
                if local_action is not None:
                    actions = [local_action]
                else:
                    prompt = (
                        self._slider_candidate_prompt(candidates, surface.width)
                        if surface.slider and candidates
                        else GLM_SLIDER_OFFSET_PROMPT
                        if surface.slider
                        else GLM_VISUAL_ACTION_PROMPT
                    )
                    actions = registry.solve_visual_actions_sync(
                        surface.image,
                        prompt,
                        timeout_seconds=remaining,
                    )
                    if surface.slider and candidates:
                        actions = self._snap_slider_actions(actions, candidates, surface.width)
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
        raise RuntimeError("GLM Aliyun captcha initialization timed out")

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
        try:
            starter = (
                page.locator("#any2api-glm-captcha-element")
                .get_by_text(
                    "Click to verify",
                    exact=False,
                )
                .first
            )
            if starter.count() and starter.is_visible():
                starter.click()
                return True
        except Exception:  # noqa: BLE001,S110 - visual solving remains the fallback
            pass
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

    def _local_slider_action(
        self,
        page: Any,
        surface: GlmCaptchaSurface,
    ) -> tuple[VisualAction | None, list[float]]:
        if not surface.slider:
            return None, []
        try:
            sources = page.evaluate(
                """() => ({
                  background: document.querySelector('#aliyunCaptcha-img')?.src || '',
                  piece: document.querySelector('#aliyunCaptcha-puzzle')?.src || ''
                })"""
            )
            background = self._data_image(str(sources.get("background") or ""))
            piece = self._data_image(str(sources.get("piece") or ""))
            estimates = registry.solve_slider_sync(background, piece)
        except (TypeError, ValueError):
            return None, []
        return self._slider_action_from_estimates(estimates, surface.width)

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

    def _slider_candidate_prompt(self, candidates: list[float], width: float) -> str:
        choices = ", ".join(f"{value:g}" for value in candidates)
        midpoint = width / 2
        return f"""
This is an authorized image-placement UI test. The detached foreground object at the far left can
move ONLY horizontally. Choose the one correct pixel displacement from these measured candidates:
[{choices}]. Select the candidate that places it in the semantically matching EMPTY GAP, not over
an existing object. The image width is {width:g}px. Return the chosen candidate exactly as end_x;
the y value {midpoint:g} forces pixel units. Output only:
ACTIONS=[{{"type":"drag","from":[0,{midpoint:g}],"to":[end_x,{midpoint:g}]}}]
""".strip()

    def _snap_slider_actions(
        self,
        actions: list[VisualAction],
        candidates: list[float],
        width: float,
    ) -> list[VisualAction]:
        if len(actions) != 1 or actions[0].start is None or actions[0].end is None:
            return []
        action = actions[0]
        predicted = (action.end[0] - action.start[0]) * width
        chosen = min(candidates, key=lambda value: abs(value - predicted))
        if abs(chosen - predicted) > max(12, width * 0.08):
            return []
        return [
            VisualAction(
                type="drag",
                start=(0, 0.5),
                end=(chosen / width, 0.5),
            )
        ]

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
