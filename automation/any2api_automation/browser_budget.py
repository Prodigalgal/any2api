"""Process-wide budget for browser-backed provider runtimes."""

from __future__ import annotations

import asyncio
import logging
import threading
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Self

from .config import settings

_WAIT_INTERVAL_SECONDS = 0.25


@dataclass(frozen=True)
class BrowserProcessBudgetSnapshot:
    capacity: int
    in_use: int
    available: int


class BrowserProcessLease:
    """Idempotent ownership token for one browser process tree."""

    def __init__(self, budget: BrowserProcessBudget, label: str) -> None:
        self._budget = budget
        self.label = label
        self._released = False
        self._lock = threading.Lock()

    def release(self) -> None:
        with self._lock:
            if self._released:
                return
            self._released = True
        self._budget._release()

    def __enter__(self) -> Self:
        return self

    def __exit__(self, *_: object) -> None:
        self.release()

    async def __aenter__(self) -> Self:
        return self

    async def __aexit__(self, *_: object) -> None:
        self.release()


class BrowserProcessBudget:
    """Shares one bounded browser-process budget across sync and async callers."""

    def __init__(self, capacity: int, *, logger: logging.Logger | None = None) -> None:
        if capacity < 1:
            raise ValueError("browser process capacity must be positive")
        self._capacity = capacity
        self._in_use = 0
        self._condition = threading.Condition()
        self._logger = logger or logging.getLogger("any2api_automation.browser_budget")
        self._async_evictors: list[Callable[[], Awaitable[bool]]] = []
        self._sync_evictors: list[Callable[[], bool]] = []
        self._evictor_lock = threading.Lock()

    def register_evictors(
        self,
        async_evictor: Callable[[], Awaitable[bool]],
        sync_evictor: Callable[[], bool],
    ) -> Callable[[], None]:
        with self._evictor_lock:
            self._async_evictors.append(async_evictor)
            self._sync_evictors.append(sync_evictor)
        removed = False
        removal_lock = threading.Lock()

        def unregister() -> None:
            nonlocal removed
            with removal_lock:
                if removed:
                    return
                removed = True
            with self._evictor_lock:
                if async_evictor in self._async_evictors:
                    self._async_evictors.remove(async_evictor)
                if sync_evictor in self._sync_evictors:
                    self._sync_evictors.remove(sync_evictor)

        return unregister

    def acquire_sync(self, label: str) -> BrowserProcessLease:
        waited_at: float | None = None
        while True:
            with self._condition:
                if self._in_use < self._capacity:
                    self._in_use += 1
                    lease = BrowserProcessLease(self, label)
                    break
                if waited_at is None:
                    waited_at = time.monotonic()
                    self._logger.info(
                        "browser_process_budget_wait label=%s capacity=%s",
                        label,
                        self._capacity,
                    )
            if self._evict_one_sync_idle(label):
                continue
            with self._condition:
                if self._in_use < self._capacity:
                    continue
                self._condition.wait(timeout=_WAIT_INTERVAL_SECONDS)
        self._log_acquired(label, waited_at)
        return lease

    async def acquire_async(self, label: str) -> BrowserProcessLease:
        waited_at: float | None = None
        while True:
            with self._condition:
                if self._in_use < self._capacity:
                    self._in_use += 1
                    lease = BrowserProcessLease(self, label)
                    break
                if waited_at is None:
                    waited_at = time.monotonic()
                    self._logger.info(
                        "browser_process_budget_wait label=%s capacity=%s",
                        label,
                        self._capacity,
                    )
            if await self._evict_one_async_idle(label):
                continue
            await asyncio.sleep(_WAIT_INTERVAL_SECONDS)
        self._log_acquired(label, waited_at)
        return lease

    def snapshot(self) -> BrowserProcessBudgetSnapshot:
        with self._condition:
            in_use = self._in_use
            return BrowserProcessBudgetSnapshot(
                capacity=self._capacity,
                in_use=in_use,
                available=self._capacity - in_use,
            )

    def _release(self) -> None:
        with self._condition:
            if self._in_use < 1:
                raise RuntimeError("browser process budget released without an active lease")
            self._in_use -= 1
            self._condition.notify_all()

    async def _evict_one_async_idle(self, label: str) -> bool:
        with self._evictor_lock:
            evictors = tuple(self._async_evictors)
        for evictor in evictors:
            try:
                if await evictor():
                    self._logger.info("browser_process_budget_evicted label=%s mode=async", label)
                    return True
            except Exception:
                self._logger.exception("browser_process_budget_evictor_failed mode=async")
        return False

    def _evict_one_sync_idle(self, label: str) -> bool:
        with self._evictor_lock:
            evictors = tuple(self._sync_evictors)
        for evictor in evictors:
            try:
                if evictor():
                    self._logger.info("browser_process_budget_evicted label=%s mode=sync", label)
                    return True
            except Exception:
                self._logger.exception("browser_process_budget_evictor_failed mode=sync")
        return False

    def _log_acquired(self, label: str, waited_at: float | None) -> None:
        if waited_at is None:
            return
        self._logger.info(
            "browser_process_budget_acquired label=%s wait_ms=%s",
            label,
            round((time.monotonic() - waited_at) * 1000),
        )


browser_process_budget = BrowserProcessBudget(
    settings().browser_process_capacity,
)
