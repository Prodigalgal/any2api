from any2api_automation.providers.base import CAMOUFOX_BROWSER_RUNTIME
from any2api_automation.providers.grok_console import GrokConsoleAutomationProvider
from any2api_automation.providers.grok_console_browser import (
    GrokConsoleOfficialBrowserTransport,
    build_grok_console_request,
)


def test_grok_console_declares_the_unified_browser_runtime() -> None:
    manifest = GrokConsoleAutomationProvider.manifest

    assert manifest.browser_backend == "camoufox"
    assert manifest.inference_transport is True
    assert manifest.inference_runtime == CAMOUFOX_BROWSER_RUNTIME


def test_runtime_rebuilds_when_auth_changes_but_reuses_for_state_only_changes() -> None:
    runtime = GrokConsoleOfficialBrowserTransport("https://console.x.ai")
    credential = {
        "email": "user@example.test",
        "sso": "sso-one",
        "browser_execution_context": {"schema_version": 1, "storage_state": {}},
    }

    state_changed = {
        **credential,
        "browser_execution_context": {
            "schema_version": 1,
            "storage_state": {"cookies": [{"name": "cf", "value": "new"}]},
        },
    }
    auth_changed = {**credential, "sso": "sso-two"}

    assert runtime.credential_digest(credential) == runtime.credential_digest(state_changed)
    assert runtime.credential_digest(credential) != runtime.credential_digest(auth_changed)


def test_grok_console_builds_chat_semantics_inside_automation() -> None:
    body = build_grok_console_request(
        {
            "schemaVersion": 1,
            "protocol": "CHAT_COMPLETIONS",
            "model": "grok-4.3",
            "messages": [
                {"role": "system", "content": "Be concise."},
                {"role": "user", "content": "Reply with OK."},
            ],
            "generation": {"max_tokens": 4, "temperature": 0.2},
            "reasoning": {},
            "providerOptions": {},
            "controls": {"tool_choice": "none"},
            "tools": [],
        }
    )

    assert body["model"] == "grok-4.3"
    assert body["stream"] is True
    assert body["store"] is False
    assert body["max_output_tokens"] == 4
    assert body["temperature"] == 0.2
    assert body["input"][0]["role"] == "developer"
    assert body["input"][1]["content"][0]["text"] == "Reply with OK."
    assert body["reasoning"]["effort"] == "medium"


def test_grok_console_sanitizes_responses_state_fields_and_tools() -> None:
    body = build_grok_console_request(
        {
            "schemaVersion": 1,
            "protocol": "RESPONSES",
            "model": "grok-4.20-0309-reasoning",
            "messages": [],
            "rawRequest": {
                "input": "Reply with OK.",
                "metadata": {"requester": "test"},
                "previous_response_id": "response-1",
                "max_output_tokens": 8,
            },
            "generation": {},
            "reasoning": {"effort": "high"},
            "providerOptions": {},
            "controls": {},
            "tools": [
                {
                    "type": "function",
                    "function": {
                        "name": "lookup",
                        "description": "Look up a value.",
                        "parameters": {"type": "object"},
                    },
                }
            ],
        }
    )

    assert body["input"] == "Reply with OK."
    assert "metadata" not in body
    assert "previous_response_id" not in body
    assert body["max_output_tokens"] == 8
    assert body["reasoning"] == {"effort": "high"}
    assert body["tools"] == [
        {
            "type": "function",
            "name": "lookup",
            "description": "Look up a value.",
            "parameters": {"type": "object"},
        }
    ]
