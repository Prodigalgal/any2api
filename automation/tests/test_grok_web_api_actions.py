import json
from copy import deepcopy
from unittest.mock import AsyncMock, patch

import pytest

from any2api_automation.providers.actions import ProviderAction, ProviderActionRequest
from any2api_automation.providers.grok_web_api_actions import (
    GrokWebApiActionHandler,
    grok_web_api_action_bindings,
)


@pytest.mark.asyncio
async def test_discover_models_returns_all_models_when_session_valid() -> None:
    handler = GrokWebApiActionHandler()
    request = ProviderActionRequest(
        provider_id="grok_web",
        action=ProviderAction.MODEL_DISCOVERY,
        channel="api",
        operation="models",
        payload={"credential": {"cookie": "sso=valid_sso_cookie;"}},
    )

    with patch(
        "any2api_automation.providers.grok_web_api_actions.api_request_sync"
    ) as mock_api_request:
        mock_api_request.return_value = {
            "status": 200,
            "body": json.dumps({"session": {"userId": "usr_test_123"}}),
        }
        result = await handler.execute(request)

    assert result["status"] == 200
    assert result["transport_mode"] == "api"
    data = json.loads(result["body"])["data"]
    ids = [item["id"] for item in data]
    assert "grok-3" in ids
    assert "grok-chat-fast" in ids
    assert "grok-3-deepsearch" in ids


@pytest.mark.asyncio
async def test_discover_models_fails_without_cookie() -> None:
    handler = GrokWebApiActionHandler()
    request = ProviderActionRequest(
        provider_id="grok_web",
        action=ProviderAction.MODEL_DISCOVERY,
        channel="api",
        operation="models",
        payload={"credential": {}},
    )

    result = await handler.execute(request)
    assert result["status"] == 401


@pytest.mark.asyncio
async def test_chat_stream_fails_without_cookie() -> None:
    handler = GrokWebApiActionHandler()
    request = ProviderActionRequest(
        provider_id="grok_web",
        action=ProviderAction.CHAT,
        channel="api",
        payload={"credential": {}},
        semantic_command={
            "schemaVersion": 1,
            "model": "grok-3",
            "messages": [{"role": "user", "content": "hi"}],
            "generation": {},
            "reasoning": {},
            "providerOptions": {},
            "controls": {},
            "tools": [],
        },
    )

    frames = [frame async for frame in handler.stream(request)]
    assert len(frames) == 2
    status_frame = json.loads(frames[0].decode("utf-8"))
    error_frame = json.loads(frames[1].decode("utf-8"))
    assert status_frame["type"] == "status"
    assert status_frame["status"] == 401
    assert error_frame["type"] == "error"


@pytest.mark.asyncio
async def test_chat_stream_session_failure() -> None:
    handler = GrokWebApiActionHandler()
    request = ProviderActionRequest(
        provider_id="grok_web",
        action=ProviderAction.CHAT,
        channel="api",
        payload={"credential": {"cookie": "sso=invalid_cookie;"}},
        semantic_command={
            "schemaVersion": 1,
            "model": "grok-3",
            "messages": [{"role": "user", "content": "hi"}],
            "generation": {},
            "reasoning": {},
            "providerOptions": {},
            "controls": {},
            "tools": [],
        },
    )

    with patch(
        "any2api_automation.providers.grok_web_api_actions.api_request_sync"
    ) as mock_api_request:
        mock_api_request.return_value = {
            "status": 401,
            "body": "unauthorized",
        }
        frames = [frame async for frame in handler.stream(request)]

    assert len(frames) == 2
    status_frame = json.loads(frames[0].decode("utf-8"))
    assert status_frame["status"] == 401


