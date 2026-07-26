import asyncio
import importlib.util
import tempfile
import threading
from pathlib import Path

from ..resources import lanes
from .models import SolverEstimate


class SolverRegistry:
    def __init__(self) -> None:
        self._ddddocr_text = None
        self._ddddocr_slider = None
        self._recognizer = None
        self._model_lock = threading.Lock()

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
            return SolverEstimate(
                solver="ddddocr_slider",
                value=float(target[0]),
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
            return SolverEstimate(
                solver="opencv_template",
                value=float(location[0]),
                confidence=max(0.0, min(1.0, float(score))),
                detail=f"score={score:.3f}",
            )
        except Exception:  # noqa: BLE001 - malformed images and OpenCV errors reject this estimate
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
