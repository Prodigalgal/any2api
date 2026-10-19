"""Keep browser cleanup independent from cancellable provider worker threads."""

from __future__ import annotations

import asyncio
import contextvars
import os
import threading
import time
import weakref
from collections.abc import Callable, Iterator
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from dataclasses import dataclass, field
from typing import Any


class BlockingWorkCancelled(RuntimeError):
    pass


@dataclass(eq=False)
class _Work:
    cancelled: threading.Event = field(default_factory=threading.Event)
    cancelled_at: float | None = None

    def cancel(self) -> None:
        self.cancelled_at = time.monotonic()
        self.cancelled.set()


_background = contextvars.ContextVar("background_blocking_work", default=False)
_current_work: contextvars.ContextVar[_Work | None] = contextvars.ContextVar(
    "current_blocking_work", default=None
)
_wait_deadline: contextvars.ContextVar[float | None] = contextvars.ContextVar(
    "browser_wait_deadline", default=None
)


@contextmanager
def background_work() -> Iterator[None]:
    token = _background.set(True)
    try:
        yield
    finally:
        _background.reset(token)


def is_background_work() -> bool:
    return _background.get()


def check_blocking_work() -> None:
    work = _current_work.get()
    if work is not None and work.cancelled.is_set():
        raise BlockingWorkCancelled("provider worker was cancelled")


@contextmanager
def browser_wait_deadline(seconds: float) -> Iterator[None]:
    token = _wait_deadline.set(time.monotonic() + seconds)
    try:
        yield
    finally:
        _wait_deadline.reset(token)


def blocking_wait_timeout(maximum: float) -> float:
    check_blocking_work()
    deadline = _wait_deadline.get()
    remaining = maximum if deadline is None else min(maximum, deadline - time.monotonic())
    if remaining <= 0:
        raise TimeoutError("browser process capacity acquisition timed out")
    return remaining


class BlockingWorkPool:
    def __init__(self, capacity: int, *, name: str, isolated: bool = True) -> None:
        self.capacity = capacity
        self._executor = (
            ThreadPoolExecutor(max_workers=capacity, thread_name_prefix=name) if isolated else None
        )
        self._limiters: weakref.WeakKeyDictionary[asyncio.AbstractEventLoop, asyncio.Semaphore] = (
            weakref.WeakKeyDictionary()
        )
        self._lock = threading.Lock()
        self._work: set[_Work] = set()
        self._waiting = 0
        self._completed = 0

    async def run[T](self, function: Callable[..., T], *args: Any, **kwargs: Any) -> T:
        loop = asyncio.get_running_loop()
        limiter = None
        if self._executor is not None:
            limiter = self._limiters.setdefault(loop, asyncio.Semaphore(self.capacity))
            with self._lock:
                self._waiting += 1
            try:
                await limiter.acquire()
            finally:
                with self._lock:
                    self._waiting -= 1
        work = _Work()
        with self._lock:
            self._work.add(work)
        context = contextvars.copy_context()

        def invoke() -> T:
            token = _current_work.set(work)
            try:
                check_blocking_work()
                return function(*args, **kwargs)
            finally:
                _current_work.reset(token)
                with self._lock:
                    self._work.remove(work)
                    self._completed += 1
                if limiter is not None and not loop.is_closed():
                    try:
                        loop.call_soon_threadsafe(limiter.release)
                    except RuntimeError:
                        if not loop.is_closed():
                            raise

        try:
            future = loop.run_in_executor(self._executor, context.run, invoke)
        except BaseException:
            with self._lock:
                self._work.remove(work)
            if limiter is not None:
                limiter.release()
            raise
        try:
            return await asyncio.shield(future)
        except asyncio.CancelledError:
            # The thread still owns its admission until invoke's finally runs.
            work.cancel()
            future.add_done_callback(_consume_exception)
            raise

    def snapshot(self) -> dict[str, int | float]:
        with self._lock:
            cancelled = [work.cancelled_at for work in self._work if work.cancelled_at is not None]
            return {
                "capacity": self.capacity,
                "in_flight": len(self._work),
                "waiting": self._waiting,
                "completed": self._completed,
                "cancelled_in_flight": len(cancelled),
                "oldest_cancelled_seconds": (
                    round(time.monotonic() - min(cancelled), 3) if cancelled else 0
                ),
            }


def _consume_exception(future: asyncio.Future[Any]) -> None:
    if not future.cancelled():
        future.exception()


_foreground_pool = BlockingWorkPool(
    min(32, (getattr(os, "process_cpu_count", os.cpu_count)() or 1) + 4),
    name="provider",
    isolated=False,
)
_background_pool = BlockingWorkPool(2, name="provider-background")
_cleanup_pool = BlockingWorkPool(2, name="browser-cleanup")


async def run_blocking[T](function: Callable[..., T], *args: Any, **kwargs: Any) -> T:
    pool = _background_pool if is_background_work() else _foreground_pool
    return await pool.run(function, *args, **kwargs)


async def run_cleanup[T](function: Callable[..., T], *args: Any, **kwargs: Any) -> T:
    task = asyncio.create_task(_cleanup_pool.run(function, *args, **kwargs))
    try:
        return await asyncio.shield(task)
    except asyncio.CancelledError:
        await task
        raise


def worker_snapshot() -> dict[str, dict[str, int | float]]:
    return {
        "foreground": _foreground_pool.snapshot(),
        "background": _background_pool.snapshot(),
        "cleanup": _cleanup_pool.snapshot(),
    }


def workers_healthy(cancelled_grace_seconds: float) -> bool:
    return all(
        pool["oldest_cancelled_seconds"] < cancelled_grace_seconds
        for pool in worker_snapshot().values()
    )
