import copy
import json

import pytest

from any2api_automation.providers.longcat_browser import (
    _append_tool_contract,
    _normalize_tools,
)


@pytest.mark.parametrize("nested", [False, True])
def test_strict_function_schemas_reach_the_longcat_contract_without_mutation(nested):
    schema = {
        "type": "object",
        "title": "Addends",
        "properties": {"a": {"type": "integer"}, "b": {"type": "integer"}},
        "required": ["a", "b"],
        "additionalProperties": False,
    }
    definition = {"name": "calculate_sum", "strict": True, "parameters": schema}
    tool = (
        {"type": "function", "function": definition}
        if nested
        else {"type": "function", **definition}
    )
    original = copy.deepcopy(tool)
    normalized = _normalize_tools([tool])
    prompt = _append_tool_contract("Calculate using the tool", normalized, "required", False)
    encoded = prompt.split("Available tools: ", 1)[1].split("\n", 1)[0]
    assert json.loads(encoded)[0]["parameters"] == schema
    assert normalized[0]["name"] == "calculate_sum"
    assert "Functions are executed by the caller" in prompt
    assert "Parallel calls allowed: False" in prompt
    assert tool == original
