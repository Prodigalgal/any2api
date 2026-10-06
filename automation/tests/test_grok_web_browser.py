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
    assert json.loads(request["systemProvidedContext"]) == messages[:2]
    assert _inline_history(prompt) == messages[2:39]
    assert prompt.startswith("[Earlier conversation history]\n")
    assert (
        "[Current turn]\nLeading system/developer instructions are in the separate context chunk"
        in prompt
    )
    assert "are the complete conversation" not in prompt
    assert request["inputChunks"] == [
        {"system_provided_context": {"text": request["systemProvidedContext"]}},
        {"text": {"text": prompt}},
    ]
    assert "Follow the system and developer instructions" in prompt
    assert all(message["content"] in prompt for message in messages[39:])
    assert "[system]" not in prompt
    assert "业务批次号" in prompt
    assert (
        prompt.index("保留完整历史-36")
        < prompt.index("保留完整历史-37")
        < prompt.index("正在验收。")
    )
    assert prompt.index('"id":"call_document"') < prompt.index("Tool result (call_document)")
    assert prompt.index("Tool result (call_document)") < prompt.index("[End of current turn]")
    assert prompt.endswith("Produce only the next assistant response.")
    assert "[Tool calling contract]" not in prompt
    assert command == original


@pytest.mark.parametrize(
    "suffix",
    [[], [{"role": "user", "content": " \n\t"}]],
)
def test_grok_native_context_preserves_earlier_calls_and_blank_follow_up(suffix) -> None:
    history = [
        {"role": "system", "content": "遵守当前验收技能。\n保留每个批次。"},
        {"role": "developer", "content": "SKILL.md：保留规则与函数结果。"},
        {"role": "user", "content": "早先任务"},
        {
            "role": "assistant",
            "content": "此前调用。",
            "tool_calls": [
                {"id": "call_old", "function": {"name": "review", "arguments": '{"中文":null}'}}
            ],
        },
        {"role": "tool", "tool_call_id": "call_old", "content": "早先结果\n第二行"},
    ]
    command = {
        "schemaVersion": 1,
        "model": "grok-3",
        "messages": [*history, {"role": "user", "content": "当前任务"}, *suffix],
        "tools": [],
        "providerOptions": {},
        "controls": {},
    }
    original = deepcopy(command)

    request = build_grok_web_request(command)

    assert json.loads(request["systemProvidedContext"]) == history[:2]
    assert _inline_history(request["message"]) == history[2:]
    assert "当前任务" in request["message"]
    assert "call_old" in request["message"]
    assert command == original


def test_grok_without_user_preserves_the_complete_original_prompt() -> None:
    command = {
        "schemaVersion": 1,
        "model": "grok-3",
        "messages": [
            {"role": "system", "content": "遵守技能。"},
            {"role": "developer", "content": "汇报当前状态。"},
        ],
        "tools": [],
        "providerOptions": {},
        "controls": {},
    }

    request = build_grok_web_request(command)

    assert request["systemProvidedContext"] == ""
    assert request["message"].startswith("[Conversation transcript]\n")
    assert "are the complete conversation" in request["message"]
    assert "[system]\n遵守技能。" in request["message"]
    assert "[developer]\n汇报当前状态。" in request["message"]


@pytest.mark.parametrize("message_type", [None, "message"])
def test_grok_gateway_resource_ids_do_not_change_model_context_or_function_ids(
    message_type,
) -> None:
    history = [
        {"role": "developer", "content": "SKILL.md：保留每个调用身份。"},
        {"role": "user", "content": "原业务批次。"},
        {
            "role": "assistant",
            "content": "",
            "tool_calls": [{"id": "call_real", "function": {"name": "review", "arguments": "{}"}}],
        },
        {"role": "tool", "tool_call_id": "call_real", "content": "此前结果。"},
        {
            "type": "function_call",
            "id": "fc_resource",
            "call_id": "call_typed",
            "name": "lookup",
            "arguments": "{}",
        },
    ]
    if message_type:
        for message in history[:4]:
            message["type"] = message_type
    command = {
        "schemaVersion": 1,
        "model": "grok-3",
        "messages": [*history, {"role": "user", "content": "当前任务"}],
        "tools": [],
        "providerOptions": {},
        "controls": {},
    }
    stored = deepcopy(command)
    for index, message in enumerate(stored["messages"][:4]):
        message["id"] = f"in_gateway_{index}"
    original = deepcopy(stored)

    plain = build_grok_web_request(command)
    state = build_grok_web_request(stored)

    assert plain["message"] == state["message"]
    assert plain["systemProvidedContext"] == state["systemProvidedContext"]
    assert json.loads(state["systemProvidedContext"]) == history[:1]
    context = _inline_history(state["message"])
    assert context[1]["tool_calls"][0]["id"] == context[2]["tool_call_id"] == "call_real"
    assert context[3]["id"] == "fc_resource" and context[3]["call_id"] == "call_typed"
    assert stored == original


def _inline_history(prompt: str) -> list[dict]:
    encoded = prompt.split("[Earlier conversation history]\n", 1)[1].split(
        "\n[End of earlier conversation history]", 1
    )[0]
    return json.loads(encoded)


