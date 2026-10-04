import asyncio
import json
import shutil
import subprocess
from contextlib import asynccontextmanager
from copy import deepcopy
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock

import pytest

from any2api_automation.lifecycle.mail import Mailbox
from any2api_automation.lifecycle.registration import RegistrationStage, RegistrationTrace
from any2api_automation.providers.grok_web_browser import (
    _SESSION_REQUEST,
    _STREAM_REQUEST,
    GrokWebOfficialBrowserTransport,
    build_grok_web_request,
    register_grok_web,
)


@pytest.mark.asyncio
async def test_runtime_selection_has_a_deadline_and_releases_account_operation(monkeypatch):
    runtime = GrokWebOfficialBrowserTransport("https://grok.com")
    released = False

    @asynccontextmanager
    async def operation(_credential):
        nonlocal released
        try:
            yield
        finally:
            released = True

    async def stalled_selection(*_args):
        await asyncio.Event().wait()

    monkeypatch.setattr(runtime, "account_operation", operation)
    monkeypatch.setattr(runtime, "_select_session", stalled_selection)
    monkeypatch.setattr(
        "any2api_automation.providers.grok_web_browser.core_settings",
        lambda: SimpleNamespace(inference_first_frame_timeout_seconds=0.02),
    )
    command = {
        "schemaVersion": 1,
        "model": "grok-3",
        "messages": [{"role": "user", "content": "hi"}],
        "providerOptions": {},
        "controls": {},
        "tools": [],
    }
    events = [event async for event in runtime.stream({}, command, "", SimpleNamespace())]
    assert events == [
        {"type": "status", "status": 504},
        {"type": "error", "data": "Grok Web runtime selection timed out"},
    ]
    assert released
    assert not runtime._stream_queues


@pytest.mark.asyncio
async def test_browser_preparation_consumes_first_frame_budget_and_timing_stays_internal(
    monkeypatch,
):
    runtime = GrokWebOfficialBrowserTransport("https://grok.com")
    captured = {}

    @asynccontextmanager
    async def operation(_credential):
        yield

    async def evaluate(script, argument):
        if script == _STREAM_REQUEST:
            captured.update(argument)
            runtime._emit(
                {
                    "requestId": argument["requestId"],
                    "type": "timing",
                    "phase": "websocket_open",
                    "elapsed_ms": 5,
                }
            )
            runtime._emit({"requestId": argument["requestId"], "type": "status", "status": 504})
            runtime._emit(
                {"requestId": argument["requestId"], "type": "error", "data": "timed out"}
            )

    session = SimpleNamespace(page=SimpleNamespace(evaluate=evaluate), build_id="test")

    async def select(*_args):
        await asyncio.sleep(0.03)
        return session, None, []

    monkeypatch.setattr(runtime, "account_operation", operation)
    monkeypatch.setattr(runtime, "_select_session", select)
    monkeypatch.setattr(runtime, "credential_patch", AsyncMock(return_value={}))
    monkeypatch.setattr(
        "any2api_automation.providers.grok_web_browser.core_settings",
        lambda: SimpleNamespace(
            inference_first_frame_timeout_seconds=0.2,
            inference_timeout_seconds=1,
            browser_cleanup_timeout_seconds=0.2,
        ),
    )
    command = {
        "schemaVersion": 1,
        "requestId": "gateway-request-id",
        "model": "grok-3",
        "messages": [{"role": "user", "content": "hi"}],
        "providerOptions": {},
        "controls": {},
        "tools": [],
    }
    events = [event async for event in runtime.stream({}, command, "", SimpleNamespace())]
    assert 0 < captured["firstFrameTimeoutMs"] < 180
    assert captured["timeoutMs"] < 980
    assert captured["requestId"] == "gateway-request-id"
    assert all(event["type"] != "timing" for event in events)
    assert not runtime._stream_queues


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
    assert "executed by the caller" in request["message"]
    assert request["message"].index("hello") < request["message"].index("Tool calling contract")
    assert request["enableSideBySide"] is False


def test_grok_web_continues_after_complete_history_and_client_function_result() -> None:
    messages = [
        {"role": "system", "content": "本次文档名称为交付资料-57345.txt。"},
        {"role": "developer", "content": "SKILL.md：汇报文档、规则、批次和工具结果。"},
        {"role": "user", "content": "业务批次号为57345。"},
    ]
    messages.extend(
        {"role": "user" if index % 2 == 0 else "assistant", "content": f"保留完整历史-{index}"}
        for index in range(38)
    )
    messages.extend(
        [
            {
                "role": "assistant",
                "content": "正在验收。",
                "tool_calls": [
                    {
                        "id": "call_document",
                        "type": "function",
                        "function": {
                            "name": "review_document",
                            "arguments": '{"batch_number":"57345"}',
                        },
                    }
                ],
            },
            {
                "role": "tool",
                "tool_call_id": "call_document",
                "content": '{"status":"已核验","processed_pages":37}',
            },
        ]
    )
    command = {
        "schemaVersion": 1,
        "model": "grok-3",
        "messages": messages,
        "tools": [],
        "providerOptions": {},
        "controls": {"tool_choice": "none"},
    }
    original = deepcopy(command)

    request = build_grok_web_request(command)

    prompt = request["message"]
    assert "Follow the system and developer instructions" in prompt
    assert prompt.index("[system]") < prompt.index("[developer]") < prompt.index("业务批次号")
    assert all(message["content"] in prompt for message in messages)
    assert (
        prompt.index("保留完整历史-0")
        < prompt.index("保留完整历史-37")
        < prompt.index("正在验收。")
    )
    assert prompt.index('"id":"call_document"') < prompt.index("Tool result (call_document)")
    assert prompt.index("Tool result (call_document)") < prompt.index(
        "[End of conversation transcript]"
    )
    assert prompt.endswith("Produce only the next assistant response.")
    assert "[Tool calling contract]" not in prompt
    assert command == original


def test_grok_web_leaves_a_single_plain_user_prompt_unchanged() -> None:
    command = {
        "schemaVersion": 1,
        "model": "grok-3",
        "messages": [{"role": "user", "content": "hello"}],
        "tools": [],
        "providerOptions": {},
        "controls": {},
    }
    assert build_grok_web_request(command)["message"] == "[user]\nhello"


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


@pytest.mark.parametrize("scenario", ["binary", "binary_close", "silent", "http_error"])
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
    if (type === 'response.create' && input.scenario.startsWith('binary')) setTimeout(() => {
      frame({type: 'response.output_text.delta', delta: 'first'});
      frame({type: 'response.output_text.delta', delta: 'second'});
      frame({type: 'response.done'});
      if (input.scenario === 'binary_close') this.onclose();
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
    if scenario.startswith("binary"):
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