@pytest.mark.asyncio
@pytest.mark.parametrize("with_history", [False, True])
async def test_chat_stream_full_websocket_lifecycle(with_history: bool) -> None:
    handler = GrokWebApiActionHandler()
    request = ProviderActionRequest(
        provider_id="grok_web",
        action=ProviderAction.CHAT,
        channel="api",
        payload={"credential": {"cookie": "sso=valid_cookie; sso-rw=valid_rw;"}},
        semantic_command={
            "schemaVersion": 1,
            "model": "grok-3",
            "messages": [{"role": "user", "content": "hello grok"}],
            "generation": {},
            "reasoning": {},
            "providerOptions": {},
            "controls": {},
            "tools": [],
        },
    )
    request.semantic_command["previousConversationId"] = "conversation-parent"
    request.semantic_command["previousUpstreamResponseId"] = "response-parent"
    if with_history:
        request.semantic_command["messages"] = [
            {"role": "system", "content": "System instructions"},
            {"role": "developer", "content": "SKILL.md: summarize tool results"},
            {"role": "user", "content": "Remember batch 7391"},
            {"role": "assistant", "content": "Batch recorded"},
            {"role": "user", "content": "Review this document"},
            {
                "role": "assistant",
                "content": "",
                "tool_calls": [
                    {
                        "id": "call-review",
                        "type": "function",
                        "function": {"name": "review", "arguments": '{"batch":"7391"}'},
                    }
                ],
            },
            {
                "role": "tool",
                "tool_call_id": "call-review",
                "content": '{"status":"verified","pages":37}',
            },
        ]
        request.semantic_command["controls"] = {"tool_choice": "none"}
    original = deepcopy(request.semantic_command)

    mock_ws = AsyncMock()
    mock_ws.send = AsyncMock()

    async def mock_aiter(*_args):
        yield json.dumps(
            {
                "session_id": "sess_123",
                "event": {"type": "conversation.attached"},
            }
        )
        yield json.dumps(
            {
                "event": {
                    "type": "response.chunk",
                    "chunk": {"text": {"channel": "CHANNEL_ASSISTANT_RESPONSE", "text": "Hello!"}},
                }
            }
        )
        yield json.dumps(
            {
                "event": {"type": "response.done"},
            }
        )

    mock_ws.__aiter__ = mock_aiter
    mock_context_manager = AsyncMock()
    mock_context_manager.__aenter__.return_value = mock_ws
    mock_context_manager.__aexit__.return_value = None

    with (
        patch(
            "any2api_automation.providers.grok_web_api_actions.api_request_sync"
        ) as mock_api_request,
        patch("websockets.connect", return_value=mock_context_manager),
    ):
        mock_api_request.return_value = {
            "status": 200,
            "body": json.dumps({"session": {"userId": "usr_999"}}),
        }
        frames = [frame async for frame in handler.stream(request)]

    decoded = [json.loads(f.decode("utf-8")) for f in frames]
    assert decoded[0]["type"] == "status"
    assert decoded[0]["status"] == 200
    assert any("Hello!" in str(d) for d in decoded)
    sent = [json.loads(call.args[0]) for call in mock_ws.send.await_args_list]
    assert [frame["event"]["type"] for frame in sent] == ["session.create", "response.create"]
    assert sent[0]["event"]["session"]["x_grok"]["enable_side_by_side"] is False
    assert sent[0]["event"]["session"]["x_grok"]["conversation_id"] == "conversation-parent"
    assert sent[0]["event"]["session"]["x_grok"]["load_existing"] is True
    assert sent[1]["session_id"] == "sess_123"
    assert sent[1]["event"]["parent_response_id"] == "response-parent"
    item = sent[1]["event"]["item"]
    assert item["type"] == "message" and item["role"] == "user"
    chunks = item["x_grok"]["input_chunks"]
    if with_history:
        assert len(chunks) == 2
        assert json.loads(chunks[0]["system_provided_context"]["text"]) == original["messages"][:2]
        text = chunks[1]["text"]["text"]
        encoded_history = text.split("[Earlier conversation history]\n", 1)[1].split(
            "\n[End of earlier conversation history]", 1
        )[0]
        assert json.loads(encoded_history) == original["messages"][2:4]
        assert "Review this document" in text and "call-review" in text
        assert '{"status":"verified","pages":37}' in text
    else:
        assert chunks == [{"text": {"text": "[user]\nhello grok"}}]
    assert request.semantic_command == original


def test_grok_web_api_action_bindings_registers_both_channels() -> None:
    from any2api_automation.providers.grok_web import GrokWebAutomationProvider

    provider = GrokWebAutomationProvider()
    bindings = grok_web_api_action_bindings(provider)

    channels = {(b.channel, b.action.value) for b in bindings}
    assert ("api", "model_discovery") in channels
    assert ("api", "chat") in channels
    assert ("camoufox_browser_runtime", "model_discovery") in channels
    assert ("camoufox_browser_runtime", "chat") in channels
    assert ("camoufox_browser_runtime", "register") in channels
    assert ("camoufox_browser_runtime", "keepalive") in channels
