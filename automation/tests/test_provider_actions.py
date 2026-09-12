from collections.abc import AsyncIterator
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from any2api_automation.main import app
from any2api_automation.providers import provider_registry
from any2api_automation.providers.actions import (
    ProviderAction,
    ProviderActionRequest,
)
from any2api_automation.providers.base import (
    API_TRANSPORT,
    CAMOUFOX_BROWSER_RUNTIME,
    AutomationProvider,
    AutomationProviderManifest,
    validate_semantic_command,
)
from any2api_automation.providers.channels import (
    ActionBindingRegistry,
    ActionNotSupported,
    ProviderActionDispatcher,
)


class DummyProvider(AutomationProvider):
    manifest = AutomationProviderManifest(
        id="dummy",
        browser_backend="patchright",
        fallback_backend=None,
        isolation="context",
        challenge_types=(),
        operations=("keepalive",),
        inference_transport=True,
        inference_runtime=CAMOUFOX_BROWSER_RUNTIME,
        inference_actions=("model_discovery", "chat"),
    )

    async def keepalive(self, payload: dict[str, object]) -> dict[str, object]:
        return {"healthy": True, "payload_is_object": isinstance(payload, dict)}

    async def transport_request(self, payload: dict[str, object]) -> dict[str, object]:
        return {"operation": payload["operation"], "channel": payload["runtime_mode"]}

    async def transport_stream(self, payload: dict[str, object]) -> AsyncIterator[bytes]:
        yield f"{payload['operation']}:{payload['runtime_mode']}\n".encode()


def test_legacy_transport_is_converted_to_a_stable_action() -> None:
    request = ProviderActionRequest.from_legacy(
        "dummy",
        "runtime",
        "models",
        payload={"credential": {"token": "redacted"}},
    )

    assert request.action is ProviderAction.MODEL_DISCOVERY
    assert request.channel == CAMOUFOX_BROWSER_RUNTIME
    assert request.legacy_payload()["operation"] == "models"
    assert request.legacy_payload()["action"] == "model_discovery"


def test_semantic_action_rejects_an_opaque_raw_request() -> None:
    with pytest.raises(ValueError, match="must not contain rawRequest"):
        ProviderActionRequest(
            provider_id="dummy",
            action=ProviderAction.CHAT,
            channel=CAMOUFOX_BROWSER_RUNTIME,
            semantic_command={"rawRequest": {"model": "upstream"}},
        )


def test_shared_semantic_command_validation_requires_canonical_sections() -> None:
    command = {
        "schemaVersion": 1,
        "model": "model-1",
        "messages": [{"role": "user", "content": "hello"}],
        "generation": {},
        "reasoning": {},
        "tools": [],
        "providerOptions": {},
        "controls": {},
    }

    assert validate_semantic_command(command, "fixture") is command

    for field in ("generation", "reasoning", "providerOptions", "controls", "tools"):
        invalid = {**command, field: None}
        with pytest.raises((TypeError, ValueError), match=field):
            validate_semantic_command(invalid, "fixture")


@pytest.mark.asyncio
async def test_dispatcher_uses_the_declared_channel_and_action_binding() -> None:
    provider = DummyProvider()
    providers = SimpleNamespace(require=lambda provider_id: provider)
    bindings = ActionBindingRegistry([provider])
    dispatcher = ProviderActionDispatcher(providers, bindings)

    result = await dispatcher.execute(
        ProviderActionRequest(
            provider_id="dummy",
            action=ProviderAction.KEEPALIVE,
            channel=CAMOUFOX_BROWSER_RUNTIME,
            payload={},
        )
    )
    assert result == {"healthy": True, "payload_is_object": True}

    stream = await dispatcher.stream(
        ProviderActionRequest(
            provider_id="dummy",
            action=ProviderAction.CHAT,
            channel=CAMOUFOX_BROWSER_RUNTIME,
            payload={},
            semantic_command={
                "schemaVersion": 1,
                "model": "fixture-model",
                "messages": [{"role": "user", "content": "hello"}],
                "generation": {},
                "reasoning": {},
                "tools": [],
                "providerOptions": {},
                "controls": {},
            },
        )
    )
    assert [chunk async for chunk in stream] == [b"chat:camoufox_browser_runtime\n"]


@pytest.mark.asyncio
async def test_dispatcher_rejects_chat_before_provider_binding_executes() -> None:
    provider = DummyProvider()
    providers = SimpleNamespace(require=lambda provider_id: provider)
    bindings = ActionBindingRegistry([provider])
    dispatcher = ProviderActionDispatcher(providers, bindings)

    with pytest.raises(ValueError, match="semantic command schema"):
        await dispatcher.stream(
            ProviderActionRequest(
                provider_id="dummy",
                action=ProviderAction.CHAT,
                channel=CAMOUFOX_BROWSER_RUNTIME,
            )
        )


