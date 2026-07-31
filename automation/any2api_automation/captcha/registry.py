import asyncio
import base64
import concurrent.futures
import importlib.util
import io
import itertools
import json
import math
import random
import re
import statistics
import tempfile
import threading
import time
from collections import Counter
from pathlib import Path

import httpx

from ..config import settings
from ..resources import lanes
from .models import SolverEstimate, VisualAction

_CAPTCHA_TEXT = r"[0-9A-Za-z\u4e00-\u9fff]{3,12}"


def _captcha_text_candidate(content: str) -> str | None:
    text = content.strip()
    for pattern in (
        rf"\bCAPTCHA\s*[:=]\s*[`*'\"]*({_CAPTCHA_TEXT})",
        rf"\*\*({_CAPTCHA_TEXT})\*\*",
        rf"`({_CAPTCHA_TEXT})`",
        rf"['\"]({_CAPTCHA_TEXT})['\"]",
        rf":\s*[`*'\"]*({_CAPTCHA_TEXT})[\s`*'\".!?]*$",
    ):
        match = re.search(pattern, text, re.IGNORECASE)
        if match:
            return match.group(1)
    if re.fullmatch(rf"[\s`*'\"]*{_CAPTCHA_TEXT}[\s`*'\"]*", text):
        match = re.search(_CAPTCHA_TEXT, text)
        return match.group(0) if match else None
    return None


