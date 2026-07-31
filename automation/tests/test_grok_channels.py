from any2api_automation.lifecycle.browser_session import BrowserSession
from any2api_automation.providers.sso_channel import probe_result, session_cookies


def test_sso_channel_cookie_projection_and_probe_classification() -> None:
    assert session_cookies({"sso": "token"}) == {"sso": "token", "sso-rw": "token"}
    assert probe_result(200, True)["healthy"] is True
    assert probe_result(401, False)["terminal"] is True
    assert probe_result(403, False)["auth_expired"] is False
    assert probe_result(429, False)["error_class"] == "UpstreamRateLimited"


def test_browser_session_persists_only_cloudflare_context() -> None:
    with BrowserSession(
        origin="https://grok.com",
        credential={
            "sso": "must-not-be-exported",
            "cloudflare_cookies": "cf_clearance=clear; __cf_bm=bm; application=value",
            "user_agent": "test-browser",
        },
        cookie_domains=(".grok.com",),
    ) as session:
        patch = session.credential_patch()

    assert patch == {
        "cloudflare_cookies": "__cf_bm=bm; cf_clearance=clear",
        "user_agent": "test-browser",
        "browser_profile": "chrome136",
    }
    assert "sso" not in patch
    assert "application" not in patch["cloudflare_cookies"]


def test_browser_session_does_not_create_empty_context_patch() -> None:
    with BrowserSession(origin="https://console.x.ai", credential={}) as session:
        assert session.credential_patch() is None
        assert "Chrome/136.0.0.0" in session.user_agent
