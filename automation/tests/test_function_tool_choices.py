import json
from copy import deepcopy

import pytest

from any2api_automation.providers.arena_browser import build_arena_request
from any2api_automation.providers.deepseek_browser import build_deepseek_request
from any2api_automation.providers.glm_runtime import build_glm_command
from any2api_automation.providers.grok_web_browser import build_grok_web_request
from any2api_automation.providers.longcat_browser import build_longcat_request
from any2api_automation.providers.mimo_browser import build_mimo_chat_request
from any2api_automation.providers.minmax import build_minmax_request


@pytest.mark.parametrize(
    ("builder", "model"),
    [(build_longcat_request, "longcat-flash"), (build_mimo_chat_request, "mimo-v2.6-flash")],
)
@pytest.mark.parametrize(
    "choice",
    [
        {"type": "function", "name": "lookup"},
        {"type": "function", "function": {"name": "lookup"}},
    ],
)
def test_forced_function_choice_preserves_tool_identity(builder, model, choice) -> None:
    request = builder(
        {
            "schemaVersion": 1,
            "requestId": "forced-function-smoke",
            "model": model,
            "messages": [{"role": "user", "content": "Use lookup."}],
            "generation": {},
            "reasoning": {},
            "providerOptions": {},
            "controls": {"tool_choice": choice},
            "tools": [
                {
                    "type": "function",
                    "function": {"name": "lookup", "parameters": {"type": "object"}},
                }
            ],
        }
    )
    assert "lookup" in str(request)
    if builder is build_longcat_request:
        assert "Tool choice: lookup" in request["content"]
    else:
        assert "required" in str(request).lower() or "must" in str(request).lower()


def test_web_agents_preserve_caller_function_contract_and_result_in_the_actual_user_prompt() -> (
    None
):
    prompt = "[Function result call_id=call_inspect]\ndemo.txt\n\n[Tool calling contract]\nUse inspect only if more information is needed."
    command = {
        "schemaVersion": 1,
        "requestId": "function-result",
        "protocol": "RESPONSES",
        "model": "glm-5.2",
        "stream": True,
        "messages": [
            {"role": "developer", "content": "Follow the caller's function protocol"},
            {"role": "user", "content": "Inspect the workspace"},
            {
                "role": "assistant",
                "content": 'Previous assistant function calls (JSON): [{"id":"call_inspect","name":"inspect"}]',
            },
            {"role": "user", "content": prompt},
        ],
        "generation": {},
        "reasoning": {},
        "tools": [],
        "providerOptions": {},
        "controls": {},
    }
    glm = build_glm_command(command, "user@example.test", 1_785_337_442_000)
    assert glm["completion"]["signature_prompt"].endswith(prompt)
    assert glm["completion"]["messages"][-1]["content"] == glm["prompt"]
    assert "Follow the caller's function protocol" in glm["prompt"]
    assert '"id":"call_inspect","name":"inspect"' in glm["prompt"]
    assert command["messages"][-1]["content"] == prompt
    minmax = build_minmax_request({**command, "model": "MiniMax-M3.1-Flash-Preview"})
    assert prompt in minmax["content"]
    assert "Previous assistant function calls" in minmax["content"]


@pytest.mark.parametrize(
    ("builder", "model", "kwargs", "path"),
    [
        (
            build_arena_request,
            "claude-sonnet-5",
            {"model_id": "00000000-0000-4000-8000-000000000007"},
            ("userMessage", "content"),
        ),
        (build_deepseek_request, "default", {"session_id": "synthetic-session"}, ("prompt",)),
        (build_glm_command, "glm-5.2", {"email": "user@example.test"}, ("prompt",)),
        (build_grok_web_request, "grok-3", {}, ("message",)),
        (build_longcat_request, "longcat-flash", {}, ("content",)),
        (build_mimo_chat_request, "mimo-v2.6-pro", {}, ("query",)),
        (build_minmax_request, "MiniMax-M3.1-Flash-Preview", {}, ("content",)),
    ],
    ids=["arena", "deepseek", "glm", "grok_web", "longcat", "mimo", "minmax"],
)
def test_every_web_builder_keeps_system_skill_complete_history_and_prepared_function_result(
    builder,
    model,
    kwargs,
    path,
) -> None:
    messages = [
        {"role": "system", "content": "系统规则：文档验收不可省略原始内容。"},
        {"role": "developer", "content": "SKILL.md：先核对，再汇总全部工具结果。"},
        {"role": "user", "content": "早期文档编号：交付资料-7319。"},
    ]
    messages.extend(
        {"role": "user" if index % 2 == 0 else "assistant", "content": f"完整历史记录-{index}。"}
        for index in range(44)
    )
    messages.extend(
        [
            {
                "role": "assistant",
                "content": '已核对第一页。\nPrevious assistant function calls (JSON): [{"id":"call_document","function":{"name":"review_document","arguments":"{}"}}]',
            },
            {
                "role": "user",
                "content": "[Function result call_id=call_document]\n文档已核验，37 页。\n[Tool calling contract]\nreview_document",
            },
        ]
    )
    command = {
        "schemaVersion": 1,
        "requestId": "complete-agent-context",
        "protocol": "RESPONSES",
        "model": model,
        "stream": True,
        "messages": messages,
        "generation": {},
        "reasoning": {},
        "tools": [],
        "providerOptions": {},
        "controls": {},
    }
    original = deepcopy(command)

    request = builder(command, **kwargs)
    prompt = request
    for component in path:
        prompt = prompt[component]
    if builder is build_grok_web_request:
        encoded_history = prompt.split("[Earlier conversation history]\n", 1)[1].split(
            "\n[End of earlier conversation history]", 1
        )[0]
        context = json.loads(request["systemProvidedContext"])
        history = json.loads(encoded_history)
        prompt = "\n".join(message["content"] for message in [*context, *history]) + "\n" + prompt

    assert all(message["content"] in prompt for message in messages)
    assert command == original
