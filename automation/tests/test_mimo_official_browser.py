import json
from contextlib import asynccontextmanager

import pytest

from any2api_automation.providers import mimo as mimo_module
from any2api_automation.providers.mimo import MimoAutomationProvider
from any2api_automation.providers.mimo_browser import (
    MimoOfficialBrowserTransport,
    build_mimo_chat_request,
    official_bridge_script,
)


def test_mimo_bridge_discovers_official_runtime_without_fixed_module_ids() -> None:
    script = official_bridge_script()

    assert "rspackChunk" in script
    assert "/open-apis/bot/chat" in script
    assert "genUploadInfo" in script
    assert "completions" in script
    assert "getConfig" in script
    assert "80032" not in script


def test_mimo_storage_injection_rejects_cross_provider_state() -> None:
    runtime = MimoOfficialBrowserTransport("https://aistudio.xiaomimimo.com")

    result = runtime.filter_storage_state(
        {
            "cookies": [
                {"name": "mimo", "value": "1", "domain": ".xiaomimimo.com"},
                {"name": "foreign", "value": "2", "domain": ".example.com"},
            ],
            "origins": [
                {
                    "origin": "https://aistudio.xiaomimimo.com",
                    "localStorage": [],
                },
                {"origin": "https://example.com", "localStorage": []},
            ],
        }
    )

    assert [cookie["name"] for cookie in result["cookies"]] == ["mimo"]
    assert [origin["origin"] for origin in result["origins"]] == ["https://aistudio.xiaomimimo.com"]


def test_mimo_builds_provider_body_from_semantic_command() -> None:
    body = build_mimo_chat_request(_semantic_command())

    assert body["query"].endswith("[USER]\nhello")
    assert "You must call at least one declared function" in body["query"]
    assert body["modelConfig"] == {
        "enableThinking": True,
        "temperature": 0.2,
        "topP": 0.95,
        "webSearchStatus": "disabled",
        "model": "mimo-v2.5-pro",
    }


@pytest.mark.asyncio
async def test_mimo_transport_normalizes_startup_failure_to_error_frame(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class BrokenTransport:
        async def stream(self, *_args: object):
            raise RuntimeError("bridge missing")
            yield {}

    @asynccontextmanager
    async def proxy_lease(*_args: object, **_kwargs: object):
        yield ""

    monkeypatch.setattr(mimo_module, "official_browser_transport", BrokenTransport())
    monkeypatch.setattr(mimo_module, "transport_proxy_lease", proxy_lease)

    frames = [
        json.loads(frame)
        async for frame in MimoAutomationProvider().transport_stream(
            {
                "operation": "chat",
                "semantic_command": _semantic_command(),
                "runtime_plan": _runtime_plan(),
                "credential": {},
            }
        )
    ]

    assert frames == [{"type": "error", "data": "official browser stream failed (RuntimeError)"}]


def _semantic_command() -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "requestId": "request-1",
        "protocol": "CHAT_COMPLETIONS",
        "model": "mimo-v2.5-pro",
        "stream": True,
        "messages": [{"role": "user", "content": "hello"}],
        "generation": {"temperature": 0.2},
        "reasoning": {"effort": "high"},
        "tools": [
            {
                "type": "function",
                "function": {"name": "lookup", "parameters": {"type": "object"}},
            }
        ],
        "providerOptions": {},
        "controls": {"tool_choice": "required", "parallel_tool_calls": True},
    }


def _runtime_plan() -> dict[str, object]:
    return {
        "active": {
            "providerId": "mimo",
            "revision": 1,
            "rules": {
                "schemaVersion": 1,
                "sessionMaxAgeSeconds": 900,
                "canaryTimeoutSeconds": 60,
                "buildAssetMarkers": ["xiaomimimo.com"],
                "discoveryMarkers": {"requestModule": ["/open-apis/bot/chat", "genUploadInfo"]},
                "capabilities": {"chat": "completions", "models": "getConfig"},
                "endpointPaths": {
                    "chat": "/open-apis/bot/chat",
                    "models": "/open-apis/bot/config",
                },
            },
        },
        "candidate": None,
    }
