"""Automated orphan and stalled browser process reaper."""

from __future__ import annotations

import asyncio
import logging
import os
import signal
import time
from contextlib import suppress
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from ..config import settings

logger = logging.getLogger("any2api_automation.process_reaper")

BROWSER_PROCESS_TARGETS: tuple[str, ...] = (
    "camoufox-bin",
    "camoufox",
    "firefox",
    "firefox-bin",
    "patchright",
    "chrome",
    "chromium",
)

DRIVER_PROCESS_SIGNATURES: tuple[str, ...] = (
    "cli.js run-driver",
    "playwright/driver",
)


@dataclass(frozen=True)
class ProcessInfo:
    pid: int
    ppid: int
    cmdline: str
    elapsed_seconds: float
    is_browser: bool
    is_driver: bool


@dataclass
class ReaperStats:
    total_sweeps: int = 0
    total_orphans_reaped: int = 0
    total_stalled_reaped: int = 0
    last_sweep_at: float = 0.0
    last_sweep_reaped: int = 0


class BrowserProcessReaper:
    def __init__(self, proc_path: Path | None = None) -> None:
        self._proc_path = proc_path or Path("/proc")
        self._stats = ReaperStats()
        self._task: asyncio.Task[None] | None = None
        self._sweep_task: asyncio.Task[int] | None = None

    @property
    def stats(self) -> ReaperStats:
        return self._stats

    def snapshot(self) -> dict[str, Any]:
        return {
            "total_sweeps": self._stats.total_sweeps,
            "total_orphans_reaped": self._stats.total_orphans_reaped,
            "total_stalled_reaped": self._stats.total_stalled_reaped,
            "last_sweep_at": self._stats.last_sweep_at,
            "last_sweep_reaped": self._stats.last_sweep_reaped,
        }

    def discover_processes(self) -> list[ProcessInfo]:
        """Discovers browser and driver processes from the /proc filesystem."""
        if not self._proc_path.exists() or not self._proc_path.is_dir():
            return []

        now = time.time()
        discovered: list[ProcessInfo] = []

        try:
            entries = list(self._proc_path.iterdir())
        except OSError:
            return []

        for entry in entries:
            if not entry.name.isdigit():
                continue
            pid = int(entry.name)
            if pid <= 1:
                continue

            try:
                cmdline_bytes = (entry / "cmdline").read_bytes()
                cmdline = cmdline_bytes.replace(b"\x00", b" ").decode("utf-8", "replace").strip()
                if not cmdline:
                    continue

                status_text = (entry / "status").read_text(encoding="utf-8", errors="replace")
                ppid = 0
                for line in status_text.splitlines():
                    if line.startswith("PPid:"):
                        parts = line.split()
                        if len(parts) >= 2 and parts[1].isdigit():
                            ppid = int(parts[1])
                        break

                elapsed = max(0.0, now - entry.stat().st_mtime)

                is_browser = any(target in cmdline for target in BROWSER_PROCESS_TARGETS)
                is_driver = any(sig in cmdline for sig in DRIVER_PROCESS_SIGNATURES)

                if is_browser or is_driver:
                    discovered.append(
                        ProcessInfo(
                            pid=pid,
                            ppid=ppid,
                            cmdline=cmdline,
                            elapsed_seconds=elapsed,
                            is_browser=is_browser,
                            is_driver=is_driver,
                        )
                    )
            except (OSError, ProcessLookupError, PermissionError):
                continue

        return discovered

    def sweep(
        self,
        *,
        max_lifetime_seconds: float | None = None,
    ) -> int:
        """Finds and terminates orphan or stalled browser/driver processes."""
        max_lifetime = (
            max_lifetime_seconds
            if max_lifetime_seconds is not None
            else float(settings().browser_max_process_lifetime_seconds)
        )
        processes = self.discover_processes()
        self._stats.total_sweeps += 1
        self._stats.last_sweep_at = time.time()

        targets: list[tuple[ProcessInfo, str]] = []
        for proc in processes:
            # 1. Orphan check: ppid is 1 (reparented to tini/init)
            if proc.ppid <= 1:
                targets.append((proc, "orphan_reparented_to_init"))
            # 2. Stalled check: lifetime exceeds max_lifetime_seconds
            elif proc.elapsed_seconds >= max_lifetime:
                targets.append((proc, f"stalled_exceeded_lifetime_{int(proc.elapsed_seconds)}s"))

        if not targets:
            self._stats.last_sweep_reaped = 0
            return 0

        reaped_count = 0
        orphans_count = 0
        stalled_count = 0

        for proc, reason in targets:
            logger.warning(
                "terminating residual process pid=%s ppid=%s reason=%s elapsed=%.1fs cmd=%s",
                proc.pid,
                proc.ppid,
                reason,
                proc.elapsed_seconds,
                proc.cmdline[:120],
            )
            terminated = self._kill_process(proc.pid)
            if terminated:
                reaped_count += 1
                if "orphan" in reason:
                    orphans_count += 1
                else:
                    stalled_count += 1

        self._stats.total_orphans_reaped += orphans_count
        self._stats.total_stalled_reaped += stalled_count
        self._stats.last_sweep_reaped = reaped_count

        if reaped_count > 0:
            logger.info(
                "process reaper finished sweep reaped_total=%s orphans=%s stalled=%s",
                reaped_count,
                orphans_count,
                stalled_count,
            )
        return reaped_count

    def _kill_process(self, pid: int) -> bool:
        """Sends SIGTERM then SIGKILL to ensure termination."""
        kill_signal = getattr(signal, "SIGKILL", signal.SIGTERM)
        try:
            os.kill(pid, signal.SIGTERM)
        except (ProcessLookupError, PermissionError):
            return False

        # Wait briefly for process to exit
        time.sleep(0.15)
        try:
            os.kill(pid, 0)
            # Still alive, escalate to SIGKILL
            if kill_signal != signal.SIGTERM:
                os.kill(pid, kill_signal)
        except ProcessLookupError:
            return True
        except PermissionError:
            return True

        return True

    async def start(self) -> None:
        """Starts the periodic background reaper loop."""
        if not settings().browser_reaper_enabled:
            return

        try:
            await self._sweep_async()
        except Exception as exc:  # noqa: BLE001 - the periodic loop can recover
            logger.warning("process reaper startup sweep failed: %s", type(exc).__name__)

        self._task = asyncio.create_task(self._run_loop())

    async def stop(self) -> None:
        """Stops the reaper loop."""
        if self._task is not None:
            self._task.cancel()
            with suppress(asyncio.CancelledError):
                await self._task
            self._task = None
        if self._sweep_task is not None:
            try:
                await self._sweep_task
            except Exception as exc:  # noqa: BLE001 - preserve subsequent resource cleanup
                logger.warning("process reaper shutdown sweep failed: %s", type(exc).__name__)
            finally:
                self._sweep_task = None

    async def _sweep_async(self) -> int:
        # Killing residual processes includes blocking filesystem reads and grace waits.
        # Shield the worker so shutdown can await it before closing browser resources.
        task = asyncio.create_task(asyncio.to_thread(self.sweep))
        self._sweep_task = task
        try:
            return await asyncio.shield(task)
        finally:
            if task.done():
                self._sweep_task = None

    async def _run_loop(self) -> None:
        interval = max(5, settings().browser_reaper_interval_seconds)
        while True:
            try:
                await asyncio.sleep(interval)
                await self._sweep_async()
            except asyncio.CancelledError:
                break
            except Exception as exc:  # noqa: BLE001
                logger.warning("process reaper sweep encountered error: %s", exc)


process_reaper = BrowserProcessReaper()
