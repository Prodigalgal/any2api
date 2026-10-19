"""Catch stale base images and preserve dependency caching across app releases."""

import importlib.util
import re
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
AUTOMATION = ROOT / "automation"


def runtime_checker():
    specification = importlib.util.spec_from_file_location(
        "check_runtime_version", AUTOMATION / "check_runtime_version.py"
    )
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


def export_dependencies(directory: Path) -> bytes:
    command = shutil.which("uv")
    assert command, "uv is required to verify the real production dependency export"
    return subprocess.run(
        [
            command,
            "export",
            "--frozen",
            "--no-dev",
            "--no-emit-project",
            "--no-header",
            "--no-annotate",
            "--format",
            "requirements-txt",
        ],
        cwd=directory,
        check=True,
        capture_output=True,
        timeout=30,
    ).stdout


def test_application_version_does_not_invalidate_the_browser_dependency_layer(tmp_path):
    original = export_dependencies(AUTOMATION)
    project = (AUTOMATION / "pyproject.toml").read_text(encoding="utf8")
    lock = (AUTOMATION / "uv.lock").read_text(encoding="utf8")
    (tmp_path / "pyproject.toml").write_text(
        re.sub(r'^version = "[^"]+"', 'version = "99.0.0"', project, count=1, flags=re.MULTILINE),
        encoding="utf8",
    )
    (tmp_path / "uv.lock").write_text(
        re.sub(
            r'(name = "any2api-automation"\nversion = )"[^"]+"',
            r'\g<1>"99.0.0"',
            lock,
            count=1,
        ),
        encoding="utf8",
    )
    assert export_dependencies(tmp_path) == original
    assert b"any2api-automation" not in original
    assert b"pytest==" not in original


def test_stale_browser_dependencies_block_the_application_build(tmp_path):
    expected = tmp_path / "expected.txt"
    installed = tmp_path / "installed.txt"
    dependencies = export_dependencies(AUTOMATION)
    expected.write_bytes(dependencies)
    installed.write_bytes(dependencies)
    checker = runtime_checker()
    assert len(checker.verify_dependency_manifest(expected, installed)) == 64

    installed.write_bytes(dependencies.replace(b"patchright==1.64.1", b"patchright==1.61.2"))
    with pytest.raises(SystemExit, match="Browser runtime dependency mismatch"):
        checker.verify_dependency_manifest(expected, installed)


@pytest.mark.parametrize(
    "filename", ["Dockerfile", "Dockerfile.arena", "Dockerfile.deepseek", "Dockerfile.qwen"]
)
def test_every_automation_image_requires_a_matching_explicit_browser_runtime(filename):
    dockerfile = (AUTOMATION / filename).read_text(encoding="utf8")
    assert dockerfile.startswith("ARG BROWSER_RUNTIME_IMAGE\nFROM ${BROWSER_RUNTIME_IMAGE}\n")
    assert "pyproject.toml uv.lock check_runtime_version.py" in dockerfile
    assert "check_runtime_version.py --dependencies" in dockerfile
    assert dockerfile.index("--dependencies") < dockerfile.index("uv pip install")
    assert "uv pip check --python /app/.venv/bin/python" in dockerfile


def test_release_build_consumes_its_own_browser_runtime_digest():
    workflow = (ROOT / ".github/workflows/build-and-deploy.yml").read_text(encoding="utf8")
    runtime_job = workflow.split("  build-browser-runtime:\n", 1)[1].split("  build-images:", 1)[0]
    app_job = workflow.split("  build-images:\n", 1)[1].split("  update-gitops:", 1)[0]
    assert "if: github.event_name != 'pull_request'" in runtime_job
    assert "@${{ steps.runtime.outputs.digest }}" in runtime_job
    assert "needs: [build-browser-runtime, release-metadata]" in app_job
    assert "BROWSER_RUNTIME_IMAGE=${{ needs.build-browser-runtime.outputs.image }}" in app_job
    assert "${{ github.run_id }}-${{ github.run_attempt }}" in workflow
    standalone = (ROOT / ".github/workflows/browser-runtime.yml").read_text(encoding="utf8")
    assert "uses: ./.github/workflows/build-and-deploy.yml" in standalone
    assert "browser_runtime: true" in standalone
    assert "deploy: false" in standalone
