"""Apply the verified viewport-edge fix to Arena's pinned Camoufox bundle at build time."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import stat
import tempfile
import zipfile
from pathlib import Path

HANDLER_MEMBER = "chrome/juggler/content/protocol/PageHandler.js"
ORIGINAL_SHA256 = "7474ebd104341cbf87eedf6947f627c984ed07f4d69af97ad33358630387f8b8"
PATCHED_SHA256 = "e7f886861fd10c9312ab105729a14485131f866e616fc77315c1d5fb92f410e4"
ORIGINAL_GUARD = """            // Skip movement that is out of bounds. Must match the endpoint guard
            // below (>=, not >): a point at exactly x==width or y==height fires as
            // an exit event instead of eMouseMove, so the hit-renderer ack never
            // arrives and every later input event hangs behind it forever.
            if (currentX < 0 || currentY < 0 || currentX >= boundingBox.width || currentY >= boundingBox.height)"""
PATCHED_GUARD = """            // Keep intermediate points strictly inside all four viewport edges.
            // Top/left edge points can also lack the renderer acknowledgement.
            // The requested endpoint is still dispatched unchanged below.
            if (currentX <= 0 || currentY <= 0 || currentX >= boundingBox.width || currentY >= boundingBox.height)"""


def patch_handler(source: bytes) -> bytes:
    if hashlib.sha256(source).hexdigest() != ORIGINAL_SHA256:
        raise ValueError("Unexpected Arena Camoufox input source digest")
    if source.count(ORIGINAL_GUARD.encode()) != 1:
        raise ValueError("Arena Camoufox input guard must match exactly once")
    patched = source.replace(ORIGINAL_GUARD.encode(), PATCHED_GUARD.encode(), 1)
    if hashlib.sha256(patched).hexdigest() != PATCHED_SHA256:
        raise ValueError("Unexpected patched Arena Camoufox input digest")
    return patched


def _read_handler(bundle: zipfile.ZipFile) -> bytes:
    if bundle.namelist().count(HANDLER_MEMBER) != 1:
        raise ValueError("Arena Camoufox input member must exist exactly once")
    return bundle.read(HANDLER_MEMBER)


def verify_bundle(path: Path) -> str:
    with zipfile.ZipFile(path) as bundle:
        digest = hashlib.sha256(_read_handler(bundle)).hexdigest()
    if digest != PATCHED_SHA256:
        raise ValueError("Arena Camoufox input patch is not installed")
    return digest


def patch_bundle(path: Path) -> str:
    with zipfile.ZipFile(path) as original:
        source = _read_handler(original)
    if hashlib.sha256(source).hexdigest() == PATCHED_SHA256:
        return PATCHED_SHA256
    patched = patch_handler(source)
    descriptor, temporary_name = tempfile.mkstemp(prefix=".arena-input-", dir=path.parent)
    os.close(descriptor)
    temporary = Path(temporary_name)
    try:
        with zipfile.ZipFile(path) as original, zipfile.ZipFile(temporary, "w") as modified:
            modified.comment = original.comment
            for member in original.infolist():
                content = patched if member.filename == HANDLER_MEMBER else original.read(member)
                modified.writestr(member, content)
        verify_bundle(temporary)
        temporary.chmod(stat.S_IMODE(path.stat().st_mode))
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)
    return PATCHED_SHA256


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle", type=Path)
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    path = args.bundle
    if path is None:
        from camoufox.pkgman import launch_path

        path = Path(launch_path()).parent / "omni.ja"
    digest = verify_bundle(path) if args.verify else patch_bundle(path)
    print(json.dumps({"arena_input_patch": "viewport_edges", "handler_sha256": digest}))


if __name__ == "__main__":
    main()
