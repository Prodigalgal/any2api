"""Linux process identities shared by browser owners and residual cleanup."""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class ProcessIdentity:
    pid: int
    start_ticks: int


@dataclass(frozen=True)
class ProcessStat:
    identity: ProcessIdentity
    parent_pid: int


def read_process_stat(pid: int, proc_path: Path = Path("/proc")) -> ProcessStat | None:
    try:
        # comm can contain spaces and parentheses; fields after its closing ')' are fixed.
        fields = (proc_path / str(pid) / "stat").read_text().rsplit(")", 1)[1].split()
        return ProcessStat(ProcessIdentity(pid, int(fields[19])), int(fields[1]))
    except (OSError, ValueError, IndexError):
        return None


def clock_ticks_per_second() -> int:
    sysconf = getattr(os, "sysconf", None)
    return int(sysconf("SC_CLK_TCK")) if sysconf is not None else 100
