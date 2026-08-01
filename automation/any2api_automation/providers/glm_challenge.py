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

from ..captcha.artifacts import record_captcha_artifact
from ..captcha.models import SolverEstimate, VisualAction
from ..captcha.object_placement import estimate_blurred_object_placement
from ..captcha.registry import registry
from ..lifecycle.browser import browser_operation_deadline
from .glm_settings import settings


@dataclass(frozen=True)
class GlmCaptchaProfile:
    scene_id: str
    mode: str
    region: str
    prefix: str
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
    background: bytes = b""
    piece: bytes = b""


class GlmAliyunChallenge:
    def __init__(self, profile: GlmCaptchaProfile | None = None) -> None:
        config = settings()
        self.profile = profile or GlmCaptchaProfile(
            scene_id=config.glm_chat_captcha_scene_id,
            mode="popup",
            region=config.glm_chat_captcha_region,
            prefix=config.glm_captcha_prefix,
        )
        if self.profile.mode not in {"embed", "popup"}:
            raise ValueError("GLM captcha mode must be embed or popup")
        if self.profile.region not in {"cn", "sgp"}:
            raise ValueError("GLM captcha region must be cn or sgp")
        self.last_diagnostic = "unavailable"

    @classmethod
    def for_authentication(cls) -> GlmAliyunChallenge:
        config = settings()
        return cls(
            GlmCaptchaProfile(
                scene_id=config.glm_auth_captcha_scene_id,
                mode="embed",
                region=config.glm_auth_captcha_region,
                prefix=config.glm_captcha_prefix,
            )
        )

    @classmethod
    def for_chat(cls) -> GlmAliyunChallenge:
        return cls()

    def arm_official(self, page: Any) -> None:
        """Observe the provider-owned captcha before its application scripts execute."""
        page.add_init_script(_OBSERVE_OFFICIAL_CAPTCHA)

    def solve(self, page: Any, *, timeout_seconds: int | None = None) -> str:
        config = settings()
        operation_timeout = (
            (timeout_seconds or config.glm_captcha_timeout_seconds)
            + config.glm_official_captcha_wait_seconds
            + 30
        )
        expired = None
        try:
            with browser_operation_deadline(
                page,
                operation_timeout,
                label="GLM Aliyun captcha",
            ) as expired:
                return self._solve(page, timeout_seconds=timeout_seconds)
        except Exception as error:
            if expired is not None and expired.is_set():
                raise RuntimeError("GLM Aliyun captcha operation timed out") from error
            raise

    def _solve(self, page: Any, *, timeout_seconds: int | None = None) -> str:
        config = settings()
        if not self._use_official_initialization(page):
            self._install(page)
        deadline = time.monotonic() + (timeout_seconds or config.glm_captcha_timeout_seconds)
        round_diagnostics: list[str] = []
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
                    round_diagnostics.append(self.last_diagnostic)
                    continue
                try:
                    surface = self._capture_surface(page)
                except RuntimeError as error:
                    self.last_diagnostic = (
                        f"mode=visual, attempt={attempt}, round={round_number}, "
                        f"surface=transient:{error}"
                    )
                    round_diagnostics.append(self.last_diagnostic)
                    page.wait_for_timeout(300)
                    continue
                actions = self._solve_actions(page, surface, remaining)
                if not actions:
                    solver_diagnostic = self.last_diagnostic
                    self.last_diagnostic = (
                        f"mode=visual, attempt={attempt}, round={round_number}, {solver_diagnostic}"
                    )
                    round_diagnostics.append(self.last_diagnostic)
                    break
                self._execute(page, actions, surface)
                execution_diagnostic = f":{self.last_diagnostic}" if surface.slider else ""
                self.last_diagnostic = (
                    f"mode=visual, attempt={attempt}, round={round_number}, actions="
                    + ",".join(action.type for action in actions)
                    + execution_diagnostic
                )
                round_diagnostics.append(self.last_diagnostic)
                state = self._wait_for_challenge_result(
                    page,
                    min(30.0, max(1.0, deadline - time.monotonic())),
                )
                ticket = self._accepted_ticket(state)
                if ticket:
                    solver_evidence = self.last_diagnostic.split(":artifact=", 1)[0]
                    self.last_diagnostic = (
                        f"mode=visual, attempt={attempt}, round={round_number}, "
                        f"ticket=accepted, {solver_evidence}"
                    )
                    return ticket
                if state.get("status") == "running":
                    raise RuntimeError(
                        f"GLM captcha official callback remained pending ({self.last_diagnostic})"
                    )
                if state.get("status") in {"error", "closed"}:
                    raise RuntimeError("GLM captcha service did not produce a usable challenge")
                if state.get("status") == "failed":
                    break
            if time.monotonic() < deadline and attempt < config.glm_captcha_attempts:
                self._refresh(page)
        if round_diagnostics:
            self.last_diagnostic = " || ".join(round_diagnostics[-6:])[:3000]
        raise RuntimeError(
            "GLM Aliyun captcha did not reach the official success callback "
            f"({self.last_diagnostic})"
        )

    def _use_official_initialization(self, page: Any) -> bool:
        state = self._state(page)
        if state.get("status") in {"ready", "running", "success"}:
            return True
        if state.get("status") not in {"armed", "loading"}:
            return False
        deadline = time.monotonic() + max(1, settings().glm_official_captcha_wait_seconds)
        while time.monotonic() < deadline:
            page.wait_for_timeout(200)
            state = self._state(page)
            if state.get("status") in {"ready", "running", "success"}:
                return True
            if state.get("status") in {"failed", "error", "closed", "missing"}:
                return False
        return False

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
                "region": self.profile.region,
                "prefix": self.profile.prefix,
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
                detail = " ".join(str(state.get("error") or "none").split())[:160]
                raise RuntimeError(f"GLM Aliyun captcha initialization failed detail={detail}")
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

    def _wait_for_challenge_result(
        self,
        page: Any,
        timeout_seconds: float,
    ) -> dict[str, Any]:
        deadline = time.monotonic() + max(0.2, timeout_seconds)
        state = self._state(page)
        while state.get("status") not in {"success", "failed", "error", "closed"}:
            if time.monotonic() >= deadline:
                break
            page.wait_for_timeout(200)
            state = self._state(page)
        return state

    def _refresh(self, page: Any) -> None:
        page.evaluate(_REFRESH_CAPTCHA)
        page.wait_for_timeout(800 + random.randint(0, 500))
        if self._start_if_required(page):
            page.wait_for_timeout(1_800)

    def _start_if_required(self, page: Any) -> bool:
        if self._visual_surface_ready(page):
            return False
        try:
            start_icon = page.locator("#aliyunCaptcha-start-icon").first
            if start_icon.count() and start_icon.is_visible():
                start_icon.click()
                return True
            body = page.locator("#aliyunCaptcha-captcha-body").first
            if body.count() and body.is_visible():
                text = " ".join(str(body.text_content() or "").split()).lower()
                if ("start" in text and "verif" in text) or "点击开始验证" in text:
                    body.click()
                    return True
            for selector in (
                "#aliyunCaptcha-float-wrapper",
                "#any2api-glm-captcha-element",
            ):
                host = page.locator(selector)
                for label in ("Click to start verification", "Click to verify", "点击开始验证"):
                    starter = host.get_by_text(label, exact=False).first
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
        deadline = time.monotonic() + 2.5
        last_error = "not_visible"
        while time.monotonic() < deadline:
            try:
                captcha_image = page.locator("#aliyunCaptcha-img").first
                slider = page.locator("#aliyunCaptcha-sliding-slider").first
                image_box = captcha_image.bounding_box() if captcha_image.is_visible() else None
                slider_visible = bool(slider.count() and slider.is_visible())
                if (
                    image_box
                    and slider_visible
                    and float(image_box.get("width") or 0) >= 160
                    and float(image_box.get("height") or 0) >= 160
                ):
                    x = float(image_box["x"])
                    y = float(image_box["y"])
                    width = float(image_box["width"])
                    height = float(image_box["height"])
                    image = page.screenshot(
                        type="png",
                        clip={"x": x, "y": y, "width": width, "height": height},
                        timeout=15_000,
                    )
                    return GlmCaptchaSurface(
                        image=image,
                        x=x,
                        y=y,
                        width=width,
                        height=height,
                        slider=True,
                    )
            except Exception as error:  # noqa: BLE001 - the SDK replaces captcha nodes in place
                last_error = type(error).__name__
            page.wait_for_timeout(100)
        raise RuntimeError(f"official slider surface changed before capture ({last_error})")

    def _solve_actions(
        self,
        page: Any,
        surface: GlmCaptchaSurface,
        _timeout_seconds: float,
    ) -> list[VisualAction]:
        if not self.profile.semantic_slider:
            local_action, _ = self._local_slider_action(page, surface)
            if local_action is not None:
                return [local_action]
        if surface.slider:
            semantic = self._semantic_slider_input(page, surface.image)
            image = semantic.image
            artifact = record_captcha_artifact("glm-semantic", image)
            record_captcha_artifact("glm-surface", surface.image)
            target_x, solver_diagnostic = self._semantic_slider_target(
                semantic,
            )
            if target_x is None:
                self.last_diagnostic = (
                    f"solver={solver_diagnostic}:object_x={semantic.object_center_x:.3f}:"
                    f"artifact={artifact or 'disabled'}"
                )
                return []
            actions = [
                VisualAction(
                    type="drag",
                    start=(semantic.object_center_x, 0.5),
                    end=(target_x, 0.5),
                )
            ]
        else:
            artifact = record_captcha_artifact("glm-visual-unsupported", surface.image)
            self.last_diagnostic = (
                "solver=deterministic_only:challenge=non_slider_unsupported:"
                f"artifact={artifact or 'disabled'}"
            )
            return []
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
            self.last_diagnostic = (
                f"solver={solver_diagnostic}:object_x={semantic.object_center_x:.3f}:"
                f"target_x={target_x:.3f}:artifact={artifact or 'disabled'}"
            )
            return [
                VisualAction(
                    type="drag",
                    start=(semantic.object_center_x, 0.5),
                    end=(target_x, 0.5),
                )
            ]
        return actions

    def _semantic_slider_target(
        self,
        semantic: GlmSemanticSliderInput,
    ) -> tuple[float | None, str]:
        if not semantic.background or not semantic.piece:
            return None, "semantic_sources_unavailable"
        record_captcha_artifact("glm-background", semantic.background)
        record_captcha_artifact("glm-piece", semantic.piece)
        blur_estimate = estimate_blurred_object_placement(
            semantic.background,
            semantic.piece,
        )
        if blur_estimate is not None and blur_estimate.accepted:
            candidate = self._candidate_contact_sheet(
                semantic.background,
                semantic.piece,
                (blur_estimate.center_x,),
                ("CV",),
            )
            candidate = self._candidate_sheet_with_reference(semantic.image, candidate)
            artifact = record_captcha_artifact("glm-semantic-opencv", candidate)
            return (
                blur_estimate.center_x,
                f"opencv_blur={blur_estimate.detail}:artifact={artifact or 'disabled'}",
            )
        detail = blur_estimate.detail if blur_estimate is not None else "unavailable"
        return None, f"opencv_blur={detail}:decision=refresh"

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
        deadline = time.monotonic() + 2.5
        while True:
            try:
                background, piece = self._slider_images(page)
                image = self._compose_semantic_slider_input(rendered_scene, piece, background)
                return GlmSemanticSliderInput(image, object_center_x, background, piece)
            except (TypeError, ValueError):
                if time.monotonic() >= deadline:
                    break
                page.wait_for_timeout(100)
        buffer = BytesIO()
        Image.new("RGBA", (1, 1), (0, 0, 0, 0)).save(buffer, format="PNG")
        image = self._compose_semantic_slider_input(rendered_scene, buffer.getvalue())
        return GlmSemanticSliderInput(image, object_center_x)

    def _candidate_contact_sheet(
        self,
        background: bytes,
        piece: bytes,
        candidates: tuple[float, ...],
        labels: tuple[str, ...],
    ) -> bytes:
        if len(candidates) != len(labels) or not candidates:
            raise ValueError("GLM candidate labels do not match candidate positions")
        with (
            Image.open(BytesIO(background)) as background_source,
            Image.open(BytesIO(piece)) as piece_source,
        ):
            scene = background_source.convert("RGBA")
            foreground = self._slider_foreground_canvas(scene, piece_source)
            box = self._foreground_box(foreground)
            if box is None:
                raise ValueError("GLM candidate foreground is unavailable")
            object_center = (box[0] + box[2]) / 2
            tile_size = 220
            label_height = 28
            gap = 12
            columns = min(3, len(candidates))
            rows = math.ceil(len(candidates) / columns)
            canvas = Image.new(
                "RGB",
                (
                    gap + columns * (tile_size + gap),
                    gap + rows * (label_height + tile_size + gap),
                ),
                "white",
            )
            draw = ImageDraw.Draw(canvas)
            for index, (candidate, label) in enumerate(zip(candidates, labels, strict=True)):
                shift = round(candidate * scene.width - object_center)
                layer = Image.new("RGBA", scene.size, (0, 0, 0, 0))
                layer.paste(foreground, (shift, 0), foreground)
                completed_source = Image.alpha_composite(scene, layer).convert("RGB")
                marker_box = (
                    max(0, box[0] + shift - 3),
                    max(0, box[1] - 3),
                    min(scene.width - 1, box[2] + shift + 3),
                    min(scene.height - 1, box[3] + 3),
                )
                ImageDraw.Draw(completed_source).rectangle(
                    marker_box,
                    outline=(255, 0, 220),
                    width=3,
                )
                completed = completed_source.resize(
                    (tile_size, tile_size),
                    Image.Resampling.LANCZOS,
                )
                column = index % columns
                row = index // columns
                x = gap + column * (tile_size + gap)
                y = gap + row * (label_height + tile_size + gap)
                draw.rectangle((x, y, x + tile_size, y + label_height - 2), fill="black")
                draw.text((x + 8, y + 6), label, fill="white")
                canvas.paste(completed, (x, y + label_height))
            return self._encode_candidate_sheet(canvas)

    def _encode_candidate_sheet(self, canvas: Image.Image) -> bytes:
        smallest = b""
        for scale in (1.0, 0.85, 0.7, 0.6):
            candidate = canvas
            if scale < 1:
                candidate = canvas.resize(
                    (
                        max(1, round(canvas.width * scale)),
                        max(1, round(canvas.height * scale)),
                    ),
                    Image.Resampling.LANCZOS,
                )
            for colors in (128, 96, 64, 48):
                output = BytesIO()
                candidate.quantize(colors=colors).save(
                    output,
                    format="PNG",
                    optimize=True,
                )
                encoded = output.getvalue()
                if not smallest or len(encoded) < len(smallest):
                    smallest = encoded
                if len(encoded) <= 120_000:
                    return encoded
        return smallest

    def _candidate_sheet_with_reference(
        self,
        reference: bytes,
        candidates: bytes,
    ) -> bytes:
        with (
            Image.open(BytesIO(reference)) as reference_source,
            Image.open(BytesIO(candidates)) as candidate_source,
        ):
            candidate_image = candidate_source.convert("RGB")
            reference_image = reference_source.convert("RGB")
            reference_height = round(
                reference_image.height * candidate_image.width / reference_image.width
            )
            reference_image = reference_image.resize(
                (candidate_image.width, reference_height),
                Image.Resampling.LANCZOS,
            )
            gap = 12
            canvas = Image.new(
                "RGB",
                (
                    candidate_image.width,
                    reference_image.height + gap + candidate_image.height,
                ),
                "white",
            )
            canvas.paste(reference_image, (0, 0))
            ImageDraw.Draw(canvas).line(
                (
                    0,
                    reference_image.height + gap // 2,
                    canvas.width,
                    reference_image.height + gap // 2,
                ),
                fill="black",
                width=2,
            )
            canvas.paste(candidate_image, (0, reference_image.height + gap))
            return self._encode_candidate_sheet(canvas)

    def _slider_foreground_canvas(
        self,
        scene: Image.Image,
        piece_source: Image.Image,
    ) -> Image.Image:
        piece = piece_source.convert("RGBA")
        if piece.width < 1 or piece.height < 1:
            raise ValueError("GLM candidate foreground has invalid dimensions")
        scale = min(scene.width / piece.width, scene.height / piece.height)
        if piece.height == scene.height and piece.width <= scene.width:
            scale = 1.0
        if abs(scale - 1.0) > 0.001:
            piece = piece.resize(
                (
                    max(1, round(piece.width * scale)),
                    max(1, round(piece.height * scale)),
                ),
                Image.Resampling.LANCZOS,
            )
        canvas = Image.new("RGBA", scene.size, (0, 0, 0, 0))
        canvas.alpha_composite(piece, (0, 0))
        return canvas

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
        scene_width = self._slider_scene_width(page)
        for action in actions:
            if action.type == "click" and action.at is not None:
                x, y = self._screen_point(action.at, surface)
                page.mouse.move(x, y, steps=random.randint(4, 8))
                page.wait_for_timeout(random.randint(80, 220))
                page.mouse.click(x, y, delay=random.randint(45, 120))
            elif action.type == "drag" and action.start is not None and action.end is not None:
                if slider_box is not None:
                    adaptive_diagnostic = self._drag_slider_to_scene_target(
                        page,
                        action,
                        surface,
                        slider_box,
                        scene_width,
                    )
                    if adaptive_diagnostic is not None:
                        self.last_diagnostic += f":{adaptive_diagnostic}"
                        page.wait_for_timeout(random.randint(250, 600))
                        continue
                    start, end = self._slider_drag_points(action, surface, slider_box, scene_width)
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

    def _drag_slider_to_scene_target(
        self,
        page: Any,
        action: VisualAction,
        surface: GlmCaptchaSurface,
        slider_box: dict[str, float],
        scene_width: float,
    ) -> str | None:
        if action.end is None:
            return None
        try:
            image_box = page.locator("#aliyunCaptcha-img").first.bounding_box()
            piece_box = page.locator("#aliyunCaptcha-puzzle").first.bounding_box()
            track_box = page.locator("#aliyunCaptcha-sliding-body").first.bounding_box()
            if not image_box or not piece_box or not track_box:
                return None
            image = {key: float(image_box[key]) for key in ("x", "y", "width", "height")}
            piece = {key: float(piece_box[key]) for key in ("x", "y", "width", "height")}
            track = {key: float(track_box[key]) for key in ("x", "y", "width", "height")}
        except Exception:  # noqa: BLE001 - the SDK can replace its DOM during refresh
            return None
        max_handle_delta = track["width"] - slider_box["width"]
        max_piece_delta = image["width"] - piece["width"]
        if max_handle_delta < 40 or max_piece_delta < 40:
            return None
        desired_piece_left = image["x"] + action.end[0] * image["width"] - piece["width"] / 2
        desired_piece_left = max(
            image["x"],
            min(image["x"] + max_piece_delta, desired_piece_left),
        )
        target_piece_delta = desired_piece_left - piece["x"]
        if target_piece_delta <= 2:
            return None
        target_fraction = max(0.001, min(1.0, target_piece_delta / max_piece_delta))
        start = (
            slider_box["x"] + slider_box["width"] / 2,
            slider_box["y"] + slider_box["height"] / 2,
        )
        predicted_handle_delta = max_handle_delta * math.sqrt(target_fraction)
        calibration_delta = min(
            max_handle_delta * 0.45,
            max(18.0, predicted_handle_delta * 0.62),
        )
        pointer = start
        page.mouse.move(*start, steps=random.randint(4, 8))
        page.wait_for_timeout(random.randint(90, 180))
        page.mouse.down()
        try:
            pointer = self._move_held_slider(
                page,
                pointer,
                (start[0] + calibration_delta, start[1]),
            )
            page.wait_for_timeout(random.randint(50, 90))
            observed_piece = page.locator("#aliyunCaptcha-puzzle").first.bounding_box()
            observed_delta = float((observed_piece or {}).get("x") or piece["x"]) - piece["x"]
            handle_fraction = calibration_delta / max_handle_delta
            piece_fraction = observed_delta / max_piece_delta
            exponent = 2.0
            if 0.001 < piece_fraction < 0.999 and 0.001 < handle_fraction < 0.999:
                measured = math.log(piece_fraction) / math.log(handle_fraction)
                if 0.4 <= measured <= 4.0:
                    exponent = measured
            final_error = target_piece_delta - observed_delta
            current_handle_delta = calibration_delta
            current_piece_delta = observed_delta
            previous_handle_delta: float | None = None
            previous_piece_delta: float | None = None
            for _ in range(6):
                if abs(final_error) <= 1.0:
                    break
                desired_handle_delta = max_handle_delta * target_fraction ** (1.0 / exponent)
                if previous_handle_delta is not None and previous_piece_delta is not None:
                    handle_step = current_handle_delta - previous_handle_delta
                    piece_step = current_piece_delta - previous_piece_delta
                    slope = piece_step / handle_step if abs(handle_step) >= 0.5 else 0.0
                    if slope > 0.02:
                        correction_limit = max(8.0, max_handle_delta * 0.2)
                        correction = max(
                            -correction_limit,
                            min(correction_limit, final_error / slope),
                        )
                        desired_handle_delta = current_handle_delta + correction
                desired_handle_delta = max(4.0, min(max_handle_delta, desired_handle_delta))
                previous_handle_delta = current_handle_delta
                previous_piece_delta = current_piece_delta
                pointer = self._move_held_slider(
                    page,
                    pointer,
                    (start[0] + desired_handle_delta, start[1]),
                )
                page.wait_for_timeout(random.randint(45, 80))
                current_piece = page.locator("#aliyunCaptcha-puzzle").first.bounding_box()
                current_delta = float((current_piece or {}).get("x") or piece["x"]) - piece["x"]
                final_error = target_piece_delta - current_delta
                current_handle_delta = desired_handle_delta
                current_piece_delta = current_delta
                current_handle_fraction = desired_handle_delta / max_handle_delta
                current_piece_fraction = current_delta / max_piece_delta
                if (
                    0.001 < current_handle_fraction < 0.999
                    and 0.001 < current_piece_fraction < 0.999
                ):
                    measured = math.log(current_piece_fraction) / math.log(current_handle_fraction)
                    if 0.4 <= measured <= 4.0:
                        exponent = measured
            return (
                f"adaptive_curve={exponent:.3f}:final_error={final_error:.2f}:"
                f"scene_width={scene_width:.1f}:surface_width={surface.width:.1f}"
            )
        finally:
            page.wait_for_timeout(random.randint(80, 160))
            page.mouse.up()

    def _move_held_slider(
        self,
        page: Any,
        start: tuple[float, float],
        end: tuple[float, float],
    ) -> tuple[float, float]:
        distance = math.dist(start, end)
        steps = max(6, min(24, round(distance / 10)))
        bend = random.uniform(-1.5, 1.5)
        for index in range(1, steps + 1):
            progress = index / steps
            eased = 3 * progress**2 - 2 * progress**3
            x = start[0] + (end[0] - start[0]) * eased
            y = start[1] + (end[1] - start[1]) * eased
            y += math.sin(math.pi * progress) * bend
            page.mouse.move(x, y)
            page.wait_for_timeout(random.randint(7, 18))
        return end

    def _slider_drag_points(
        self,
        action: VisualAction,
        surface: GlmCaptchaSurface,
        slider_box: dict[str, float],
        scene_width: float,
    ) -> tuple[tuple[float, float], tuple[float, float]]:
        if action.start is None or action.end is None:
            raise RuntimeError("GLM slider action is incomplete")
        start = (
            slider_box["x"] + slider_box["width"] / 2,
            slider_box["y"] + slider_box["height"] / 2,
        )
        effective_width = scene_width if scene_width >= 100 else surface.width
        delta_x = (action.end[0] - action.start[0]) * effective_width
        end = (
            max(start[0] + 4, min(surface.x + surface.width - 4, start[0] + delta_x)),
            start[1],
        )
        return start, end

    def _slider_scene_width(self, page: Any) -> float:
        try:
            background = page.locator("#aliyunCaptcha-img").first
            if background.count() and background.is_visible():
                box = background.bounding_box()
                width = float((box or {}).get("width") or 0)
                if width >= 100:
                    return width
        except Exception:  # noqa: BLE001,S110 - fallback uses captured surface width
            pass
        return 0.0

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


