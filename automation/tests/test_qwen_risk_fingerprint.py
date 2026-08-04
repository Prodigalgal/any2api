from __future__ import annotations

import pytest

from any2api_automation.providers.qwen_risk import (
    _browser_major,
    _client_hints,
    _fingerprint_script,
)


def test_qwen_risk_fingerprint_matches_the_configured_curl_profile() -> None:
    hints = _client_hints("chrome146")
    script = _fingerprint_script("chrome146")

    assert _browser_major("chrome146") == "146"
    assert '"Chromium";v="146"' in hints["sec-ch-ua"]
    assert hints["sec-ch-ua-platform"] == '"Windows"'
    assert "uaFullVersion: '146.0.0.0'" in script
    assert "platform: 'Windows'" in script


@pytest.mark.parametrize("profile", ["", "firefox147", "chrome", "chrome149beta"])
def test_qwen_risk_fingerprint_rejects_unbounded_profiles(profile: str) -> None:
    with pytest.raises(ValueError):
        _browser_major(profile)
