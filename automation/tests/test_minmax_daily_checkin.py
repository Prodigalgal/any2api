import json
from contextlib import asynccontextmanager

import pytest

from any2api_automation.providers import minmax
from any2api_automation.providers.minmax import (
    MinmaxAutomationProvider,
    _default_runtime_plan,
)


def test_minmax_runtime_plan_contains_daily_checkin_paths() -> None:
    paths = _default_runtime_plan().active.rules.endpoint_paths

    assert paths["signinStatus"] == "/minimax-cloud/api/v1/signin/status"
    assert paths["signinClaim"] == "/minimax-cloud/api/v1/signin/claim"


def _runtime_plan_payload() -> dict[str, object]:
    rule = _default_runtime_plan().active.rules
    return {
        "active": {
            "providerId": "minmax",
            "revision": 1,
            "rules": {
                "schemaVersion": rule.schema_version,
                "sessionMaxAgeSeconds": rule.session_max_age_seconds,
                "canaryTimeoutSeconds": rule.canary_timeout_seconds,
                "buildAssetMarkers": list(rule.build_asset_markers),
                "discoveryMarkers": {
                    key: list(value) for key, value in rule.discovery_markers.items()
                },
                "capabilities": rule.capabilities,
                "endpointPaths": rule.endpoint_paths,
            },
        },
        "candidate": None,
        "activeBuildId": "",
        "candidateBuildId": "",
    }


def _signin_panel(today_status: int, claimable: bool) -> str:
    days = [
        {
            "day_no": day_no,
            "points": 400 if day_no == 1 else 500,
            "is_today": day_no == 1,
            "status": (today_status if day_no == 1 else 2 if claimable and day_no == 2 else 1),
        }
        for day_no in range(1, 8)
    ]
    return json.dumps({"base_resp": {"status_code": 0}, "data": {"scene": 1, "days": days}})


class _FakeMinmaxTransport:
    def __init__(self, responses: list[dict[str, object]]) -> None:
        self.responses = list(responses)
        self.calls: list[tuple[str, str, str]] = []

    async def request(
        self,
        _credential: dict[str, object],
        method: str,
        path: str,
        body: str,
        _proxy_url: str,
    ) -> dict[str, object]:
        self.calls.append((method, path, body))
        return self.responses.pop(0)


@asynccontextmanager
async def _no_proxy(_payload: dict[str, object]):
    yield ""


@pytest.mark.asyncio
async def test_minmax_daily_checkin_claims_the_current_day(monkeypatch) -> None:
    transport = _FakeMinmaxTransport(
        [
            {
                "status": 200,
                "body": _signin_panel(today_status=2, claimable=True),
                "credential_patch": {"browser_execution_context": {"schema_version": 1}},
            },
            {
                "status": 200,
                "body": json.dumps(
                    {
                        "base_resp": {"status_code": 0},
                        "data": {
                            "claim_id": "claim",
                            "claim_result": 1,
                            "day_no": 1,
                            "points": 400,
                            "expire_at_ms": 1_800_000_000_000,
                        },
                    }
                ),
            },
        ]
    )
    monkeypatch.setattr(minmax, "official_browser_transport", transport)
    monkeypatch.setattr(minmax, "_transport_proxy_lease", _no_proxy)

    result = await MinmaxAutomationProvider().daily_checkin(
        {
            "credential": {"token": "token", "user_id": "user"},
            "runtime_plan": _runtime_plan_payload(),
        }
    )

    assert result["healthy"] is True
    assert result["ready_for_inference"] is False
    assert [call[:2] for call in transport.calls] == [
        ("GET", "/minimax-cloud/api/v1/signin/status"),
        ("POST", "/minimax-cloud/api/v1/signin/claim"),
    ]
    assert transport.calls[1][2] == "{}"
    assert result["metadata_patch"]["minmax_daily_checkin"]["status"] == "CLAIMED"


@pytest.mark.asyncio
async def test_minmax_daily_checkin_is_idempotent_after_current_day_is_claimed(monkeypatch) -> None:
    transport = _FakeMinmaxTransport(
        [{"status": 200, "body": _signin_panel(today_status=3, claimable=False)}]
    )
    monkeypatch.setattr(minmax, "official_browser_transport", transport)
    monkeypatch.setattr(minmax, "_transport_proxy_lease", _no_proxy)

    result = await MinmaxAutomationProvider().daily_checkin(
        {
            "credential": {"token": "token", "user_id": "user"},
            "runtime_plan": _runtime_plan_payload(),
        }
    )

    assert result["healthy"] is True
    assert result["metadata_patch"]["minmax_daily_checkin"]["status"] == "ALREADY_CLAIMED"
    assert len(transport.calls) == 1


@pytest.mark.asyncio
async def test_minmax_daily_checkin_treats_racing_claim_as_idempotent(monkeypatch) -> None:
    transport = _FakeMinmaxTransport(
        [
            {"status": 200, "body": _signin_panel(today_status=2, claimable=True)},
            {
                "status": 200,
                "body": json.dumps(
                    {
                        "base_resp": {"status_code": 0},
                        "data": {
                            "claim_id": "claim",
                            "claim_result": 2,
                            "day_no": 1,
                            "points": 400,
                            "expire_at_ms": 1_800_000_000_000,
                        },
                    }
                ),
            },
        ]
    )
    monkeypatch.setattr(minmax, "official_browser_transport", transport)
    monkeypatch.setattr(minmax, "_transport_proxy_lease", _no_proxy)

    result = await MinmaxAutomationProvider().daily_checkin(
        {
            "credential": {"token": "token", "user_id": "user"},
            "runtime_plan": _runtime_plan_payload(),
        }
    )

    assert result["healthy"] is True
    assert result["metadata_patch"]["minmax_daily_checkin"]["status"] == "ALREADY_CLAIMED"


@pytest.mark.asyncio
async def test_minmax_daily_checkin_does_not_accept_a_protocol_response_without_claim_id(
    monkeypatch,
) -> None:
    transport = _FakeMinmaxTransport(
        [
            {"status": 200, "body": _signin_panel(today_status=2, claimable=True)},
            {
                "status": 200,
                "body": json.dumps(
                    {
                        "base_resp": {"status_code": 0},
                        "data": {
                            "claim_result": 1,
                            "day_no": 1,
                            "points": 400,
                            "expire_at_ms": 1_800_000_000_000,
                        },
                    }
                ),
            },
        ]
    )
    monkeypatch.setattr(minmax, "official_browser_transport", transport)
    monkeypatch.setattr(minmax, "_transport_proxy_lease", _no_proxy)

    result = await MinmaxAutomationProvider().daily_checkin(
        {
            "credential": {"token": "token", "user_id": "user"},
            "runtime_plan": _runtime_plan_payload(),
        }
    )

    assert result["healthy"] is False
    assert result["error_class"] == "minmax_daily_checkin_protocol_changed"
