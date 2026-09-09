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
    CAMOUFOX_BROWSER_RUNTIME,
    AutomationProvider,
    AutomationProviderManifest,
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
        )
    )
    assert [chunk async for chunk in stream] == [b"chat:camoufox_browser_runtime\n"]


def test_minmax_exposes_api_and_runtime_actions_separately() -> None:
    manifest = next(item for item in provider_registry.public_manifests() if item["id"] == "minmax")
    actions = {(item["channel"], item["action"]) for item in manifest["actions"]}

    assert ("api", "chat") in actions
    assert (CAMOUFOX_BROWSER_RUNTIME, "chat") in actions
    assert ("api", "daily_checkin") not in actions
    assert (CAMOUFOX_BROWSER_RUNTIME, "daily_checkin") in actions


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
