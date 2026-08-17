from __future__ import annotations

import pytest

from any2api_automation.providers.runtime_rules import (
    build_id,
    parse_runtime_plan,
    runtime_canary,
    successful_canary,
)


def test_runtime_plan_parses_active_and_candidate_revisions() -> None:
    value = _plan()
    value["candidate"] = {
        **value["active"],
        "revision": 2,
    }

    plan = parse_runtime_plan(value, "mimo")

    assert plan.active.revision == 1
    assert plan.candidate is not None
    assert plan.candidate.revision == 2
    assert runtime_canary(plan.candidate, "a" * 64, "passed") == {
        "revision": 2,
        "build_id": "a" * 64,
        "status": "PASSED",
    }


def test_runtime_plan_rejects_executable_unknown_fields() -> None:
    value = _plan()
    value["active"]["rules"]["javascript"] = "fetch('/steal')"

    with pytest.raises(ValueError, match="unsupported fields"):
        parse_runtime_plan(value, "mimo")


def test_build_id_is_order_independent_and_requires_assets() -> None:
    assert build_id(["b.js", "a.js"]) == build_id(["a.js", "b.js", "a.js"])
    with pytest.raises(RuntimeError, match="assets were not found"):
        build_id([])


def test_active_canary_is_reported_only_when_build_changes() -> None:
    value = _plan()
    value["activeBuildId"] = "a" * 64
    plan = parse_runtime_plan(value, "mimo")

    assert successful_canary(plan, plan.active, "a" * 64) is None
    assert successful_canary(plan, plan.active, "b" * 64) == {
        "revision": 1,
        "build_id": "b" * 64,
        "status": "PASSED",
    }


def _plan() -> dict[str, object]:
    return {
        "active": {
            "providerId": "mimo",
            "revision": 1,
            "rules": {
                "schemaVersion": 1,
                "sessionMaxAgeSeconds": 900,
                "canaryTimeoutSeconds": 60,
                "buildAssetMarkers": ["xiaomimimo.com"],
                "discoveryMarkers": {"requestModule": ["chat", "upload"]},
                "capabilities": {"chat": "completions", "models": "getConfig"},
                "endpointPaths": {"chat": "/chat", "models": "/models"},
            },
        },
        "candidate": None,
    }
