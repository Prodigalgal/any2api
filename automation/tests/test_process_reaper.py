import os
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from any2api_automation.browser_budget import BrowserProcessBudget
from any2api_automation.lifecycle.process_reaper import (
    BrowserProcessReaper,
)
from any2api_automation.main import app
from any2api_automation.process_identity import clock_ticks_per_second


def _create_mock_proc_entry(
    proc_dir: Path,
    pid: int,
    ppid: int,
    cmdline: str,
    mtime_offset_seconds: float = 0.0,
) -> Path:
    entry = proc_dir / str(pid)
    entry.mkdir(parents=True, exist_ok=True)
    # cmdline is null-delimited
    (entry / "cmdline").write_bytes(
        b"\x00".join(part.encode() for part in cmdline.split()) + b"\x00"
    )
    (entry / "status").write_text(f"Name:\ttest\nPPid:\t{ppid}\nState:\tS\n", encoding="utf-8")
    mtime = (entry.stat().st_mtime if entry.exists() else 1000.0) - mtime_offset_seconds
    os.utime(entry, (mtime, mtime))
    (proc_dir / "uptime").write_text("10000.0 0.0")
    start_ticks = int((10000 - mtime_offset_seconds) * clock_ticks_per_second())
    fields = ["S", str(ppid), *["0"] * 17, str(start_ticks)]
    (entry / "stat").write_text(f"{pid} (browser process) " + " ".join(fields))
    return entry


def test_process_reaper_discover_processes(tmp_path: Path):
    proc_dir = tmp_path / "proc"
    proc_dir.mkdir()

    # 1: init
    _create_mock_proc_entry(proc_dir, 1, 0, "/sbin/init")
    # 7: python app
    _create_mock_proc_entry(proc_dir, 7, 1, "/app/.venv/bin/python -m uvicorn")
    # 100: orphan camoufox
    _create_mock_proc_entry(
        proc_dir,
        100,
        1,
        "/opt/camoufox/camoufox-bin -no-remote -headless",
        mtime_offset_seconds=50.0,
    )
    # 101: orphan node playwright driver
    _create_mock_proc_entry(
        proc_dir,
        101,
        1,
        "node /path/to/cli.js run-driver",
        mtime_offset_seconds=50.0,
    )
    # 102: normal active browser
    _create_mock_proc_entry(
        proc_dir,
        102,
        7,
        "/opt/camoufox/camoufox-bin -no-remote -headless",
        mtime_offset_seconds=30.0,
    )
    # 103: stalled browser
    _create_mock_proc_entry(
        proc_dir,
        103,
        7,
        "/opt/camoufox/camoufox-bin -no-remote -headless",
        mtime_offset_seconds=800.0,
    )

    reaper = BrowserProcessReaper(proc_path=proc_dir)
    discovered = reaper.discover_processes()
    pids = {p.pid for p in discovered}
    assert pids == {100, 101, 102, 103}

    p100 = next(p for p in discovered if p.pid == 100)
    assert p100.ppid == 1
    assert p100.is_browser is True

    p101 = next(p for p in discovered if p.pid == 101)
    assert p101.ppid == 1
    assert p101.is_driver is True


def test_process_reaper_sweep_orphans_and_stalled(tmp_path: Path, monkeypatch):
    proc_dir = tmp_path / "proc"
    proc_dir.mkdir()

    # 100: orphan camoufox
    _create_mock_proc_entry(
        proc_dir,
        100,
        1,
        "/opt/camoufox/camoufox-bin -no-remote",
        mtime_offset_seconds=50.0,
    )
    # 101: orphan driver
    _create_mock_proc_entry(
        proc_dir,
        101,
        1,
        "node /path/to/cli.js run-driver",
        mtime_offset_seconds=50.0,
    )
    # 102: normal browser (<600s, ppid=7)
    _create_mock_proc_entry(
        proc_dir,
        102,
        7,
        "/opt/camoufox/camoufox-bin -no-remote",
        mtime_offset_seconds=30.0,
    )
    # 103: stalled browser (>600s, ppid=7)
    _create_mock_proc_entry(
        proc_dir,
        103,
        7,
        "/opt/camoufox/camoufox-bin -no-remote",
        mtime_offset_seconds=700.0,
    )

    killed_pids: list[int] = []

    def mock_kill(pid: int) -> bool:
        killed_pids.append(pid)
        return True

    reaper = BrowserProcessReaper(proc_path=proc_dir)
    monkeypatch.setattr(reaper, "_kill_process", mock_kill)

    reaped = reaper.sweep(max_lifetime_seconds=600.0)
    assert reaped == 3
    assert set(killed_pids) == {100, 101, 103}

    snapshot = reaper.snapshot()
    assert snapshot["total_sweeps"] == 1
    assert snapshot["total_orphans_reaped"] == 2
    assert snapshot["total_stalled_reaped"] == 1
    assert snapshot["last_sweep_reaped"] == 3


