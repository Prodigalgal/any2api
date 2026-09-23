import json
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
async def test_chat_stream_full_websocket_lifecycle() -> None:
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
    assert mock_ws.send.await_count >= 2


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
