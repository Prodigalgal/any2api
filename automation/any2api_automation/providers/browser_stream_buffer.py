"""Bound page-binding queues without discarding already buffered output."""

from __future__ import annotations

import asyncio
import json
from typing import Any

from ..config import settings


class BrowserStreamBuffer(asyncio.Queue[dict[str, Any]]):
    def __init__(self, maximum_bytes: int | None = None, maximum_events: int = 4096) -> None:
        super().__init__()
        self._maximum_bytes = (
            settings().browser_transport_max_buffered_bytes
            if maximum_bytes is None
            else maximum_bytes
        )
        if self._maximum_bytes <= 0 or maximum_events <= 0:
            raise ValueError("browser stream buffer limits must be positive")
        self._maximum_events = maximum_events
        self._buffered_bytes = 0

    def _put(self, event: dict[str, Any]) -> None:
        size = len(json.dumps(event, ensure_ascii=False, separators=(",", ":")).encode("utf8"))
        terminal_control = event.get("type") in {"status", "error", "done"}
        if not terminal_control and (
            self._buffered_bytes + size > self._maximum_bytes
            or self.qsize() >= self._maximum_events
        ):
            # The producer fails explicitly; terminal controls retain reserved space.
            raise BufferError("browser stream buffer limit exceeded")
        self._queue.append((event, size))
        self._buffered_bytes += size

    def _get(self) -> dict[str, Any]:
        event, size = self._queue.popleft()
        self._buffered_bytes -= size
        return event
