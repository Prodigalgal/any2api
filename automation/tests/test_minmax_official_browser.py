from unittest.mock import AsyncMock

import pytest

from any2api_automation.providers.minmax_browser import (
    MinmaxOfficialBrowserTransport,
    _filter_storage_state,
    _Session,
    official_bridge_script,
)


def test_minmax_bridge_discovers_the_official_runtime_without_fixed_module_ids() -> None:
    script = official_bridge_script()

    assert "webpackChunk" in script
    assert "x-signature" in script
    assert "hasSearchParamsPath" in script
    assert "return fetch(" in script
    assert "97516" not in script
    assert "I*7Cf" not in script


def test_minmax_storage_injection_rejects_cross_provider_state() -> None:
    result = _filter_storage_state(
        {
            "cookies": [
                {"name": "minmax", "value": "1", "domain": ".minimax.io"},
                {"name": "foreign", "value": "2", "domain": ".example.com"},
            ],
            "origins": [
                {"origin": "https://agent.minimax.io", "localStorage": []},
                {"origin": "https://example.com", "localStorage": []},
            ],
        }
    )

    assert [cookie["name"] for cookie in result["cookies"]] == ["minmax"]
    assert [origin["origin"] for origin in result["origins"]] == ["https://agent.minimax.io"]


@pytest.mark.asyncio
async def test_minmax_stream_persists_context_before_forwarding_an_error() -> None:
    transport = MinmaxOfficialBrowserTransport("https://agent.minimax.io")

    class Page:
        async def evaluate(self, _script: str, payload: dict[str, object]) -> None:
            request_id = str(payload["requestId"])
            transport._emit(
                session,
                {"requestId": request_id, "type": "status", "status": 403},
            )
            transport._emit(
                session,
                {"requestId": request_id, "type": "error", "data": "challenge"},
            )

    session = _Session(
        key="account",
        browser=object(),
        context=object(),
        page=Page(),
        backend="camoufox",
        state_digest="",
        input_digest="",
        proxy_url="",
    )
    transport._session_for = AsyncMock(return_value=session)
    transport._inject_context = AsyncMock()
    transport._credential_patch = AsyncMock(
        return_value={"browser_execution_context": {"schema_version": 1}}
    )

    events = [
        event
        async for event in transport.stream(
            {"token": "token", "user_id": "user"},
            "POST",
            "/archon/api/v1/session/1/message",
            "{}",
            "",
        )
    ]

    assert [event["type"] for event in events] == [
        "status",
        "credential_patch",
        "error",
    ]


@pytest.mark.asyncio
async def test_legacy_minmax_account_persists_the_generated_camoufox_config() -> None:
    class Context:
        async def storage_state(self, *, indexed_db: bool) -> dict[str, object]:
            assert indexed_db is True
            return {"cookies": [], "origins": []}

    class Page:
        async def evaluate(self, script: str) -> dict[str, object]:
            assert "WEBGL_debug_renderer_info" in script
            return {"user_agent": "Mozilla/5.0 Firefox/150.0"}

    session = _Session(
        key="account",
        browser=object(),
        context=Context(),
        page=Page(),
        backend="camoufox",
        state_digest="",
        input_digest="",
        proxy_url="",
        camoufox_config={"navigator.userAgent": "Mozilla/5.0 Firefox/150.0"},
    )

    patch = await MinmaxOfficialBrowserTransport("https://agent.minimax.io")._credential_patch(
        session, {"token": "token", "user_id": "user"}
    )

    execution = patch["browser_execution_context"]
    assert execution["schema_version"] == 1
    assert execution["camoufox_config"]["navigator.userAgent"].endswith("Firefox/150.0")