@pytest.mark.parametrize("leading", [[], [{"role": "system", "content": "初始规则"}]])
def test_grok_keeps_interleaved_instructions_and_extra_history_fields_in_order(leading) -> None:
    history = [
        {
            "role": "user",
            "content": [{"type": "text", "text": "早期批次"}],
            "metadata": {"id": "nested_identity"},
        },
        {"role": "system", "content": "中途更新规则", "phase": "commentary"},
        {"role": "assistant", "content": "保留记录", "refusal": None},
    ]
    command = {
        "schemaVersion": 1,
        "model": "grok-3",
        "messages": [*leading, *history, {"role": "user", "content": "当前任务"}],
        "tools": [],
        "providerOptions": {},
        "controls": {},
    }
    original = deepcopy(command)

    request = build_grok_web_request(command)

    assert _inline_history(request["message"]) == history
    if leading:
        assert json.loads(request["systemProvidedContext"]) == leading
    else:
        assert request["systemProvidedContext"] == ""
    assert "初始规则" not in request["message"]
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
    request = build_grok_web_request(command)
    assert request["message"] == "[user]\nhello"
    assert request["enableSideBySide"] is False


@pytest.mark.parametrize("choice", ["auto", "none", "required"])
def test_grok_web_never_enables_comparison_when_function_policy_changes(choice) -> None:
    command = {
        "schemaVersion": 1,
        "model": "grok-3",
        "messages": [{"role": "user", "content": "hello"}],
        "tools": [
            {"type": "function", "name": "review_document", "parameters": {"type": "object"}}
        ],
        "providerOptions": {},
        "controls": {"tool_choice": choice},
    }
    original = deepcopy(command)
    assert build_grok_web_request(command)["enableSideBySide"] is False
    assert command == original


def test_grok_web_uses_page_session_and_page_websocket() -> None:
    assert "/api/auth/session" in _SESSION_REQUEST
    assert "/api/auth/session" in _STREAM_REQUEST
    assert "new WebSocket" in _STREAM_REQUEST
    assert "input_chunks: request.inputChunks" in _STREAM_REQUEST
    assert "event: responseEvent" in _STREAM_REQUEST
    assert "conversation.item.create" not in _STREAM_REQUEST
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
@pytest.mark.parametrize("context", ["", "系统技能。\n完整历史。"])
def test_gateway_preserves_frame_order_and_closes_on_completion_or_timeout(
    scenario: str, context: str
) -> None:
    node = shutil.which("node")
    if node is None:
        pytest.skip("Node is required to execute the page WebSocket contract")
    harness = r"""
const input = JSON.parse(require('fs').readFileSync(0, 'utf8'));
const events = [];
const sent = [];
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
    const outgoing = JSON.parse(raw);
    sent.push(outgoing);
    const type = outgoing.event.type;
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
  await eval('(' + input.script + ')')({...input.request, requestId: 'test',
    timeoutMs: 500, firstFrameTimeoutMs: 150});
  process.stdout.write(JSON.stringify({events, sent, closed, streams: window.__any2apiGrokWebStreams.size}));
})().catch(error => { process.stderr.write(String(error)); process.exitCode = 1; });
"""
    request = build_grok_web_request(
        {
            "schemaVersion": 1,
            "model": "grok-3",
            "messages": [
                *([{"role": "system", "content": context}] if context else []),
                {"role": "user", "content": "hi"},
            ],
            "providerOptions": {},
            "controls": {},
            "previousUpstreamResponseId": "parent-id",
        }
    )
    completed = subprocess.run(
        [node, "-e", harness],
        input=json.dumps({"script": _STREAM_REQUEST, "scenario": scenario, "request": request}),
        capture_output=True,
        text=True,
        check=True,
        timeout=5,
    )
    result = json.loads(completed.stdout)
    assert result["streams"] == 0
    if scenario != "http_error":
        assert [frame["event"]["type"] for frame in result["sent"]] == [
            "session.create",
            "response.create",
        ]
        event = result["sent"][1]["event"]
        assert event["parent_response_id"] == "parent-id"
        assert event["item"]["x_grok"]["input_chunks"] == [
            *(
                [{"system_provided_context": {"text": request["systemProvidedContext"]}}]
                if context
                else []
            ),
            {"text": {"text": "[user]\nhi"}},
        ]
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


@pytest.mark.parametrize("choice", ["none", {"type": "none"}])
def test_grok_web_normalizes_both_disabled_function_choices_without_exposing_tools(
    choice,
) -> None:
    command = {
        "schemaVersion": 1,
        "model": "grok-3",
        "messages": [{"role": "user", "content": "正常回复"}],
        "tools": [{"type": "function", "name": "review", "parameters": {"type": "object"}}],
        "providerOptions": {},
        "controls": {"tool_choice": choice},
    }
    original = deepcopy(command)

    prompt = build_grok_web_request(command)["message"]

    assert "正常回复" in prompt
    assert prompt == "[user]\n正常回复"
    assert "AVAILABLE TOOLS" not in prompt and "<tool_calls>" not in prompt
    assert command == original
