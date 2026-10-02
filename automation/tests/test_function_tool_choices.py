import pytest

from any2api_automation.providers.longcat_browser import build_longcat_request
from any2api_automation.providers.mimo_browser import build_mimo_chat_request


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
