import pytest

from any2api_automation.providers.glm_runtime import build_glm_command
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
    assert glm["completion"]["signature_prompt"] == prompt
    assert glm["completion"]["messages"][-1]["content"] == prompt
    minmax = build_minmax_request({**command, "model": "MiniMax-M3.1-Flash-Preview"})
    assert prompt in minmax["content"]
    assert "Previous assistant function calls" in minmax["content"]
