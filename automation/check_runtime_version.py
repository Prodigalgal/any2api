"""Verify browser dependencies and the installed Automation application artifact."""

import argparse
import hashlib
import importlib.metadata
import json
import tomllib
from pathlib import Path


def verify_dependency_manifest(expected: Path, installed: Path) -> str:
    expected_digest = hashlib.sha256(expected.read_bytes()).hexdigest()
    installed_digest = hashlib.sha256(installed.read_bytes()).hexdigest()
    if installed_digest != expected_digest:
        raise SystemExit(
            "Browser runtime dependency mismatch: build the runtime from the current uv.lock "
            "and pass its immutable digest as BROWSER_RUNTIME_IMAGE"
        )
    return expected_digest


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dependencies", type=Path)
    arguments = parser.parse_args()
    if arguments.dependencies:
        dependency_digest = verify_dependency_manifest(
            arguments.dependencies, Path("/opt/any2api-runtime/requirements.txt")
        )
        print(json.dumps({"status": "PASS", "dependency_sha256": dependency_digest}))
        return

    from any2api_automation.main import app

    project = tomllib.loads(Path(__file__).with_name("pyproject.toml").read_text(encoding="utf-8"))[
        "project"
    ]
    expected = project["version"]
    installed = importlib.metadata.version(project["name"])
    if installed != expected or app.version != expected:
        raise SystemExit(
            f"Automation version mismatch: project={expected}, installed={installed}, API={app.version}"
        )
    print(
        json.dumps(
            {"status": "PASS", "version": expected, "installed": installed, "API": app.version}
        )
    )


if __name__ == "__main__":
    main()
