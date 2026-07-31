from __future__ import annotations

import re
import secrets
import time
from pathlib import Path

from ..config import settings


def record_captcha_artifact(label: str, image: bytes | None) -> str:
    config = settings()
    directory = config.captcha_diagnostics_dir.strip()
    if not directory or not image:
        return ""
    safe_label = re.sub(r"[^a-z0-9_-]+", "-", label.strip().lower()).strip("-")
    if not safe_label:
        safe_label = "captcha"
    target = Path(directory)
    target.mkdir(parents=True, exist_ok=True, mode=0o700)
    name = f"{safe_label}-{time.time_ns()}-{secrets.token_hex(3)}.png"
    artifact = target / name
    artifact.write_bytes(image)
    artifact.chmod(0o600)
    _prune(target, max(10, config.captcha_diagnostics_max_files))
    return name


def _prune(directory: Path, maximum: int) -> None:
    artifacts = sorted(
        (path for path in directory.glob("*.png") if path.is_file()),
        key=lambda path: path.stat().st_mtime_ns,
        reverse=True,
    )
    for artifact in artifacts[maximum:]:
        artifact.unlink(missing_ok=True)
