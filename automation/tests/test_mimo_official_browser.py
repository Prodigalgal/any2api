import asyncio
import json
from contextlib import asynccontextmanager
from copy import deepcopy
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from any2api_automation.providers import mimo as mimo_module
from any2api_automation.providers.mimo import MimoAutomationProvider
from any2api_automation.providers.mimo_browser import (
    _UPLOAD_MEDIA,
    MimoOfficialBrowserTransport,
    _config_request,
    _mimo_media_sources,
    _stream_request,
    build_mimo_chat_request,
    default_runtime_plan,
)


def test_mimo_uses_same_origin_browser_fetch_for_config_and_chat() -> None:
    rule = default_runtime_plan().active.rules
    config_script = _config_request(rule)
    stream_script = _stream_request(rule)

    assert "fetch(request.url" in config_script
    assert "fetch(request.url" in stream_script
    assert "credentials: 'include'" in config_script
    assert "credentials: 'include'" in stream_script
    assert "rspackChunk" not in stream_script


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


def test_mimo_tool_contract_keeps_unicode_and_full_schema_without_escape_expansion() -> None:
    command = _semantic_command()
    description = "完整中文工具说明" * 200
    parameters = {
        "type": "object",
        "properties": {"城市": {"type": "string"}},
        "required": ["城市"],
    }
    command["tools"] = [
        {
            "type": "function",
            "function": {
                "name": "city_lookup",
                "description": description,
                "parameters": parameters,
            },
        }
    ]
    query = build_mimo_chat_request(command)["query"]
    definitions = json.loads(query.split("<tools>", 1)[1].split("</tools>", 1)[0])
    assert definitions == [
        {"name": "city_lookup", "description": description, "parameters": parameters}
    ]
    assert "\\u57ce" not in query


@pytest.mark.parametrize("content", ["已核对第一页", [{"type": "text", "text": "已核对第一页"}]])
@pytest.mark.parametrize("with_calls", [True, False])
def test_mimo_assistant_history_retains_content_and_all_function_identities(
    content, with_calls
) -> None:
    command = _semantic_command()
    command["messages"] = [
        {"role": "system", "content": "保留完整的文档说明"},
        {"role": "developer", "content": "SKILL.md：核对后汇总"},
        {"role": "user", "content": "核对两份文档"},
        {
            "role": "assistant",
            "content": content,
            "tool_calls": [
                {
                    "id": "call_document_a",
                    "type": "function",
                    "function": {
                        "name": "review_document",
                        "arguments": '{"document":"甲.txt"}',
                    },
                },
                {
                    "id": "call_document_b",
                    "type": "function",
                    "function": {
                        "name": "review_document",
                        "arguments": '{"document":"乙.txt"}',
                    },
                },
            ]
            if with_calls
            else [],
        },
    ]
    if with_calls:
        command["messages"].extend(
            [
                {"role": "tool", "tool_call_id": "call_document_a", "content": "甲文档已核验"},
                {"role": "tool", "tool_call_id": "call_document_b", "content": "乙文档已核验"},
            ]
        )
    original = deepcopy(command)

    query = build_mimo_chat_request(command)["query"]

    assert "已核对第一页" in query
    assert "保留完整的文档说明" in query and "SKILL.md：核对后汇总" in query
    if with_calls:
        assert "[TOOL call_document_a]\n甲文档已核验" in query
        assert "[TOOL call_document_b]\n乙文档已核验" in query
        assert "[call_id=call_document_a] TOOL_CALL: review_document" in query
        assert "[call_id=call_document_b] TOOL_CALL: review_document" in query
        assert '{"document":"甲.txt"}' in query and '{"document":"乙.txt"}' in query
    assert command == original


def test_mimo_media_is_validated_before_browser_upload() -> None:
    source = "data:image/png;base64,aW1hZ2U="
    command = _semantic_command()
    command["messages"] = [
        {
            "role": "user",
            "content": [{"type": "input_image", "image_url": {"url": source}}],
        }
    ]

    sources = _mimo_media_sources(command["messages"])
    assert sources[0]["dataUrl"] == source
    assert sources[0]["filename"].endswith(".png")

    with pytest.raises(ValueError, match="inline base64"):
        _mimo_media_sources(
            [
                {
                    "role": "user",
                    "content": [{"type": "image_url", "image_url": "https://example.test/a.png"}],
                }
            ]
        )


def test_mimo_media_upload_matches_the_current_official_request_shape() -> None:
    assert "fetch(withPhase(input.uploadInfoPath), {" in _UPLOAD_MEDIA
    assert "headers: {'Content-Type': 'application/octet-stream'}" in _UPLOAD_MEDIA
    assert "input.uploadInfoPath +" not in _UPLOAD_MEDIA
    assert "withPhase(input.parsePath) + '&fileUrl='" in _UPLOAD_MEDIA
    assert "info?.data ?? info" in _UPLOAD_MEDIA
    assert "parseBody?.data ?? parseBody" in _UPLOAD_MEDIA
    assert "xiaomichatbot_ph" in _UPLOAD_MEDIA


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


@pytest.mark.asyncio
async def test_concurrent_accounts_receive_only_their_own_stream_events(monkeypatch) -> None:
    from any2api_automation.providers import mimo_browser

    runtime = MimoOfficialBrowserTransport("https://aistudio.xiaomimimo.com")
    both_started = asyncio.Event()
    started = set()

    class Page:
        def __init__(self, account):
            self.account = account

        async def evaluate(self, _script, payload):
            started.add(self.account)
            if len(started) == 2:
                both_started.set()
            await both_started.wait()
            runtime._emit({"requestId": payload["requestId"], "type": "status", "status": 200})
            runtime._emit({"requestId": payload["requestId"], "type": "data", "data": self.account})

    async def select(credential, _proxy, _plan):
        return SimpleNamespace(page=Page(credential["user_id"]), build_id=""), None, []

    runtime._select_session = AsyncMock(side_effect=select)
    runtime.credential_patch = AsyncMock(return_value={})
    monkeypatch.setattr(mimo_browser, "_stream_request", lambda _rule: "test")
    monkeypatch.setattr(mimo_browser, "successful_canary", lambda *_args: None)

    async def selection(credential, proxy, plan):
        session, _, reports = await select(credential, proxy, plan)
        return session, SimpleNamespace(rules=default_runtime_plan().active.rules), reports

    runtime._select_session = AsyncMock(side_effect=selection)

    async def consume(account):
        return [
            event
            async for event in runtime.stream(
                {"user_id": account, "xiaomichatbot_ph": "phase"},
                _semantic_command(),
                "",
                None,
            )
        ]

    a, b = await asyncio.wait_for(asyncio.gather(consume("a"), consume("b")), 2)
    assert [event["data"] for event in a if event["type"] == "data"] == ["a"]
    assert [event["data"] for event in b if event["type"] == "data"] == ["b"]
    assert runtime._stream_queues == {}
    await runtime.close()
