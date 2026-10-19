"""Bounded, process-local tunnels; traffic reservations remain request scoped."""

from __future__ import annotations

import asyncio
import logging
import threading
import time
from collections.abc import Callable, Hashable
from contextlib import asynccontextmanager, suppress
from dataclasses import dataclass
from typing import Protocol

from ..blocking_work import run_cleanup

logger = logging.getLogger("any2api_automation.proxy_tunnels")


class TunnelProcess(Protocol):
    def __enter__(self) -> str: ...
    def __exit__(self, *_: object) -> None: ...
    def is_alive(self) -> bool: ...


@dataclass(eq=False)
class _Tunnel[Node: Hashable]:
    node: Node
    process: TunnelProcess
    references: int = 1
    proxy_url: str = ""
    starting: bool = True
    closed: bool = False
    idle_since: float | None = None


class ProxyTunnelLease:
    """Idempotent reference held by an operation or persistent browser session."""

    def __init__(self, proxy_url: str, release: Callable[[], None]) -> None:
        self.proxy_url = proxy_url
        self._release = release
        self._lock = threading.Lock()
        self._released = False

    def release(self) -> None:
        with self._lock:
            if self._released:
                return
            self._released = True
        self._release()


class ProxyTunnelPool[Node: Hashable]:
    def __init__(
        self,
        factory: Callable[[Node], TunnelProcess],
        *,
        capacity: int,
        idle_seconds: float,
        acquire_timeout_seconds: float = 10,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        if capacity < 1 or idle_seconds < 0 or acquire_timeout_seconds <= 0:
            raise ValueError("invalid proxy tunnel pool limits")
        self._factory = factory
        self._capacity = capacity
        self._idle_seconds = idle_seconds
        self._acquire_timeout_seconds = acquire_timeout_seconds
        self._clock = clock
        self._condition = threading.Condition()
        self._tunnels: dict[Node, _Tunnel[Node]] = {}
        self._by_url: dict[str, _Tunnel[Node]] = {}
        self._closed = False
        self._created = 0
        self._reused = 0

    def acquire(self, node: Node) -> ProxyTunnelLease:
        deadline = time.monotonic() + self._acquire_timeout_seconds
        while True:
            discarded: list[_Tunnel[Node]] = []
            with self._condition:
                if self._closed:
                    raise RuntimeError("proxy tunnel pool is closed")
                current = self._tunnels.get(node)
                if current is not None and not current.starting:
                    if self._usable(current):
                        self._reused += 1
                        return self._retain(current)
                    self._detach(current)
                    discarded.append(current)
                if current is not None and current.starting:
                    self._wait(deadline)
                    continue
                for entry in tuple(self._tunnels.values()):
                    if not entry.starting and entry.references == 0 and not self._usable(entry):
                        self._detach(entry)
                        discarded.append(entry)
                if not discarded and len(self._tunnels) >= self._capacity:
                    idle = [entry for entry in self._tunnels.values() if entry.references == 0]
                    if idle:
                        oldest = min(idle, key=lambda entry: entry.idle_since or 0)
                        self._detach(oldest)
                        discarded.append(oldest)
                    else:
                        self._wait(deadline)
                        continue
                if not discarded:
                    tunnel = _Tunnel(node, self._factory(node))
                    self._tunnels[node] = tunnel
            self._close_processes(discarded)
            if discarded:
                continue
            break
        try:
            proxy_url = tunnel.process.__enter__()
        except BaseException:
            with self._condition:
                self._detach(tunnel)
            self._close_processes([tunnel])
            raise
        with self._condition:
            if self._closed:
                self._detach(tunnel)
            else:
                tunnel.proxy_url = proxy_url
                tunnel.starting = False
                self._by_url[proxy_url] = tunnel
                self._created += 1
                self._condition.notify_all()
                return self._lease(tunnel)
        self._close_processes([tunnel])
        raise RuntimeError("proxy tunnel pool closed during startup")

    def retain(self, proxy_url: str) -> ProxyTunnelLease | None:
        with self._condition:
            tunnel = self._by_url.get(proxy_url)
            if tunnel is None:
                return None
            if self._closed or not self._usable(tunnel):
                raise RuntimeError("browser proxy tunnel is unavailable")
            return self._retain(tunnel)

    def reap_idle(self) -> int:
        with self._condition:
            expired = [
                entry
                for entry in self._tunnels.values()
                if not entry.starting and entry.references == 0 and not self._usable(entry)
            ]
            for entry in expired:
                self._detach(entry)
        self._close_processes(expired)
        return len(expired)

    def close(self) -> None:
        with self._condition:
            self._closed = True
            tunnels = [entry for entry in self._tunnels.values() if not entry.starting]
            for entry in tunnels:
                self._detach(entry)
            self._condition.notify_all()
        self._close_processes(tunnels)

    def snapshot(self) -> dict[str, int]:
        with self._condition:
            tunnels = tuple(self._tunnels.values())
            return {
                "capacity": self._capacity,
                "total": len(tunnels),
                "idle": sum(entry.references == 0 for entry in tunnels),
                "references": sum(entry.references for entry in tunnels),
                "created": self._created,
                "reused": self._reused,
            }

    @asynccontextmanager
    async def lifecycle(self):
        with self._condition:
            self._closed = False
        maintenance = asyncio.create_task(self._maintain())
        try:
            yield
        finally:
            maintenance.cancel()
            with suppress(asyncio.CancelledError):
                await maintenance
            await run_cleanup(self.close)

    async def _maintain(self) -> None:
        while True:
            await asyncio.sleep(15)
            reaping = asyncio.create_task(run_cleanup(self.reap_idle))
            try:
                await asyncio.shield(reaping)
            except asyncio.CancelledError:
                await reaping
                raise

    def _usable(self, tunnel: _Tunnel[Node]) -> bool:
        return (
            not tunnel.closed
            and tunnel.process.is_alive()
            and (
                tunnel.references > 0
                or tunnel.idle_since is None
                or self._clock() - tunnel.idle_since < self._idle_seconds
            )
        )

    def _retain(self, tunnel: _Tunnel[Node]) -> ProxyTunnelLease:
        tunnel.references += 1
        tunnel.idle_since = None
        return self._lease(tunnel)

    def _lease(self, tunnel: _Tunnel[Node]) -> ProxyTunnelLease:
        return ProxyTunnelLease(tunnel.proxy_url, lambda: self._release(tunnel))

    def _release(self, tunnel: _Tunnel[Node]) -> None:
        close = False
        with self._condition:
            tunnel.references -= 1
            if tunnel.references == 0 and not tunnel.closed:
                tunnel.idle_since = self._clock()
                if self._closed or not self._usable(tunnel):
                    self._detach(tunnel)
                    close = True
            self._condition.notify_all()
        if close:
            self._close_processes([tunnel])

    def _detach(self, tunnel: _Tunnel[Node]) -> None:
        tunnel.closed = True
        if self._tunnels.get(tunnel.node) is tunnel:
            del self._tunnels[tunnel.node]
        if self._by_url.get(tunnel.proxy_url) is tunnel:
            del self._by_url[tunnel.proxy_url]
        self._condition.notify_all()

    def _wait(self, deadline: float) -> None:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise RuntimeError("proxy tunnel pool acquisition timed out")
        self._condition.wait(timeout=remaining)

    @staticmethod
    def _close_processes(tunnels: list[_Tunnel[Node]]) -> None:
        for tunnel in tunnels:
            try:
                tunnel.process.__exit__()
            except Exception as error:  # noqa: BLE001 - continue closing remaining tunnels
                logger.warning("proxy_tunnel_cleanup_failed error_type=%s", type(error).__name__)
