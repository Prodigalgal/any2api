from fastapi.testclient import TestClient

from any2api_automation.main import app
from any2api_automation.providers.minmax import (
    _browser_name,
    _client_hint_headers,
    _impersonate,
    _minmax_proxy_affinity,
    _optional_proxy_parameters,
    _signed_request,
    _transport_input,
)


def test_transport_allowlist_rejects_external_and_unexpected_paths() -> None:
    for path in (
        "https://example.com/archon/api/v1/config",
        "/archon/api/v1/config?redirect=https://example.com",
        "/archon/api/v1/agent?limit=100",
        "/archon/api/v1/session/not-numeric/message",
        "/v1/api/files/request_policy/extra",
        "/v1/api/files/delete",
    ):
        response = TestClient(app).post(
            "/internal/v1/providers/minmax/transport/request",
            json={"method": "GET", "path": path, "payload": {}},
        )
        assert response.status_code == 400


def test_transport_allowlist_preserves_official_agent_query() -> None:
    assert _transport_input(
        {"method": "GET", "path": "/archon/api/v1/agent?limit=20"}, stream=False
    ) == ("GET", "/archon/api/v1/agent?limit=20", "")


def test_transport_allowlist_accepts_only_the_official_media_operations() -> None:
    assert _transport_input(
        {"method": "GET", "path": "/v1/api/files/request_policy"}, stream=False
    ) == ("GET", "/v1/api/files/request_policy", "")
    assert _transport_input(
        {
            "method": "POST",
            "path": "/v1/api/files/policy_callback",
            "body": '{"fileName":"asset.png"}',
        },
        stream=False,
    ) == (
        "POST",
        "/v1/api/files/policy_callback",
        '{"fileName":"asset.png"}',
    )


def test_lifecycle_and_inference_are_direct_without_a_scoped_pool() -> None:
    parameters = _optional_proxy_parameters({})

    assert parameters["dynamic"] is False
    assert parameters["explicit_url"] == ""
    assert parameters["node_urls"] is None


def test_minmax_registration_proxy_affinity_is_stable_per_identity_and_attempt() -> None:
    first = _minmax_proxy_affinity("Account@Example.test", 1)

    assert first == _minmax_proxy_affinity("account@example.test", 1)
    assert first != _minmax_proxy_affinity("account@example.test", 2)


def test_stream_signature_uses_the_dedicated_official_origin(monkeypatch) -> None:
    monkeypatch.setattr(
        "any2api_automation.providers.minmax._official_profile",
        lambda _proxy, _credential: ("signature", "yy", "22201"),
    )

    url, _headers = _signed_request(
        "/archon/api/v1/session/123/message",
        "POST",
        '{"content":"hello"}',
        {"token": "token", "user_id": "42", "device_id": "device"},
        stream=True,
    )

    assert url.startswith("https://agent-stream.minimax.io/archon/api/v1/session/123/message?")


def test_request_fingerprint_follows_the_account_browser() -> None:
    chrome = {
        "user_agent": "Mozilla/5.0 Chrome/150.0.0.0 Safari/537.36",
        "device_profile": {"browser_name": "chrome", "os_name": "Windows"},
    }
    firefox = {"user_agent": "Mozilla/5.0 Firefox/147.0"}

    assert _browser_name(firefox["user_agent"], "chrome") == "Firefox"
    assert _impersonate(firefox) == "firefox"
    assert _impersonate(chrome) == "chrome146"
    assert (
        '"Chromium";v="150"'
        in _client_hint_headers(chrome["user_agent"], chrome["device_profile"])["sec-ch-ua"]
    )