def test_process_reaper_nonexistent_proc_dir(tmp_path: Path):
    non_existent = tmp_path / "non_existent_proc"
    reaper = BrowserProcessReaper(proc_path=non_existent)
    assert reaper.discover_processes() == []
    assert reaper.sweep() == 0


def test_owned_process_tree_survives_age_limit_until_owner_releases(tmp_path, monkeypatch):
    _create_mock_proc_entry(tmp_path, 100, 7, "node /driver/cli.js run-driver", 1200)
    _create_mock_proc_entry(tmp_path, 101, 100, "/opt/camoufox/camoufox-bin", 1100)
    _create_mock_proc_entry(tmp_path, 102, 1, "/opt/camoufox/camoufox-bin", 50)
    budget = BrowserProcessBudget(1)
    owner = budget.acquire_sync("active-session")
    owner.track_process(100, proc_path=tmp_path)
    reaper = BrowserProcessReaper(proc_path=tmp_path, budget=budget)
    killed = []
    monkeypatch.setattr(reaper, "_kill_process", lambda pid: killed.append(pid) or True)
    assert reaper.sweep(max_lifetime_seconds=600) == 1
    assert killed == [102]
    owner.release()
    owner.release()
    assert not budget.owned_processes()
    assert reaper.sweep(max_lifetime_seconds=600) == 3
    assert {100, 101}.issubset(killed)


def test_process_identity_rechecked_before_termination(tmp_path, monkeypatch):
    _create_mock_proc_entry(tmp_path, 100, 1, "/opt/camoufox/camoufox-bin", 800)
    reaper = BrowserProcessReaper(proc_path=tmp_path)
    snapshot = reaper.discover_processes()
    _create_mock_proc_entry(tmp_path, 100, 7, "/opt/camoufox/camoufox-bin", 1)
    monkeypatch.setattr(reaper, "discover_processes", lambda: snapshot)
    killed = []
    monkeypatch.setattr(reaper, "_kill_process", lambda pid: killed.append(pid) or True)
    assert reaper.sweep() == 0
    assert killed == []


def test_reused_pid_does_not_inherit_browser_ownership(tmp_path, monkeypatch):
    _create_mock_proc_entry(tmp_path, 100, 7, "/opt/camoufox/camoufox-bin", 1200)
    budget = BrowserProcessBudget(1)
    with budget.acquire_sync("old-owner") as owner:
        owner.track_process(100, proc_path=tmp_path)
        _create_mock_proc_entry(tmp_path, 100, 1, "/opt/camoufox/camoufox-bin", 10)
        reaper = BrowserProcessReaper(proc_path=tmp_path, budget=budget)
        killed = []
        monkeypatch.setattr(reaper, "_kill_process", lambda pid: killed.append(pid) or True)
        assert reaper.sweep() == 1
        assert killed == [100]


def test_process_age_uses_kernel_start_time_and_arguments_do_not_mark_browsers(tmp_path):
    entry = _create_mock_proc_entry(tmp_path, 100, 7, "/opt/camoufox/camoufox-bin", 10)
    os.utime(entry, (1, 1))
    _create_mock_proc_entry(tmp_path, 101, 1, "/usr/bin/python /work/firefox/cleanup.py", 800)
    reaper = BrowserProcessReaper(proc_path=tmp_path)
    processes = reaper.discover_processes()
    assert len(processes) == 1
    assert processes[0].elapsed_seconds == pytest.approx(10)
    assert reaper.sweep() == 0


