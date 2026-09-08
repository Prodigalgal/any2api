from any2api_automation.providers.grok_web_browser import (
    _SESSION_REQUEST,
    _STREAM_REQUEST,
    build_grok_web_request,
)


def test_grok_web_builds_gateway_command_from_semantic_request() -> None:
    command = {
        "schemaVersion": 1,
        "model": "grok-chat-fast",
        "messages": [{"role": "user", "content": "hello"}],
        "tools": [{
            "type": "function",
            "function": {
                "name": "lookup",
                "description": "Look up a value",
                "parameters": {"type": "object"},
            },
        }],
        "providerOptions": {},
        "controls": {"tool_choice": "required"},
        "previousConversationId": "conversation-1",
        "previousUpstreamResponseId": "response-1",
    }

    request = build_grok_web_request(command)

    assert request["mode"] == "fast"
    assert request["conversationId"] == "conversation-1"
    assert request["parentResponseId"] == "response-1"
    assert "<tool_calls>" in request["message"]
    assert "lookup" in request["message"]


def test_grok_web_uses_page_session_and_page_websocket() -> None:
    assert "/api/auth/session" in _SESSION_REQUEST
    assert "/api/auth/session" in _STREAM_REQUEST
    assert "new WebSocket" in _STREAM_REQUEST
    assert "conversation.item.create" in _STREAM_REQUEST
    assert "response.create" in _STREAM_REQUEST
