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


def test_provider_registry_rejects_duplicate_ids() -> None:
    with pytest.raises(ValueError, match="duplicate automation provider id"):
        AutomationProviderRegistry([ExampleProvider(), ExampleProvider()])
