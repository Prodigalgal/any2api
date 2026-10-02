"""Verify the installed Automation artifact before publishing its application image."""

import importlib.metadata
import json
import tomllib
from pathlib import Path

from any2api_automation.main import app


def main() -> None:
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