def test_pid_reused_after_sigterm_is_not_sent_sigkill(tmp_path, monkeypatch):
    import signal

    _create_mock_proc_entry(tmp_path, 100, 1, "/opt/camoufox/camoufox-bin", 800)
    sent = []

    def kill(pid, sig):
        sent.append((pid, sig))
        _create_mock_proc_entry(tmp_path, pid, 7, "/usr/bin/python", 1)

    monkeypatch.setattr(os, "kill", kill)
    reaper = BrowserProcessReaper(proc_path=tmp_path)
    assert reaper._kill_process(100)
    assert sent == [(100, signal.SIGTERM)]


@pytest.mark.asyncio
async def test_process_reaper_start_and_stop(tmp_path: Path, monkeypatch):
    proc_dir = tmp_path / "proc"
    proc_dir.mkdir()
    reaper = BrowserProcessReaper(proc_path=proc_dir)

    sweep_called = False

    def mock_sweep(**kwargs):
        nonlocal sweep_called
        sweep_called = True
        return 0

    monkeypatch.setattr(reaper, "sweep", mock_sweep)
    await reaper.start()
    assert sweep_called is True
    await reaper.stop()
    assert reaper._task is None


def test_capabilities_endpoint_includes_reaper_stats():
    client = TestClient(app)
    response = client.get("/internal/v1/capabilities")
    assert response.status_code == 200
    data = response.json()
    assert "resources" in data
    assert "process_reaper" in data["resources"]
    reaper_info = data["resources"]["process_reaper"]
    assert "total_sweeps" in reaper_info
    assert "total_orphans_reaped" in reaper_info
    assert "total_stalled_reaped" in reaper_info


@pytest.mark.asyncio
async def test_startup_sweep_keeps_health_responsive(tmp_path: Path, monkeypatch):
    import asyncio
    import time

    from httpx import ASGITransport, AsyncClient

    reaper = BrowserProcessReaper(proc_path=tmp_path)

    def slow_sweep(**kwargs):
        time.sleep(0.25)
        return 0

    monkeypatch.setattr(reaper, "sweep", slow_sweep)
    started = time.monotonic()
    starting = asyncio.create_task(reaper.start())
    try:
        await asyncio.sleep(0)
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
            response = await client.get("/health/live")
        assert response.status_code == 200
        assert time.monotonic() - started < 0.15
    finally:
        await starting
        await reaper.stop()


@pytest.mark.asyncio
async def test_periodic_sweep_keeps_health_responsive_and_shutdown_waits(
    tmp_path: Path, monkeypatch
):
    import asyncio
    import importlib
    import threading
    import time
    from types import SimpleNamespace

    from httpx import ASGITransport, AsyncClient

    entered = threading.Event()
    release = threading.Event()
    finished = threading.Event()
    reaper = BrowserProcessReaper(proc_path=tmp_path)

    def slow_sweep(**kwargs):
        entered.set()
        release.wait(timeout=2)
        finished.set()
        return 0

    monkeypatch.setattr(reaper, "sweep", slow_sweep)
    monkeypatch.setattr(
        importlib.import_module("any2api_automation.lifecycle.process_reaper"),
        "settings",
        lambda: SimpleNamespace(browser_reaper_interval_seconds=5),
    )
    reaper._task = asyncio.create_task(reaper._run_loop())
    stopping = None
    try:
        assert await asyncio.to_thread(entered.wait, 7)
        started = time.monotonic()
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
            response = await client.get("/health/live")
        assert response.status_code == 200
        assert time.monotonic() - started < 0.15
        stopping = asyncio.create_task(reaper.stop())
        await asyncio.sleep(0.01)
        assert not stopping.done()
        assert not finished.is_set()
    finally:
        release.set()
        if stopping is not None:
            await stopping
        else:
            await reaper.stop()
    assert finished.is_set()
    assert reaper._task is None
    assert reaper._sweep_task is None