_OBSERVE_OFFICIAL_CAPTCHA = """
(() => {
  const state = window.__any2apiGlmCaptchaState = {
    status: 'armed', ticket: '', error: '', source: 'official'
  };
  let initializer;
  const wrap = value => {
    if (typeof value !== 'function') return value;
    return config => {
      state.status = 'loading';
      const originalSuccess = config?.success;
      const originalFail = config?.fail;
      const originalError = config?.onError;
      const originalClose = config?.onClose;
      const originalInstance = config?.getInstance;
      return value({
        ...config,
        success: (...args) => {
          const result = args[0];
          state.ticket = typeof result === 'string' ? result : JSON.stringify(result);
          state.status = 'success';
          return originalSuccess?.(...args);
        },
        fail: (...args) => {
          state.status = 'failed';
          state.error = typeof args[0] === 'string' ? args[0] : 'challenge failed';
          return originalFail?.(...args);
        },
        onError: (...args) => {
          state.status = 'error';
          state.error = typeof args[0] === 'string' ? args[0] : 'captcha service error';
          return originalError?.(...args);
        },
        onClose: (...args) => {
          state.status = 'closed';
          return originalClose?.(...args);
        },
        getInstance: instance => {
          window.__any2apiGlmCaptchaInstance = instance;
          state.status = 'ready';
          const result = originalInstance?.(instance);
          if (state.status === 'ready') state.status = 'running';
          return result;
        }
      });
    };
  };
  Object.defineProperty(window, 'initAliyunCaptcha', {
    configurable: true,
    enumerable: true,
    get: () => wrap(initializer),
    set: value => {
      initializer = value;
      window.__any2apiGlmCaptchaRawInit = value;
    }
  });
})();
"""

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
  const initializer = () => window.__any2apiGlmCaptchaRawInit || window.initAliyunCaptcha;
  const waitForInitializer = timeout => new Promise(resolve => {
    const started = Date.now();
    const check = () => {
      if (initializer()) return resolve(true);
      if (Date.now() - started >= timeout) return resolve(false);
      setTimeout(check, 50);
    };
    check();
  });
  const loadScript = src => new Promise(resolve => {
    const script = document.createElement('script');
    const finish = loaded => {
      clearTimeout(timer);
      script.onload = null;
      script.onerror = null;
      if (!loaded) script.remove();
      resolve(loaded);
    };
    const timer = setTimeout(() => finish(false), 10000);
    script.src = src;
    script.onload = () => finish(true);
    script.onerror = () => finish(false);
    document.head.appendChild(script);
  });
  if (!initializer()) {
    const existing = document.querySelector(`script[src="${config.scriptUrl}"]`);
    if (existing) await waitForInitializer(5000);
    if (!initializer()) {
      existing?.remove();
      const separator = config.scriptUrl.includes('?') ? '&' : '?';
      const loaded = await loadScript(
        `${config.scriptUrl}${separator}_any2api=${Date.now()}`
      );
      if (!loaded) {
        state.status = 'error';
        state.error = 'captcha script load failed';
        return;
      }
      await waitForInitializer(2000);
    }
  }
  if (!initializer()) {
    state.status = 'error';
    state.error = 'initAliyunCaptcha missing';
    return;
  }
  initializer()({
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
  const slider = document.querySelector('#aliyunCaptcha-sliding-slider');
  const windowElement = document.querySelector('#aliyunCaptcha-window-float');
  if (!image || !slider || !windowElement) return false;
  const imageRect = image.getBoundingClientRect();
  const sliderRect = slider.getBoundingClientRect();
  const windowRect = windowElement.getBoundingClientRect();
  const imageStyle = getComputedStyle(image);
  const sliderStyle = getComputedStyle(slider);
  const windowStyle = getComputedStyle(windowElement);
  return imageRect.width >= 160 && imageRect.height >= 160 &&
    sliderRect.width >= 24 && sliderRect.height >= 24 &&
    windowRect.width >= 280 && windowRect.height >= 260 &&
    imageStyle.visibility !== 'hidden' && imageStyle.opacity !== '0' &&
    sliderStyle.display !== 'none' && sliderStyle.visibility !== 'hidden' &&
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
