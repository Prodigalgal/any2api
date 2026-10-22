import hashlib
import importlib.util
import json
import shutil
import subprocess
import warnings
import zipfile
from pathlib import Path

import pytest

AUTOMATION = Path(__file__).resolve().parents[1]


@pytest.fixture
def patcher():
    specification = importlib.util.spec_from_file_location(
        "arena_input_patch", AUTOMATION / "patch_arena_browser_input.py"
    )
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


@pytest.fixture
def source(patcher, monkeypatch):
    original = (
        "async function move(points, endpoint) {\n"
        "const boundingBox = {width: 200, height: 100};\n"
        "for (const [currentX, currentY] of points) {\n"
        + patcher.ORIGINAL_GUARD
        + "\ncontinue;\nawait sendOne('mousemove', currentX, currentY);\n}\n"
        "await sendOne('mousemove', ...endpoint);\n}\n"
    ).encode()
    expected = original.replace(patcher.ORIGINAL_GUARD.encode(), patcher.PATCHED_GUARD.encode())
    monkeypatch.setattr(patcher, "ORIGINAL_SHA256", hashlib.sha256(original).hexdigest())
    monkeypatch.setattr(patcher, "PATCHED_SHA256", hashlib.sha256(expected).hexdigest())
    return original, expected


def create_bundle(path, patcher, source, *, duplicate=False):
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.comment = b"native browser bundle"
        archive.writestr("unchanged/runtime.js", b"unchanged runtime")
        archive.writestr(patcher.HANDLER_MEMBER, source)
        if duplicate:
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                archive.writestr(patcher.HANDLER_MEMBER, source)


def test_native_trajectory_never_sends_an_unacknowledged_edge_point(patcher, source):
    node = shutil.which("node")
    if node is None:
        pytest.skip("Node is required to execute the native input boundary regression")
    script = (
        patcher.patch_handler(source[0]).decode()
        + """
const sent = [];
globalThis.sendOne = async (type, x, y) => {
  if (x === 0 || y === 0) throw new Error('missing_renderer_ack');
  sent.push([x, y]);
};
move([[44, 0], [0, 22], [-1, 10], [10, -1], [200, 20], [20, 100],
      [40, 1], [40, 1], [38, 1], [44, 0], [80, 20]], [90.5, 30.5])
  .then(() => process.stdout.write(JSON.stringify(sent)))
  .catch(error => {console.error(error.message); process.exitCode = 1});
"""
    )
    result = subprocess.run(
        [node, "-e", script], capture_output=True, text=True, timeout=10, check=False
    )
    assert result.returncode == 0, result.stderr
    assert json.loads(result.stdout) == [[40, 1], [40, 1], [38, 1], [80, 20], [90.5, 30.5]]


def test_bundle_patch_preserves_other_members_and_is_idempotent(tmp_path, patcher, source):
    path = tmp_path / "omni.ja"
    create_bundle(path, patcher, source[0])
    original_mode = path.stat().st_mode
    assert patcher.patch_bundle(path) == patcher.PATCHED_SHA256
    assert patcher.verify_bundle(path) == patcher.PATCHED_SHA256
    with zipfile.ZipFile(path) as archive:
        assert archive.read(patcher.HANDLER_MEMBER) == source[1]
        assert archive.read("unchanged/runtime.js") == b"unchanged runtime"
        assert archive.comment == b"native browser bundle"
        assert archive.namelist() == ["unchanged/runtime.js", patcher.HANDLER_MEMBER]
    assert path.stat().st_mode == original_mode
    first = path.read_bytes()
    assert patcher.patch_bundle(path) == patcher.PATCHED_SHA256
    assert path.read_bytes() == first
    assert not list(tmp_path.glob(".arena-input-*"))


def test_unknown_source_digest_leaves_the_bundle_untouched(tmp_path, patcher):
    path = tmp_path / "omni.ja"
    create_bundle(path, patcher, b"unknown browser version")
    original = path.read_bytes()
    with pytest.raises(ValueError, match="Unexpected .* digest"):
        patcher.patch_bundle(path)
    assert path.read_bytes() == original
    assert not list(tmp_path.glob(".arena-input-*"))


@pytest.mark.parametrize("duplicate", [False, True])
def test_missing_or_duplicate_input_member_is_rejected(tmp_path, patcher, source, duplicate):
    path = tmp_path / "omni.ja"
    if duplicate:
        create_bundle(path, patcher, source[0], duplicate=True)
    else:
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("unrelated.js", b"untouched")
    original = path.read_bytes()
    with pytest.raises(ValueError, match="must exist exactly once"):
        patcher.patch_bundle(path)
    assert path.read_bytes() == original


def test_failed_write_keeps_original_and_removes_own_temporary_file(
    tmp_path, patcher, source, monkeypatch
):
    path = tmp_path / "omni.ja"
    create_bundle(path, patcher, source[0])
    original = path.read_bytes()

    def fail_write(*_args, **_kwargs):
        raise OSError("write failed")

    monkeypatch.setattr(zipfile.ZipFile, "writestr", fail_write)
    with pytest.raises(OSError, match="write failed"):
        patcher.patch_bundle(path)
    assert path.read_bytes() == original
    assert not list(tmp_path.glob(".arena-input-*"))


def test_unpatched_bundle_fails_verification(tmp_path, patcher, source):
    path = tmp_path / "omni.ja"
    create_bundle(path, patcher, source[0])
    with pytest.raises(ValueError, match="not installed"):
        patcher.verify_bundle(path)


def test_unexpected_guard_occurrences_are_rejected(patcher, monkeypatch):
    for source in (b"no guard", (patcher.ORIGINAL_GUARD * 2).encode()):
        monkeypatch.setattr(patcher, "ORIGINAL_SHA256", hashlib.sha256(source).hexdigest())
        with pytest.raises(ValueError, match="must match exactly once"):
            patcher.patch_handler(source)


def test_unexpected_patched_digest_is_rejected_before_writing(
    tmp_path, patcher, source, monkeypatch
):
    path = tmp_path / "omni.ja"
    create_bundle(path, patcher, source[0])
    original = path.read_bytes()
    monkeypatch.setattr(patcher, "PATCHED_SHA256", "0" * 64)
    with pytest.raises(ValueError, match="Unexpected patched .* digest"):
        patcher.patch_bundle(path)
    assert path.read_bytes() == original
    assert not list(tmp_path.glob(".arena-input-*"))


def test_only_arena_build_applies_the_input_fix_before_application_layers():
    arena = (AUTOMATION / "Dockerfile.arena").read_text(encoding="utf8")
    assert arena.index("patch_arena_browser_input.py --verify") < arena.index(
        "COPY --chown=10001:10001 any2api_automation"
    )
    for filename in (
        "Dockerfile",
        "Dockerfile.browser-runtime",
        "Dockerfile.deepseek",
        "Dockerfile.qwen",
    ):
        assert "patch_arena_browser_input" not in (AUTOMATION / filename).read_text(encoding="utf8")
