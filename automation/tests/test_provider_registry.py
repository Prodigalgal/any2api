import pytest

from any2api_automation.providers.base import AutomationProvider, AutomationProviderManifest
from any2api_automation.providers.registry import AutomationProviderRegistry


class ExampleProvider(AutomationProvider):
    manifest = AutomationProviderManifest(
        id="example",
        browser_backend="patchright",
        fallback_backend=None,
        isolation="context",
        challenge_types=("ocr",),
    )


class FirstProvider(AutomationProvider):
    manifest = AutomationProviderManifest(
        id="alpha",
        browser_backend="patchright",
        fallback_backend=None,
        isolation="context",
        challenge_types=(),
        operations=("register",),
    )


class InvalidProvider(AutomationProvider):
    manifest = AutomationProviderManifest(
        id="Invalid",
        browser_backend="patchright",
        fallback_backend=None,
        isolation="context",
        challenge_types=(),
    )


class UnknownOperationProvider(AutomationProvider):
    manifest = AutomationProviderManifest(
        id="unknown",
        browser_backend="patchright",
        fallback_backend=None,
        isolation="context",
        challenge_types=(),
        operations=("rotate_magic",),
    )


def test_provider_registry_rejects_duplicate_ids() -> None:
    with pytest.raises(ValueError, match="duplicate automation provider id"):
        AutomationProviderRegistry([ExampleProvider(), ExampleProvider()])


def test_provider_registry_rejects_invalid_ids_and_operations() -> None:
    with pytest.raises(ValueError, match="invalid automation provider id"):
        AutomationProviderRegistry([InvalidProvider()])

    with pytest.raises(ValueError, match="unsupported automation operations"):
        AutomationProviderRegistry([UnknownOperationProvider()])


def test_provider_registry_is_deterministic() -> None:
    registry = AutomationProviderRegistry([ExampleProvider(), FirstProvider()])

    assert [manifest["id"] for manifest in registry.public_manifests()] == ["alpha", "example"]
