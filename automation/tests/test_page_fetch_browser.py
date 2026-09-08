import pytest

from any2api_automation.providers.page_fetch_browser import _resolve_target_path


def test_endpoint_key_resolves_without_a_fallback_path() -> None:
    assert (
        _resolve_target_path("", "completion", {"completion": "/api/v0/chat/completion"})
        == "/api/v0/chat/completion"
    )


def test_missing_endpoint_key_still_requires_a_valid_fallback_path() -> None:
    with pytest.raises(ValueError, match="same-origin path"):
        _resolve_target_path("", "missing", {})
