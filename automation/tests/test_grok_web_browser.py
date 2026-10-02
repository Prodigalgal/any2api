import json
import shutil
import subprocess
from unittest.mock import MagicMock

import pytest

from any2api_automation.lifecycle.mail import Mailbox
from any2api_automation.lifecycle.registration import RegistrationStage, RegistrationTrace
from any2api_automation.providers.grok_web_browser import (
    _SESSION_REQUEST,
    _STREAM_REQUEST,
    build_grok_web_request,
    register_grok_web,
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
    assert request["enableSideBySide"] is False


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


@pytest.mark.parametrize("scenario", ["binary", "silent", "http_error"])
def test_gateway_preserves_frame_order_and_closes_on_completion_or_timeout(scenario: str) -> None:
    node = shutil.which("node")
    if node is None:
        pytest.skip("Node is required to execute the page WebSocket contract")
    harness = r"""
const input = JSON.parse(require('fs').readFileSync(0, 'utf8'));
const events = [];
let closed = false;
global.window = {__any2apiGrokWebEmit: async event => {
  if (event.type === 'data') await new Promise(resolve => setTimeout(resolve, 2));
  events.push(event);
}};
global.location = {href: 'https://grok.com/'};
global.fetch = async () => ({
  ok: input.scenario !== 'http_error', status: input.scenario === 'http_error' ? 401 : 200,
  text: async () => JSON.stringify({session: {userId: 'test-user'}})
});
global.WebSocket = class {
  constructor() { setTimeout(() => this.onopen(), 0); }
  send(raw) {
    const type = JSON.parse(raw).event.type;
    const frame = event => this.onmessage({data: new Blob([JSON.stringify({session_id: 'test', event})])});
    if (type === 'session.create') setTimeout(() => frame({type: 'conversation.attached'}), 0);
    if (type === 'response.create' && input.scenario === 'binary') setTimeout(() => {
      frame({type: 'response.output_text.delta', delta: 'first'});
      frame({type: 'response.output_text.delta', delta: 'second'});
      frame({type: 'response.done'});
    }, 0);
  }
  close() { closed = true; this.onclose?.(); }
};
(async () => {
  await eval('(' + input.script + ')')({requestId: 'test', mode: 'fast', message: 'hi',
    timeoutMs: 500, firstFrameTimeoutMs: 150});
  process.stdout.write(JSON.stringify({events, closed, streams: window.__any2apiGrokWebStreams.size}));
})().catch(error => { process.stderr.write(String(error)); process.exitCode = 1; });
"""
    completed = subprocess.run(
        [node, "-e", harness],
        input=json.dumps({"script": _STREAM_REQUEST, "scenario": scenario}),
        capture_output=True,
        text=True,
        check=True,
        timeout=5,
    )
    result = json.loads(completed.stdout)
    assert result["streams"] == 0
    if scenario == "binary":
        assert result["closed"]
        frames = [
            json.loads(event["data"])["event"]
            for event in result["events"]
            if event["type"] == "data"
        ]
        assert [frame.get("delta") for frame in frames if "delta" in frame] == ["first", "second"]
        assert not any(event["type"] == "error" for event in result["events"])
    else:
        assert any(event["type"] == "error" for event in result["events"])
        statuses = [event["status"] for event in result["events"] if event["type"] == "status"]
        assert statuses[-1] == (504 if scenario == "silent" else 401)
        if scenario == "silent":
            assert result["closed"]


def test_register_grok_web_flow() -> None:
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


def test_register_grok_web_fallback_uuid_when_session_empty_but_sso_present() -> None:
    mock_page = MagicMock()
    mock_page.url = "https://grok.com/"
    mock_page.evaluate.return_value = {
        "status": 200,
        "body": "{}",
    }

    mock_context = MagicMock()
    mock_context.cookies.return_value = [
        {"name": "sso", "value": "sso_test_val"},
        {"name": "sso-rw", "value": "sso_rw_test_val"},
    ]

    mock_mail = MagicMock()
    mock_mail.wait_for_code.return_value = "123456"

    mailbox = Mailbox(address="test_fallback@example.com", jwt="test-jwt")
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

    assert result.external_id
    assert len(result.external_id) == 36  # UUID length
    assert result.email == "test_fallback@example.com"
    assert result.credential["sso"] == "sso_test_val"
    assert result.credential["sso-rw"] == "sso_rw_test_val"
    assert result.ready_for_inference is True
    assert RegistrationStage.CREDENTIAL_CAPTURED.value in trace.stages
