from copy import deepcopy

import pytest

from any2api_automation.providers.minmax_browser import _context_options
from any2api_automation.providers.official_browser import context_options


def execution(user_agent):
    return {
        "runtime_fingerprint": {
            "user_agent": user_agent,
            "language": "zh-CN",
            "timezone_id": "Asia/Shanghai",
            "screen": {"width": 1280, "height": 720},
        }
    }


@pytest.mark.parametrize("backend_options", [context_options, _context_options])
@pytest.mark.parametrize(
    "saved_agent,headless",
    [
        ("Mozilla/5.0 Chrome/149.0.0.0", False),
        ("Mozilla/5.0 HeadlessChrome/149.0.0.0", True),
        ("Mozilla/5.0 Firefox/152.0", False),
        ("Mozilla/5.0 HeadlessChrome/156.0.0.0", False),
        ("Mozilla/5.0 Chrome/156.0.0.0", True),
        ("", True),
    ],
)
def test_stale_or_incompatible_agents_use_browser_defaults_and_preserve_session_preferences(
    backend_options, saved_agent, headless
):
    saved = execution(saved_agent)
    before = deepcopy(saved)
    options = backend_options(
        saved, "patchright", browser_version="156.0.8078.4", headless=headless
    )
    assert "user_agent" not in options
    assert options["locale"] == "zh-CN"
    assert options["timezone_id"] == "Asia/Shanghai"
    assert options["viewport"] == {"width": 1280, "height": 720}
    assert saved == before


@pytest.mark.parametrize("headless", [True, False])
def test_compatible_agent_is_preserved(headless):
    user_agent = f"Mozilla/5.0 {'HeadlessChrome' if headless else 'Chrome'}/156.0.0.0"
    assert (
        context_options(
            execution(user_agent), "patchright", browser_version="156.0.8078.4", headless=headless
        )["user_agent"]
        == user_agent
    )


def test_camoufox_keeps_its_existing_runtime_configuration():
    assert context_options(
        execution("Mozilla/5.0 Firefox/152.0"),
        "camoufox",
        browser_version="152.0.4-beta.28",
        headless=False,
    ) == {"ignore_https_errors": True}