@pytest.mark.asyncio
async def test_dispatcher_rejects_an_explicit_operation_mismatch() -> None:
    provider = DummyProvider()
    providers = SimpleNamespace(require=lambda provider_id: provider)
    bindings = ActionBindingRegistry([provider])
    dispatcher = ProviderActionDispatcher(providers, bindings)

    with pytest.raises(ValueError, match="does not match operation"):
        await dispatcher.execute(
            ProviderActionRequest(
                provider_id="dummy",
                action=ProviderAction.KEEPALIVE,
                channel=CAMOUFOX_BROWSER_RUNTIME,
                operation="register",
                payload={},
            )
        )


def test_minmax_exposes_api_and_runtime_actions_separately() -> None:
    manifest = next(item for item in provider_registry.public_manifests() if item["id"] == "minmax")
    actions = {(item["channel"], item["action"]) for item in manifest["actions"]}

    assert ("api", "chat") in actions
    assert (CAMOUFOX_BROWSER_RUNTIME, "chat") in actions
    assert ("api", "daily_checkin") not in actions
    assert (CAMOUFOX_BROWSER_RUNTIME, "daily_checkin") in actions


def test_runtime_action_matrix_matches_each_provider_manifest() -> None:
    expected = {
        "arena": {"model_discovery", "chat"},
        "deepseek": {"model_discovery", "chat"},
        "glm": {"model_discovery", "chat"},
        "grok": {"model_discovery", "chat"},
        "grok_console": {"chat"},
        "grok_web": {"model_discovery", "chat"},
        "longcat": {"chat"},
        "mimo": {"model_discovery", "chat"},
        "minmax": {
            "model_discovery",
            "chat",
            "provider_query",
            "media_policy",
            "media_callback",
            "raw_request",
        },
        "qwen": {"model_discovery", "chat"},
    }

    for manifest in provider_registry.public_manifests():
        provider_id = str(manifest["id"])
        if not manifest["inference_transport"]:
            continue
        actions = {(str(item["channel"]), str(item["action"])) for item in manifest["actions"]}
        runtime_actions = {
            action for channel, action in actions if channel == CAMOUFOX_BROWSER_RUNTIME
        }
        lifecycle_actions = {str(operation) for operation in manifest["operations"]}
        assert runtime_actions - lifecycle_actions == expected[provider_id]
        assert set(manifest["inference_actions"]) == expected[provider_id]


def test_api_action_matrix_matches_declared_api_mode() -> None:
    expected_api = {
        "arena": {"model_discovery", "chat"},
        "deepseek": {"model_discovery", "chat"},
        "glm": {"model_discovery", "chat"},
        "longcat": {"chat"},
        "mimo": {"model_discovery", "chat"},
        "minmax": {
            "model_discovery",
            "chat",
            "provider_query",
            "media_policy",
            "media_callback",
            "raw_request",
        },
        "qwen": {"model_discovery", "chat"},
    }

    for manifest in provider_registry.public_manifests():
        provider_id = str(manifest["id"])
        actions = {(str(item["channel"]), str(item["action"])) for item in manifest["actions"]}
        api_actions = {action for channel, action in actions if channel == API_TRANSPORT}
        if API_TRANSPORT in manifest["inference_modes"]:
            assert api_actions == expected_api[provider_id]
        else:
            assert api_actions == set()


def test_lifecycle_actions_are_runtime_only_for_all_installed_providers() -> None:
    for manifest in provider_registry.public_manifests():
        actions = {(str(item["channel"]), str(item["action"])) for item in manifest["actions"]}
        for operation in manifest["operations"]:
            action = ProviderAction.from_legacy_operation(str(operation)).value
            assert (CAMOUFOX_BROWSER_RUNTIME, action) in actions
            assert ("api", action) not in actions


def test_unsupported_action_channel_is_rejected_before_provider_execution() -> None:
    provider = DummyProvider()
    registry = ActionBindingRegistry([provider])

    with pytest.raises(ActionNotSupported):
        registry.resolve(provider, ProviderAction.CHAT, "api")


def test_action_endpoint_returns_capability_error_for_missing_channel_binding() -> None:
    response = TestClient(app).post(
        "/internal/v1/providers/minmax/actions/request",
        json={"action": "daily_checkin", "channel": "api"},
    )

    assert response.status_code == 501
    assert "provider action is not implemented" in response.json()["detail"]


def test_action_endpoint_preserves_unknown_provider_not_found_status() -> None:
    response = TestClient(app).post(
        "/internal/v1/providers/not-installed/actions/request",
        json={"action": "chat", "channel": "runtime"},
    )

    assert response.status_code == 404
