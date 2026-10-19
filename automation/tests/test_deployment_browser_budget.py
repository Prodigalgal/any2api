"""Validate the deployed budget through the real settings environment parser."""

import re
from pathlib import Path

from any2api_automation.config import Settings


def test_shared_deployment_environment_applies_the_four_browser_budget(monkeypatch):
    manifest = (
        (Path(__file__).resolve().parents[2] / "deploy/gitops/automation-runtime-resources.yaml")
        .read_text(encoding="utf8")
        .split("---", 1)[0]
    )
    monkeypatch.delenv("ANY2API_AUTOMATION_BROWSER_PROCESS_CAPACITY", raising=False)
    variables = re.findall(r'name:\s*(ANY2API_\S+)\s+value:\s*"(\d+)"', manifest)
    assert variables, "shared browser budget is missing from the deployment patch"
    for name, value in variables:
        monkeypatch.setenv(name, value)
    capacity = Settings().browser_process_capacity
    assert capacity == 4
