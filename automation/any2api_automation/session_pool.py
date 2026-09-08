"""Bounded account ownership for reusable, event-loop-local browser sessions."""

from __future__ import annotations

import asyncio
from collections import OrderedDict
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager
from dataclasses import dataclass
from typing import Generic, TypeVar

T = TypeVar("T")


@dataclass
class SessionSlot(Generic[T]):
    key: str
    value: T | None = None


class AccountSessionPool(Generic[T]):
    """One operation per account; only idle sessions may be evicted."""

    def __init__(self, capacity: int, dispose: Callable[[T], Awaitable[None]]) -> None:
        if capacity < 1:
            raise ValueError("session pool capacity must be positive")
        self._capacity = capacity
        self._dispose = dispose
        self._condition = asyncio.Condition()
        self._slots: OrderedDict[str, SessionSlot[T]] = OrderedDict()
        self._busy: set[str] = set()
        self._closed = False
        self._closing: asyncio.Task[None] | None = None

    @asynccontextmanager
    async def borrow(self, key: str) -> AsyncIterator[SessionSlot[T]]:
        if not key:
            raise ValueError("session account key is required")
        slot, evicted = await self._acquire(key)
        try:
            if evicted is not None:
                await self._dispose_safely(evicted)
            yield slot
        finally:
            async with self._condition:
                self._busy.remove(key)
                if slot.value is None:
                    self._slots.pop(key, None)
                else:
                    self._slots.move_to_end(key)
                self._condition.notify_all()

    async def _acquire(self, key: str) -> tuple[SessionSlot[T], T | None]:
        async with self._condition:
            while True:
                if self._closed:
                    raise RuntimeError("session pool is closed")
                if key in self._slots:
                    if key not in self._busy:
                        self._busy.add(key)
                        return self._slots[key], None
                else:
                    evicted = None
                    if len(self._slots) >= self._capacity:
                        idle = next((item for item in self._slots if item not in self._busy), None)
                        if idle is None:
                            await self._condition.wait()
                            continue
                        evicted = self._slots.pop(idle).value
                    slot: SessionSlot[T] = SessionSlot(key)
                    self._slots[key] = slot
                    self._busy.add(key)
                    return slot, evicted
                await self._condition.wait()

    async def _dispose_safely(self, value: T) -> None:
        task = asyncio.create_task(self._dispose(value))
        try:
            await asyncio.shield(task)
        except asyncio.CancelledError:
            # Releasing the reserved slot before disposal finishes exceeds the capacity bound.
            await task
            raise

    async def close(self) -> None:
        if self._closing is None:
            self._closed = True
            self._closing = asyncio.create_task(self._close())
        await asyncio.shield(self._closing)

    async def _close(self) -> None:
        async with self._condition:
            self._condition.notify_all()
            await self._condition.wait_for(lambda: not self._busy)
            values = [slot.value for slot in self._slots.values() if slot.value is not None]
            self._slots.clear()
        results = await asyncio.gather(
            *(self._dispose(value) for value in values), return_exceptions=True
        )
        errors = [result for result in results if isinstance(result, BaseException)]
        if errors:
            raise BaseExceptionGroup("browser session cleanup failed", errors)
