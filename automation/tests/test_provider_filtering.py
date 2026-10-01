from unittest.mock import patch

from any2api_automation.config import Settings
from any2api_automation.providers import _discover


def test_discover_all_providers_when_unfiltered():
    with patch(
        "any2api_automation.providers.settings", return_value=Settings(enabled_providers="")
    ):
        providers = _discover()
        provider_ids = {p.manifest.id for p in providers}
        assert "arena" in provider_ids
        assert "grok_web" in provider_ids
        assert len(provider_ids) > 1


def test_discover_only_arena_when_filtered():
    with patch(
        "any2api_automation.providers.settings", return_value=Settings(enabled_providers="arena")
    ):
        providers = _discover()
        provider_ids = {p.manifest.id for p in providers}
        assert provider_ids == {"arena"}