class SolverRegistry:
    def __init__(self) -> None:
        self._ddddocr_text = None
        self._ddddocr_slider = None
        self._recognizer = None
        self._model_lock = threading.Lock()
        self._diagnostics = threading.local()

    def visual_diagnostic(self) -> str:
        return str(getattr(self._diagnostics, "visual", "unavailable"))

    def capabilities(self) -> dict[str, dict[str, object]]:
        return {
            "ddddocr_text": self._capability("ddddocr", ("ocr",)),
            "ddddocr_slider": self._capability("ddddocr", ("slider",)),
            "captcha_recognizer": self._capability("captcha_recognizer", ("slider",), lazy=True),
            "opencv_template": self._capability("cv2", ("slider", "tap", "dots")),
        }

    async def solve_text(self, image: bytes) -> list[SolverEstimate]:
        async with lanes.ocr:
            return await asyncio.to_thread(self._solve_text_sync, image)

    async def solve_slider(self, background: bytes, piece: bytes) -> list[SolverEstimate]:
        async with lanes.slider:
            tasks = [
                asyncio.to_thread(self._solve_slider_ddddocr_sync, background, piece),
                asyncio.to_thread(self._solve_slider_opencv_sync, background, piece),
            ]
            results = await asyncio.gather(*tasks, return_exceptions=True)
        estimates = [item for item in results if isinstance(item, SolverEstimate)]
        if self.capabilities()["captcha_recognizer"]["available"]:
            async with lanes.recognizer:
                result = await asyncio.to_thread(self._solve_slider_recognizer_sync, background)
                if result:
                    estimates.append(result)
        return estimates

    async def solve_dots(self, image: bytes, color: str = "any") -> list[SolverEstimate]:
        async with lanes.ocr:
            estimate = await asyncio.to_thread(self._solve_dots_sync, image, color)
        return [estimate] if estimate else []

    async def solve_tap(self, targets: bytes, panel: bytes) -> list[SolverEstimate]:
        async with lanes.ocr:
            estimate = await asyncio.to_thread(self._solve_tap_sync, targets, panel)
        return [estimate] if estimate else []

    def solve_text_sync(self, image: bytes) -> list[SolverEstimate]:
        return self._solve_text_sync(image)

    def solve_slider_sync(self, background: bytes, piece: bytes) -> list[SolverEstimate]:
        estimates = [
            self._solve_slider_ddddocr_sync(background, piece),
            self._solve_slider_opencv_sync(background, piece),
        ]
        if self.capabilities()["captcha_recognizer"]["available"]:
            estimates.append(self._solve_slider_recognizer_sync(background))
        return [item for item in estimates if item is not None]

    def solve_dots_sync(self, image: bytes, color: str = "any") -> list[SolverEstimate]:
        estimate = self._solve_dots_sync(image, color)
        return [estimate] if estimate else []

    def solve_tap_sync(self, targets: bytes, panel: bytes) -> list[SolverEstimate]:
        estimate = self._solve_tap_sync(targets, panel)
        return [estimate] if estimate else []

    def solve_visual_points_sync(self, image: bytes, prompt: str) -> SolverEstimate | None:
        content = self._visual_completion_sync(image, prompt, max_tokens=120)
        if not content:
            return None
        try:
            match = re.search(r"POINTS\s*=\s*([0-9.,;\s]+)", content, re.IGNORECASE)
            if not match:
                self._diagnostics.visual = "response_without_points"
                return None
            points: list[tuple[float, float]] = []
            for raw_point in match.group(1).split(";"):
                values = [value.strip() for value in raw_point.split(",")]
                if len(values) < 2:
                    continue
                x, y = float(values[0]), float(values[1])
                if 0 <= x <= 1 and 0 <= y <= 1:
                    points.append((x, y))
            if not 2 <= len(points) <= 10:
                self._diagnostics.visual = f"invalid_point_count:{len(points)}"
                return None
            spread = (max(x for x, _ in points) - min(x for x, _ in points)) + (
                max(y for _, y in points) - min(y for _, y in points)
            )
            if spread < 0.12:
                self._diagnostics.visual = f"points_too_clustered:{spread:.3f}"
                return None
            self._diagnostics.visual = f"points:{len(points)}:spread:{spread:.3f}"
            return SolverEstimate(
                solver="vision_points",
                value=points,
                confidence=0.55,
                detail=f"count={len(points)}; spread={spread:.3f}",
            )
        except (TypeError, ValueError) as error:
            self._diagnostics.visual = f"point_parse_error:{type(error).__name__}"
            return None

    def solve_visual_text_sync(self, image: bytes, prompt: str) -> SolverEstimate | None:
        content = self._visual_completion_sync(
            image,
            prompt
            + "\nReturn exactly CAPTCHA=<characters>. Do not add any other text or formatting.",
            max_tokens=32,
        )
        if not content:
            return None
        candidate = _captcha_text_candidate(content)
        if candidate is None:
            self._diagnostics.visual = "response_without_captcha_text"
            return None
        return SolverEstimate(
            solver="vision_text",
            value=candidate,
            confidence=0.55,
            detail=f"length={len(candidate)}",
        )

    def solve_visual_choice_sync(
        self,
        image: bytes,
        prompt: str,
        choices: tuple[str, ...],
        *,
        timeout_seconds: float | None = None,
    ) -> str | None:
        normalized = tuple(dict.fromkeys(choice.strip().upper() for choice in choices))
        if not 2 <= len(normalized) <= 20 or any(
            not re.fullmatch(r"[A-Z][A-Z0-9_-]{0,15}", choice) for choice in normalized
        ):
            raise ValueError("visual choices must contain 2-20 unique machine labels")
        config = settings()
        sample_count = max(1, min(5, config.captcha_ai_action_samples))
        sample_timeout = float(max(1, config.captcha_ai_action_sample_timeout_seconds))
        if timeout_seconds is not None:
            sample_timeout = min(sample_timeout, timeout_seconds)
        choice_prompt = (
            "The first characters of your response MUST be CHOICE=<label>. "
            "Write the choice before any analysis. "
            + prompt
            + "\n<label> must be one of: "
            + ", ".join(normalized)
            + "."
        )

        def sample() -> tuple[str, str]:
            content = self._visual_completion_sync(
                image,
                choice_prompt,
                max_tokens=128,
                timeout_seconds=sample_timeout,
            )
            return content, self.visual_diagnostic()

        executor = concurrent.futures.ThreadPoolExecutor(max_workers=sample_count)
        futures = [executor.submit(sample) for _ in range(sample_count)]
        done, pending = concurrent.futures.wait(futures, timeout=sample_timeout)
        for future in pending:
            future.cancel()
        executor.shutdown(wait=False, cancel_futures=True)
        raw_votes: list[tuple[str, str, int]] = []
        sources: list[str] = []
        for index, future in enumerate(done):
            try:
                content, diagnostic = future.result()
            except Exception as error:  # noqa: BLE001 - one failed vote must not fail the batch
                sources.append(f"sample_error:{type(error).__name__}")
                continue
            candidate = self._visual_choice_candidate(content, normalized)
            if candidate:
                raw_votes.append((candidate, diagnostic, index))
                sources.append(diagnostic)
        votes, provider_summary = self._visual_provider_choice_votes(raw_votes)
        counts = Counter(votes)
        required = 1 if sample_count == 1 else max(2, len(votes) // 2 + 1)
        ranking = counts.most_common()
        if (
            ranking
            and ranking[0][1] >= required
            and (len(ranking) == 1 or ranking[0][1] > ranking[1][1])
        ):
            winner, count = ranking[0]
            self._diagnostics.visual = (
                f"choice_consensus:{winner}:{count}/{len(votes)}:"
                f"providers={provider_summary}:completed={len(done)}/{sample_count}"
            )
            return winner
        vote_summary = ",".join(f"{label}:{count}" for label, count in sorted(counts.items()))
        self._diagnostics.visual = (
            f"choice_rejected:completed={len(done)}/{sample_count}:"
            f"valid={len(raw_votes)}/{sample_count}:votes={vote_summary}:"
            f"providers={provider_summary}:"
            f"sources={'|'.join(sources)}"
        )[:1000]
        return None

    @staticmethod
    def _visual_provider_choice_votes(
        raw_votes: list[tuple[str, str, int]],
    ) -> tuple[list[str], str]:
        buckets: dict[str, list[str]] = {}
        display: dict[str, str] = {}
        for candidate, diagnostic, index in raw_votes:
            match = re.search(r"(?:^|:)provider=([^:|]+)", diagnostic)
            provider = match.group(1).strip().lower() if match else ""
            key = provider if provider and provider != "unknown" else f"sample-{index}"
            buckets.setdefault(key, []).append(candidate)
            display[key] = provider if provider and provider != "unknown" else "independent"
        votes: list[str] = []
        summary: list[str] = []
        for key, candidates in buckets.items():
            ranking = Counter(candidates).most_common()
            if ranking and (len(ranking) == 1 or ranking[0][1] > ranking[1][1]):
                votes.append(ranking[0][0])
                summary.append(f"{display[key]}={ranking[0][0]}")
            else:
                summary.append(f"{display[key]}=conflict")
        return votes, ",".join(summary)

    @staticmethod
    def _visual_choice_candidate(content: str, choices: tuple[str, ...]) -> str | None:
        match = re.search(
            r"\bCHOICE\s*[:=]\s*[`*'\"]*([A-Z][A-Z0-9_-]{0,15})",
            str(content),
            re.IGNORECASE,
        )
        if not match:
            return None
        candidate = match.group(1).upper()
        return candidate if candidate in choices else None

    def solve_visual_actions_sync(
        self,
        image: bytes,
        prompt: str,
        *,
        timeout_seconds: float | None = None,
    ) -> list[VisualAction]:
        config = settings()
        sample_count = max(1, min(5, config.captcha_ai_action_samples))
        sample_timeout = float(max(1, config.captcha_ai_action_sample_timeout_seconds))
        if timeout_seconds is not None:
            sample_timeout = min(sample_timeout, timeout_seconds)

        def sample() -> tuple[str, str]:
            content = self._visual_completion_sync(
                image,
                prompt,
                max_tokens=256,
                timeout_seconds=sample_timeout,
            )
            return content, self.visual_diagnostic()

        executor = concurrent.futures.ThreadPoolExecutor(max_workers=sample_count)
        futures = [executor.submit(sample) for _ in range(sample_count)]
        done, pending = concurrent.futures.wait(futures, timeout=sample_timeout)
        for future in pending:
            future.cancel()
        executor.shutdown(wait=False, cancel_futures=True)
        responses: list[tuple[str, str]] = []
        for future in done:
            try:
                responses.append(future.result())
            except Exception as error:  # noqa: BLE001 - one failed vote must not fail the batch
                responses.append(("", f"sample_error:{type(error).__name__}"))

        samples: list[list[VisualAction]] = []
        sources: list[str] = []
        failures: list[str] = []
        for content, diagnostic in responses:
            if not content:
                failures.append(diagnostic)
                continue
            actions = self._parse_visual_actions(image, content)
            if actions:
                samples.append(actions)
                sources.append(diagnostic)
            else:
                failures.append(self.visual_diagnostic())
        consensus = self._visual_action_consensus(samples, sample_count)
        if consensus:
            return consensus
        failure = failures[-1] if failures else "coordinate_disagreement"
        votes = "|".join(self._visual_action_summary(actions) for actions in samples)
        source_summary = "|".join(sources)
        self._diagnostics.visual = (
            f"consensus_rejected:completed={len(done)}/{sample_count}:"
            f"valid={len(samples)}/{sample_count}:"
            f"votes={votes}:sources={source_summary}:last={failure}"
        )[:1000]
        return []

    def _visual_action_consensus(
        self,
        samples: list[list[VisualAction]],
        sample_count: int,
    ) -> list[VisualAction]:
        valid_count = len(samples)
        required = 1 if sample_count == 1 else max(2, valid_count // 2 + 1)
        grouped: dict[tuple[str, ...], list[list[VisualAction]]] = {}
        for actions in samples:
            signature = tuple(action.type for action in actions)
            grouped.setdefault(signature, []).append(actions)
        if not grouped:
            return []
        signature, candidates = max(grouped.items(), key=lambda item: len(item[1]))
        if len(candidates) < required:
            return []

        action_vectors = list(
            zip(candidates, map(self._visual_action_vector, candidates), strict=True)
        )
        cluster: tuple[tuple[list[VisualAction], list[float]], ...] = ()
        for size in range(len(action_vectors), required - 1, -1):
            qualifying = [
                candidate
                for candidate in itertools.combinations(action_vectors, size)
                if self._visual_vector_spread([vector for _, vector in candidate]) <= 0.06
            ]
            if qualifying:
                cluster = min(
                    qualifying,
                    key=lambda candidate: self._visual_vector_spread(
                        [vector for _, vector in candidate]
                    ),
                )
                break
        if not cluster:
            return []
        clustered_vectors = [vector for _, vector in cluster]
        median_vector = [
            statistics.median(values) for values in zip(*clustered_vectors, strict=True)
        ]
        result = self._visual_actions_from_vector(signature, median_vector)
        spread = max(
            max(abs(value - median) for value, median in zip(vector, median_vector, strict=True))
            for vector in clustered_vectors
        )
        self._diagnostics.visual = (
            f"consensus:{len(cluster)}/{sample_count}:spread={spread:.3f}:" + ",".join(signature)
        )
        return result

    def _visual_vector_spread(self, vectors: list[list[float]]) -> float:
        return max(max(values) - min(values) for values in zip(*vectors, strict=True))

    def _visual_action_vector(self, actions: list[VisualAction]) -> list[float]:
        vector: list[float] = []
        for action in actions:
            if action.type == "click" and action.at is not None:
                vector.extend(action.at)
            elif action.type == "drag" and action.start is not None and action.end is not None:
                vector.extend((*action.start, *action.end))
            else:
                raise ValueError("visual action is incomplete")
        return vector

    def _visual_action_summary(self, actions: list[VisualAction]) -> str:
        signature = ",".join(action.type for action in actions)
        coordinates = ",".join(f"{value:.3f}" for value in self._visual_action_vector(actions))
        return f"{signature}[{coordinates}]"

    def _visual_actions_from_vector(
        self,
        signature: tuple[str, ...],
        vector: list[float],
    ) -> list[VisualAction]:
        actions: list[VisualAction] = []
        offset = 0
        for kind in signature:
            if kind == "click":
                actions.append(VisualAction("click", at=(vector[offset], vector[offset + 1])))
                offset += 2
            else:
                actions.append(
                    VisualAction(
                        "drag",
                        start=(vector[offset], vector[offset + 1]),
                        end=(vector[offset + 2], vector[offset + 3]),
                    )
                )
                offset += 4
        return actions

    def _parse_visual_actions(self, image: bytes, content: str) -> list[VisualAction]:
        try:
            marker = re.search(r"ACTIONS\s*=", content, re.IGNORECASE)
            if marker is None:
                self._diagnostics.visual = "response_without_actions"
                return []
            decoded, _ = json.JSONDecoder().raw_decode(content[marker.end() :].lstrip())
            if not isinstance(decoded, list) or not 1 <= len(decoded) <= 4:
                self._diagnostics.visual = "invalid_action_count"
                return []
            width, height = self._visual_image_size(image)
            points = self._visual_action_points(decoded)
            largest = max((max(abs(x), abs(y)) for x, y in points), default=0.0)
            coordinate_mode = (
                "normalized" if largest <= 1 else "percent" if largest <= 100 else "pixel"
            )
            actions: list[VisualAction] = []
            for raw in decoded:
                if not isinstance(raw, dict):
                    raise TypeError("visual action must be an object")
                kind = str(raw.get("type") or "").strip().lower()
                if kind == "click":
                    at = self._visual_point(
                        raw.get("at", raw.get("point")), coordinate_mode, width, height
                    )
                    actions.append(VisualAction("click", at=at))
                    continue
                if kind == "drag":
                    start = self._visual_point(
                        raw.get("from", raw.get("start")), coordinate_mode, width, height
                    )
                    end = self._visual_point(
                        raw.get("to", raw.get("end")), coordinate_mode, width, height
                    )
                    if math.dist(start, end) < 0.025:
                        raise ValueError("visual drag is degenerate")
                    actions.append(VisualAction("drag", start=start, end=end))
                    continue
                raise ValueError("unsupported visual action")
            self._diagnostics.visual = f"actions:{len(actions)}:mode:{coordinate_mode}:" + ",".join(
                action.type for action in actions
            )
            return actions
        except (json.JSONDecodeError, TypeError, ValueError) as error:
            self._diagnostics.visual = f"action_parse_error:{type(error).__name__}"
            return []

    def _visual_image_size(self, image: bytes) -> tuple[float, float]:
        from PIL import Image

        with Image.open(io.BytesIO(image)) as source:
            width, height = source.size
        if width < 1 or height < 1:
            raise ValueError("visual image has invalid dimensions")
        return float(width), float(height)

    def _visual_action_points(self, actions: list[object]) -> list[tuple[float, float]]:
        points: list[tuple[float, float]] = []
        for action in actions:
            if not isinstance(action, dict):
                raise TypeError("visual action must be an object")
            kind = str(action.get("type") or "").strip().lower()
            values = (
                [action.get("at", action.get("point"))]
                if kind == "click"
                else [action.get("from", action.get("start")), action.get("to", action.get("end"))]
            )
            for value in values:
                if not isinstance(value, list | tuple) or len(value) != 2:
                    raise ValueError("visual action point is invalid")
                point = (float(value[0]), float(value[1]))
                if not all(math.isfinite(coordinate) and coordinate >= 0 for coordinate in point):
                    raise ValueError("visual action coordinate is invalid")
                points.append(point)
        return points

    def _visual_point(
        self,
        value: object,
        mode: str,
        width: float,
        height: float,
    ) -> tuple[float, float]:
        if not isinstance(value, list | tuple) or len(value) != 2:
            raise ValueError("visual action point is invalid")
        x, y = float(value[0]), float(value[1])
        if mode == "percent":
            x, y = x / 100, y / 100
        elif mode == "pixel":
            x, y = x / width, y / height
        if not (0 <= x <= 1 and 0 <= y <= 1):
            raise ValueError("visual action coordinate is outside the image")
        return x, y

    def _visual_completion_sync(
        self,
        image: bytes,
        prompt: str,
        *,
        max_tokens: int,
        timeout_seconds: float | None = None,
    ) -> str:
        config = settings()
        api_key = config.public_api_key.strip() or config.captcha_ai_api_key.strip()
        if not (config.captcha_ai_enabled and config.java_base_url.strip() and api_key and image):
            self._diagnostics.visual = "disabled_or_unconfigured"
            return ""
        endpoint = f"{config.java_base_url.rstrip('/')}/multimodal-random/v1/chat/completions"
        data_url = "data:image/png;base64," + base64.b64encode(image).decode("ascii")
        prompt_prefix = config.captcha_ai_prompt_prefix.strip()
        effective_prompt = f"{prompt_prefix}\n\n{prompt}" if prompt_prefix else prompt
        request_body = {
            "model": "random",
            "messages": [
                {
                    "role": "system",
                    "content": (
                        "Return only the requested machine-readable result. "
                        "Do not explain or use Markdown."
                    ),
                },
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": effective_prompt},
                        {"type": "image_url", "image_url": {"url": data_url}},
                    ],
                },
            ],
            "reasoning_effort": "none",
        }
        budget = max(
            1,
            min(
                180,
                config.captcha_ai_timeout_seconds,
                timeout_seconds
                if timeout_seconds is not None
                else config.captcha_ai_timeout_seconds,
            ),
        )
        deadline = time.monotonic() + budget
        for attempt in range(1, 4):
            remaining = deadline - time.monotonic()
            if remaining < 1:
                break
            try:
                response = httpx.post(
                    endpoint,
                    headers={"Authorization": f"Bearer {api_key}"},
                    json=request_body,
                    timeout=max(1, min(remaining, config.captcha_ai_timeout_seconds)),
                )
                headers = getattr(response, "headers", {})
                provider = str(headers.get("X-Any2API-Provider") or "unknown")
                model = str(headers.get("X-Any2API-Model") or "unknown")
                error_type = self._response_error_type(response)
                if error_type == "account_unavailable" and attempt < 3:
                    self._diagnostics.visual = (
                        f"retrying_account_unavailable:provider={provider}:model={model}"
                    )
                    time.sleep(min(random.uniform(0.1, 0.4), max(0, remaining - 1)))
                    continue
                response.raise_for_status()
                payload = response.json()
                content = ((payload.get("choices") or [{}])[0].get("message") or {}).get(
                    "content", ""
                )
                if isinstance(content, list):
                    content = "".join(
                        value if isinstance(value, str) else str(value.get("text") or "")
                        for value in content
                    )
                if not str(content).strip() and attempt < 3:
                    self._diagnostics.visual = (
                        f"retrying_empty_response:provider={provider}:model={model}"
                    )
                    continue
                self._diagnostics.visual = (
                    f"response_received:provider={provider}:model={model}:attempt={attempt}"
                )
                return str(content)
            except httpx.HTTPStatusError as error:
                headers = error.response.headers
                provider = str(headers.get("X-Any2API-Provider") or "unknown")
                model = str(headers.get("X-Any2API-Model") or "unknown")
                self._diagnostics.visual = (
                    f"http_status:{error.response.status_code}:provider={provider}:model={model}"
                )
                return ""
            except (httpx.HTTPError, KeyError, TypeError, ValueError) as error:
                self._diagnostics.visual = f"request_error:{type(error).__name__}"
                return ""
        return ""

    def _response_error_type(self, response: object) -> str:
        if int(getattr(response, "status_code", 200)) < 400:
            return ""
        try:
            payload = response.json()  # type: ignore[attr-defined]
            error = payload.get("error") if isinstance(payload, dict) else None
            return str(error.get("type") or "") if isinstance(error, dict) else ""
        except (TypeError, ValueError):
            return ""

    def solve_slider_ddddocr_variant_sync(
        self,
        background: bytes,
        piece: bytes,
        *,
        simple_target: bool,
        solver_name: str,
        confidence: float,
    ) -> SolverEstimate | None:
        try:
            engine = self._get_ddddocr_slider()
            result = engine.slide_match(piece, background, simple_target=simple_target)
            target = result.get("target") if isinstance(result, dict) else None
            if not target or float(target[0]) < 20:
                return None
            return SolverEstimate(
                solver=solver_name,
                value=float(target[0]),
                confidence=confidence,
                detail=f"simple_target={simple_target}; target={target}",
            )
        except Exception:  # noqa: BLE001 - one detector variant must not abort fusion
            return None

    def solve_slider_recognizer_sync(self, background: bytes) -> SolverEstimate | None:
        return self._solve_slider_recognizer_sync(background)

    def _capability(
        self, module: str, challenge_types: tuple[str, ...], lazy: bool = False
    ) -> dict[str, object]:
        return {
            "available": importlib.util.find_spec(module) is not None,
            "challenge_types": challenge_types,
            "lazy": lazy,
        }

    def _solve_text_sync(self, image: bytes) -> list[SolverEstimate]:
        engine = self._get_ddddocr_text()
        raw = str(engine.classification(image)).strip()
        if not raw:
            return []
        return [SolverEstimate(solver="ddddocr_text", value=raw, confidence=0.75)]

    def _solve_slider_ddddocr_sync(self, background: bytes, piece: bytes) -> SolverEstimate | None:
        try:
            engine = self._get_ddddocr_slider()
            result = engine.slide_match(piece, background, simple_target=False)
            target = result.get("target") if isinstance(result, dict) else None
            if not target:
                return None
            x = float(target[0])
            if x < 20:
                return None
            return SolverEstimate(
                solver="ddddocr_slider",
                value=x,
                confidence=0.82,
                detail=f"target={target}",
            )
        except Exception:  # noqa: BLE001 - third-party detector errors are isolated per estimate
            return None

    def _solve_slider_recognizer_sync(self, background: bytes) -> SolverEstimate | None:
        try:
            recognizer = self._get_recognizer()
            with tempfile.TemporaryDirectory(prefix="any2api-captcha-") as directory:
                source = Path(directory) / "background.png"
                source.write_bytes(background)
                box, confidence = recognizer.identify(source=str(source), show=False)
            x = box[0] if isinstance(box, (list, tuple)) else box.get("x")
            if x is None or float(x) < 20:
                return None
            return SolverEstimate(
                solver="captcha_recognizer",
                value=float(x),
                confidence=max(0.0, min(1.0, float(confidence))),
                detail=f"box={box}",
            )
        except Exception:  # noqa: BLE001 - third-party detector errors are isolated per estimate
            return None

    def _solve_slider_opencv_sync(self, background: bytes, piece: bytes) -> SolverEstimate | None:
        try:
            import cv2
            import numpy as np

            bg = cv2.imdecode(np.frombuffer(background, np.uint8), cv2.IMREAD_UNCHANGED)
            pc = cv2.imdecode(np.frombuffer(piece, np.uint8), cv2.IMREAD_UNCHANGED)
            if bg is None or pc is None:
                return None
            bg_gray = cv2.cvtColor(
                bg, cv2.COLOR_BGRA2GRAY if bg.shape[-1] == 4 else cv2.COLOR_BGR2GRAY
            )
            pc_gray = cv2.cvtColor(
                pc, cv2.COLOR_BGRA2GRAY if pc.shape[-1] == 4 else cv2.COLOR_BGR2GRAY
            )
            bg_edges = cv2.Canny(bg_gray, 80, 180)
            pc_edges = cv2.Canny(pc_gray, 80, 180)
            result = cv2.matchTemplate(bg_edges, pc_edges, cv2.TM_CCOEFF_NORMED)
            _, score, _, location = cv2.minMaxLoc(result)
            if location[0] < 20:
                return None
            return SolverEstimate(
                solver="opencv_template",
                value=float(location[0]),
                confidence=max(0.0, min(1.0, float(score))),
                detail=f"score={score:.3f}",
            )
        except Exception:  # noqa: BLE001 - malformed images and OpenCV errors reject this estimate
            return None

    def _solve_dots_sync(self, image: bytes, color: str) -> SolverEstimate | None:
        try:
            import cv2
            import numpy as np

            source = cv2.imdecode(np.frombuffer(image, np.uint8), cv2.IMREAD_COLOR)
            if source is None:
                return None
            hsv = cv2.cvtColor(source, cv2.COLOR_BGR2HSV)
            ranges = {
                "yellow": ((20, 80, 80), (40, 255, 255)),
                "green": ((35, 50, 50), (90, 255, 255)),
                "orange": ((5, 80, 80), (25, 255, 255)),
                "purple": ((120, 40, 40), (160, 255, 255)),
                "blue": ((90, 50, 50), (130, 255, 255)),
                "red": ((0, 80, 80), (10, 255, 255)),
            }
            low, high = ranges.get(color.lower(), ((0, 60, 60), (180, 255, 255)))
            mask = cv2.inRange(hsv, np.array(low), np.array(high))
            if color.lower() == "red":
                mask = cv2.bitwise_or(
                    mask, cv2.inRange(hsv, np.array((170, 80, 80)), np.array((180, 255, 255)))
                )
            mask = cv2.medianBlur(mask, 5)
            kernel = np.ones((3, 3), np.uint8)
            mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, kernel)
            mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, kernel)
            contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            height, width = source.shape[:2]
            points: list[tuple[float, float, float]] = []
            for contour in contours:
                area = cv2.contourArea(contour)
                if area < max(20, width * height * 0.0003) or area > width * height * 0.08:
                    continue
                moments = cv2.moments(contour)
                if not moments["m00"]:
                    continue
                points.append(
                    (
                        moments["m10"] / moments["m00"] / width,
                        moments["m01"] / moments["m00"] / height,
                        area,
                    )
                )
            if len(points) < 2:
                return None
            remaining = [(x, y) for x, y, _ in sorted(points, key=lambda item: -item[2])[:12]]
            ordered = [remaining.pop(0)]
            while remaining:
                last_x, last_y = ordered[-1]
                index = min(
                    range(len(remaining)),
                    key=lambda item: (
                        (remaining[item][0] - last_x) ** 2 + (remaining[item][1] - last_y) ** 2
                    ),
                )
                ordered.append(remaining.pop(index))
            return SolverEstimate(
                solver="opencv_dots",
                value=ordered,
                confidence=min(0.95, 0.55 + len(ordered) * 0.05),
                detail=f"color={color}; count={len(ordered)}",
            )
        except Exception:  # noqa: BLE001 - malformed images reject only this estimate
            return None

    def _solve_tap_sync(self, targets: bytes, panel: bytes) -> SolverEstimate | None:
        try:
            import cv2
            import numpy as np

            target = cv2.imdecode(np.frombuffer(targets, np.uint8), cv2.IMREAD_COLOR)
            source = cv2.imdecode(np.frombuffer(panel, np.uint8), cv2.IMREAD_COLOR)
            if target is None or source is None:
                return None
            target_gray = cv2.cvtColor(target, cv2.COLOR_BGR2GRAY)
            ink = target_gray < 240
            projection = ink.sum(axis=0)
            threshold = max(1, int(target.shape[0] * 0.08))
            runs: list[tuple[int, int]] = []
            start: int | None = None
            for index, value in enumerate(projection):
                if value >= threshold and start is None:
                    start = index
                elif value < threshold and start is not None:
                    if index - start >= 8:
                        runs.append((start, index))
                    start = None
            if start is not None:
                runs.append((start, target.shape[1]))
            merged: list[list[int]] = []
            for left, right in runs:
                if merged and left - merged[-1][1] < 3:
                    merged[-1][1] = right
                else:
                    merged.append([left, right])
            runs = [(left, right) for left, right in merged if right - left >= 10]
            if len(runs) < 2:
                step = target.shape[1] / 4
                runs = [(int(index * step), int((index + 1) * step)) for index in range(4)]

            icons = []
            for left, right in runs:
                icon = target[:, max(0, left - 1) : min(target.shape[1], right + 1)]
                gray = cv2.cvtColor(icon, cv2.COLOR_BGR2GRAY)
                rows = (gray < 240).sum(axis=1)
                ys = np.where(rows >= max(1, int((right - left) * 0.05)))[0]
                if len(ys) > 2:
                    icon = icon[
                        max(0, int(ys[0]) - 1) : min(target.shape[0], int(ys[-1]) + 2),
                        :,
                    ]
                if min(icon.shape[:2]) >= 8:
                    icons.append(icon)

            source_gray = cv2.cvtColor(source, cv2.COLOR_BGR2GRAY)
            source_edges = cv2.Canny(source_gray, 50, 150)
            points: list[tuple[float, float]] = []
            scores: list[float] = []
            used: list[tuple[int, int, int, int]] = []
            for icon in icons:
                best = None
                for scale in (0.9, 1.0, 1.1, 1.25, 0.75, 1.4):
                    width = max(8, int(icon.shape[1] * scale))
                    height = max(8, int(icon.shape[0] * scale))
                    if height >= source.shape[0] or width >= source.shape[1]:
                        continue
                    resized = cv2.resize(icon, (width, height), interpolation=cv2.INTER_AREA)
                    icon_gray = cv2.cvtColor(resized, cv2.COLOR_BGR2GRAY)
                    icon_edges = cv2.Canny(icon_gray, 50, 150)
                    edge_result = cv2.matchTemplate(source_edges, icon_edges, cv2.TM_CCOEFF_NORMED)
                    gray_result = cv2.matchTemplate(source_gray, icon_gray, cv2.TM_CCOEFF_NORMED)
                    result = 0.55 * edge_result + 0.45 * gray_result
                    for x, y, used_width, used_height in used:
                        result[
                            max(0, y - height // 3) : min(result.shape[0], y + used_height),
                            max(0, x - width // 3) : min(result.shape[1], x + used_width),
                        ] = -1
                    _, score, _, location = cv2.minMaxLoc(result)
                    if best is None or score > best[0]:
                        best = (float(score), location[0], location[1], width, height)
                if best is None or best[0] < 0.25:
                    continue
                score, x, y, width, height = best
                used.append((x, y, width, height))
                points.append(
                    (
                        (x + width / 2) / source.shape[1],
                        (y + height / 2) / source.shape[0],
                    )
                )
                scores.append(score)
            if len(points) < 2 or len(points) != len(icons):
                return None
            return SolverEstimate(
                solver="opencv_tap",
                value=points,
                confidence=sum(scores) / len(scores),
                detail=f"targets={len(points)}",
            )
        except Exception:  # noqa: BLE001 - malformed images reject only this estimate
            return None

    def _get_ddddocr_text(self):
        with self._model_lock:
            if self._ddddocr_text is None:
                import ddddocr

                self._ddddocr_text = ddddocr.DdddOcr(show_ad=False)
            return self._ddddocr_text

    def _get_ddddocr_slider(self):
        with self._model_lock:
            if self._ddddocr_slider is None:
                import ddddocr

                self._ddddocr_slider = ddddocr.DdddOcr(det=False, ocr=False, show_ad=False)
            return self._ddddocr_slider

    def _get_recognizer(self):
        with self._model_lock:
            if self._recognizer is None:
                from captcha_recognizer.slider import Slider

                self._recognizer = Slider()
            return self._recognizer


registry = SolverRegistry()
