import pytest

from any2api_automation.providers import provider_registry
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


class UnknownAttemptModeProvider(AutomationProvider):
    manifest = AutomationProviderManifest(
        id="unknown-mode",
        browser_backend="patchright",
        fallback_backend=None,
        isolation="context",
        challenge_types=(),
        registration_attempt_mode="replace_everything",
    )


def test_provider_registry_rejects_duplicate_ids() -> None:
    with pytest.raises(ValueError, match="duplicate automation provider id"):
        AutomationProviderRegistry([ExampleProvider(), ExampleProvider()])


def test_provider_registry_rejects_invalid_ids_and_operations() -> None:
    with pytest.raises(ValueError, match="invalid automation provider id"):
        AutomationProviderRegistry([InvalidProvider()])

    with pytest.raises(ValueError, match="unsupported automation operations"):
        AutomationProviderRegistry([UnknownOperationProvider()])

    with pytest.raises(ValueError, match="unsupported registration attempt mode"):
        AutomationProviderRegistry([UnknownAttemptModeProvider()])


def test_provider_manifest_rejects_mixed_inference_transport_modes() -> None:
    with pytest.raises(ValueError, match="must use camoufox_browser_runtime"):
        AutomationProviderManifest(
            id="mixed",
            browser_backend="patchright",
            fallback_backend=None,
            isolation="context",
            challenge_types=(),
            inference_transport=True,
        )

    with pytest.raises(ValueError, match="cannot declare an inference runtime"):
        AutomationProviderManifest(
            id="lifecycle-only",
            browser_backend="camoufox",
            fallback_backend=None,
            isolation="context",
            challenge_types=(),
            inference_runtime="camoufox_browser_runtime",
        )


def test_provider_registry_is_deterministic() -> None:
    registry = AutomationProviderRegistry([ExampleProvider(), FirstProvider()])

    assert [manifest["id"] for manifest in registry.public_manifests()] == ["alpha", "example"]


def test_all_inference_provider_manifests_use_camoufox_runtime() -> None:
    manifests = {
        str(manifest["id"]): manifest
        for manifest in provider_registry.public_manifests()
        if manifest["inference_transport"]
    }

    assert set(manifests) == {
        "deepseek",
        "glm",
        "grok",
        "grok_console",
        "grok_web",
        "longcat",
        "mimo",
        "minmax",
        "qwen",
    }
    assert {str(manifest["inference_runtime"]) for manifest in manifests.values()} == {
        "camoufox_browser_runtime"
    }
