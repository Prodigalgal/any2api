import asyncio
import os
from pathlib import Path
from unittest.mock import MagicMock

import pytest
from fastapi.testclient import TestClient

from any2api_automation.lifecycle.process_reaper import (
    BrowserProcessReaper,
    ProcessInfo,
)
from any2api_automation.main import app


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
    (entry / "cmdline").write_bytes(cmdline.encode("utf-8") + b"\x00")
    (entry / "status").write_text(f"Name:\ttest\nPPid:\t{ppid}\nState:\tS\n", encoding="utf-8")
    mtime = (entry.stat().st_mtime if entry.exists() else 1000.0) - mtime_offset_seconds
    os.utime(entry, (mtime, mtime))
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
