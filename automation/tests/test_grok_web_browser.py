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
        "tools": [
            {
                "type": "function",
                "function": {
                    "name": "lookup",
                    "description": "Look up a value",
                    "parameters": {"type": "object"},
                },
            }
        ],
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


def test_grok_web_builds_request_with_model_aliases() -> None:
    for alias, expected_mode in (
        ("grok-3", "fast"),
        ("grok-3-mini", "fast"),
        ("grok-3-fast", "fast"),
        ("grok-2", "fast"),
        ("grok-3-deepsearch", "heavy"),
        ("grok-3-reasoning", "heavy"),
        ("grok-3-expert", "expert"),
    ):
        command = {
            "schemaVersion": 1,
            "model": alias,
            "messages": [{"role": "user", "content": "hi"}],
            "providerOptions": {},
            "controls": {},
        }
        request = build_grok_web_request(command)
        assert request["mode"] == expected_mode


def test_register_grok_web_flow() -> None:
    from unittest.mock import MagicMock

    from any2api_automation.lifecycle.mail import Mailbox
    from any2api_automation.lifecycle.registration import RegistrationStage, RegistrationTrace
    from any2api_automation.providers.grok_web_browser import register_grok_web

    mock_page = MagicMock()
    mock_locator = MagicMock()
    mock_locator.is_visible.return_value = False
    mock_locator.first = mock_locator
    mock_page.locator.return_value = mock_locator

    mock_page.evaluate.return_value = {
        "status": 200,
        "body": '{"session":{"userId":"usr_grok_123"}}',
    }

    mock_context = MagicMock()
    mock_context.cookies.return_value = [
        {"name": "sso", "value": "sso_test_val"},
        {"name": "sso-rw", "value": "sso_rw_test_val"},
        {"name": "cf_clearance", "value": "cf_test_val"},
    ]

    mock_mail = MagicMock()
    mock_mail.wait_for_code.return_value = "123456"

    mailbox = Mailbox(address="test@example.com", jwt="test-jwt")
    trace = RegistrationTrace("grok_web")

    result = register_grok_web(
        page=mock_page,
        context=mock_context,
        backend="camoufox",
        mail=mock_mail,
        mailbox=mailbox,
        password="TestPassword123!",
        payload={},
        trace=trace,
    )

    assert result.external_id == "usr_grok_123"
    assert result.email == "test@example.com"
    assert result.credential["sso"] == "sso_test_val"
    assert result.credential["sso-rw"] == "sso_rw_test_val"
    assert "cf_clearance" in result.credential["cookie"]
    assert result.ready_for_inference is True
    assert RegistrationStage.ACTIVATED.value in trace.stages
    assert RegistrationStage.CREDENTIAL_CAPTURED.value in trace.stages
