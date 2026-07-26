import asyncio
from dataclasses import dataclass

from .config import settings


@dataclass(frozen=True)
class ResourceSnapshot:
    realtime_capacity: int
    batch_capacity: int
    ocr_capacity: int
    slider_capacity: int
    recognizer_capacity: int


class ResourceLanes:
    def __init__(self) -> None:
        config = settings()
        self.realtime = asyncio.Semaphore(config.browser_realtime_capacity)
        self.batch = asyncio.Semaphore(config.browser_batch_capacity)
        self.ocr = asyncio.Semaphore(config.captcha_ocr_concurrency)
        self.slider = asyncio.Semaphore(config.captcha_slider_concurrency)
        self.recognizer = asyncio.Semaphore(config.captcha_recognizer_concurrency)
        self.snapshot = ResourceSnapshot(
            realtime_capacity=config.browser_realtime_capacity,
            batch_capacity=config.browser_batch_capacity,
            ocr_capacity=config.captcha_ocr_concurrency,
            slider_capacity=config.captcha_slider_concurrency,
            recognizer_capacity=config.captcha_recognizer_concurrency,
        )


lanes = ResourceLanes()
