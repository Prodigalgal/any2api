import base64
from contextlib import contextmanager
from types import SimpleNamespace

import pytest

from any2api_automation import browser_transport
from any2api_automation.browser_transport import (
    BrowserRequest,
    BrowserSessionOpenRequest,
    BrowserWebSocketOpenRequest,
    BrowserWebSocketSendRequest,
    _allowed_origin,
    _binding_id,
    _cookie_domains,
    _relative_path,
    _request,
    _request_headers,
    _request_origin,
    _websocket_url,
    close_websocket,
    manager,
    open_websocket,
    receive_websocket,
    send_websocket,
)
from any2api_automation.config import settings
from any2api_automation.lifecycle.browser_session import BrowserSession


@pytest.fixture(autouse=True)
def allowed_origins() -> None:
    config = settings()
    previous = config.browser_transport_allowed_origins
    config.browser_transport_allowed_origins = (
        "https://grok.com,https://accounts.x.ai,https://console.x.ai"
    )
    try:
        yield
    finally:
        config.browser_transport_allowed_origins = previous


def test_browser_transport_accepts_only_allowlisted_https_origins() -> None:
    assert _allowed_origin("https://grok.com/") == "https://grok.com"
    with pytest.raises(ValueError, match="allowlisted"):
        _allowed_origin("https://169.254.169.254")
    with pytest.raises(ValueError, match="HTTPS origin"):
        _allowed_origin("http://grok.com")


def test_browser_transport_validates_proxy_node_offset() -> None:
    request = BrowserSessionOpenRequest(
        origin="https://grok.com",
        proxy_node_offset=2,
    )

    assert request.proxy_node_offset == 2
    with pytest.raises(ValueError):
        BrowserSessionOpenRequest(
            origin="https://grok.com",
            proxy_node_offset=-1,
        )


def test_browser_transport_rejects_cross_origin_cookie_domains() -> None:
    assert _cookie_domains(("https://grok.com",), [".grok.com"]) == (".grok.com",)
    assert _cookie_domains(
        ("https://grok.com", "https://accounts.x.ai"), [".grok.com", ".x.ai"]
    ) == (".grok.com", ".x.ai")
    with pytest.raises(ValueError, match="cookie domain"):
        _cookie_domains(("https://grok.com",), ["x.ai"])


def test_browser_binding_changes_with_proxy_fingerprint_and_affinity() -> None:
    baseline = _binding_id(
        "https://grok.com", "http://proxy-a:8080", "ua", "chrome136", "account-a", "r1"
    )

    assert len(baseline) == 64
    assert baseline != _binding_id(
        "https://grok.com", "http://proxy-b:8080", "ua", "chrome136", "account-a", "r1"
    )
    assert baseline != _binding_id(
        "https://grok.com", "http://proxy-a:8080", "ua-2", "chrome136", "account-a", "r1"
    )
    assert baseline != _binding_id(
        "https://grok.com", "http://proxy-a:8080", "ua", "chrome136", "account-b", "r1"
    )
    assert baseline != _binding_id(
        "https://grok.com", "http://proxy-a:8080", "ua", "chrome136", "account-a", "r2"
    )


def test_clearance_is_scoped_to_primary_origin_not_every_sso_domain() -> None:
    with BrowserSession(
        origin="https://grok.com",
        credential={},
        cookie_domains=(".grok.com", ".x.ai"),
        initial_cookies={"sso": "session"},
    ) as browser:
        browser.apply_clearance_context(
            {
                "cloudflare_cookies": "cf_clearance=clear",
                "user_agent": browser.user_agent,
                "browser_profile": browser.profile.impersonate,
            }
        )
        cookies = list(browser.client.cookies.jar)

    assert {cookie.domain for cookie in cookies if cookie.name == "sso"} == {
        ".grok.com",
        ".x.ai",
    }
    assert {cookie.domain for cookie in cookies if cookie.name == "cf_clearance"} == {"grok.com"}


def test_browser_session_transfers_aws_waf_clearance_without_cloudflare_alias() -> None:
    with BrowserSession(origin="https://chat.deepseek.com", credential={}) as browser:
        patch = browser.apply_clearance_context(
            {
                "clearance_cookies": "aws-waf-token=clear",
                "user_agent": browser.user_agent,
                "browser_profile": browser.profile.impersonate,
            }
        )
        cookies = list(browser.client.cookies.jar)

    assert patch["clearance_cookies"] == "aws-waf-token=clear"
    assert "cloudflare_cookies" not in patch
    assert {cookie.domain for cookie in cookies if cookie.name == "aws-waf-token"} == {
        "chat.deepseek.com"
    }


def test_browser_session_initializes_bearer_without_allowing_request_override() -> None:
    with BrowserSession(
        origin="https://grok.com",
        credential={},
        bearer_token="provider-token",
    ) as browser:
        assert browser.client.headers["Authorization"] == "Bearer provider-token"

    entry = SimpleNamespace(browser=SimpleNamespace(origin="https://grok.com"))
    with pytest.raises(ValueError, match="blocked header"):
        _request_headers(
            entry,
            BrowserRequest(method="GET", path="/", headers={"Authorization": "Bearer other"}),
            "https://grok.com",
        )


def test_browser_request_can_only_select_a_declared_origin() -> None:
    entry = SimpleNamespace(
        browser=SimpleNamespace(origin="https://grok.com"),
        origins=("https://grok.com", "https://accounts.x.ai"),
    )
    assert _request_origin(entry, "") == "https://grok.com"
    assert _request_origin(entry, "https://accounts.x.ai") == "https://accounts.x.ai"
    with pytest.raises(ValueError, match="not declared"):
        _request_origin(entry, "https://console.x.ai")


def test_websocket_url_is_derived_from_a_declared_https_origin() -> None:
    entry = SimpleNamespace(
        browser=SimpleNamespace(origin="https://grok.com"),
        origins=("https://grok.com",),
    )
    assert _websocket_url(entry, "", "/ws/imagine/listen") == ("wss://grok.com/ws/imagine/listen")
    with pytest.raises(ValueError, match="path"):
        _websocket_url(entry, "", "wss://attacker.invalid/ws")


def test_websocket_operations_reuse_the_declared_browser_session(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, object] = {}

    class FakeWebSocket:
        def send_json(self, value: object) -> None:
            captured["sent"] = value

        def recv(self) -> tuple[bytes, int]:
            return b'{"type":"json"}', 1

        def close(self) -> None:
            captured["closed"] = True

    websocket = FakeWebSocket()

    def ws_connect(url: str, **kwargs: object) -> FakeWebSocket:
        captured.update(url=url, **kwargs)
        return websocket

    entry = SimpleNamespace(
        browser=SimpleNamespace(
            origin="https://grok.com",
            client=SimpleNamespace(ws_connect=ws_connect),
        ),
        origins=("https://grok.com",),
        websockets={},
    )

    @contextmanager
    def lease(_: str):
        yield entry

    monkeypatch.setattr(manager, "lease", lease)
    opened = open_websocket("session", BrowserWebSocketOpenRequest(path="/ws/imagine/listen"))
    websocket_id = str(opened["websocket_id"])
    send_websocket(
        "session", websocket_id, BrowserWebSocketSendRequest(json_body={"type": "reset"})
    )
    received = receive_websocket("session", websocket_id)
    closed = close_websocket("session", websocket_id)

    assert captured["url"] == "wss://grok.com/ws/imagine/listen"
    assert captured["sent"] == {"type": "reset"}
    assert base64.b64decode(str(received["body_base64"])) == b'{"type":"json"}'
    assert received["flags"] == 1
    assert closed["closed"] is True
    assert captured["closed"] is True


def test_browser_websocket_mode_uses_the_thread_owned_browser_bridge(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, object] = {}

    class FakeBrowserWebSocket:
        def __init__(self, **kwargs: object) -> None:
            captured.update(kwargs)

        def close(self) -> None:
            captured["closed"] = True

    entry = SimpleNamespace(
        browser=SimpleNamespace(origin="https://grok.com"),
        origins=("https://grok.com",),
        proxy_url="http://127.0.0.1:10808",
        websockets={},
    )

    @contextmanager
    def lease(_: str):
        yield entry

    monkeypatch.setattr(manager, "lease", lease)
    monkeypatch.setattr(browser_transport, "BrowserWebSocket", FakeBrowserWebSocket)

    opened = open_websocket(
        "session",
        BrowserWebSocketOpenRequest(
            path="/ws/mgw/?uid=user-id",
            transport_mode="browser",
            timeout_seconds=45,
        ),
    )
    websocket_id = str(opened["websocket_id"])
    closed = close_websocket("session", websocket_id)

    assert captured == {
        "browser": entry.browser,
        "proxy_url": "http://127.0.0.1:10808",
        "target_url": "wss://grok.com/ws/mgw/?uid=user-id",
        "timeout_seconds": 45,
        "closed": True,
    }
    assert closed["closed"] is True


def test_browser_transport_rejects_absolute_and_traversal_paths() -> None:
    assert _relative_path("/rest/modes?locale=en") == "/rest/modes?locale=en"
    for value in ("https://internal.invalid/", "//internal.invalid/", "/../admin"):
        with pytest.raises(ValueError, match="path"):
            _relative_path(value)


def test_browser_transport_blocks_credential_and_routing_headers() -> None:
    entry = SimpleNamespace(browser=SimpleNamespace(origin="https://grok.com"))
    request = BrowserRequest(
        method="POST",
        path="/rest/chat",
        headers={"x-statsig-id": "signed"},
        fingerprint_profile="same_origin_fetch",
    )
    headers = _request_headers(entry, request, "https://grok.com")
    assert headers["Origin"] == "https://grok.com"
    assert headers["Referer"] == "https://grok.com/"
    assert headers["Sec-Fetch-Mode"] == "cors"
    assert headers["x-statsig-id"] == "signed"
    for name in ("Cookie", "Authorization", "Host", "Proxy-Authorization"):
        with pytest.raises(ValueError, match="blocked header"):
            _request_headers(
                entry,
                BrowserRequest(method="GET", path="/", headers={name: "secret"}),
                "https://grok.com",
            )


def test_browser_transport_owns_request_fingerprint_headers() -> None:
    entry = SimpleNamespace(browser=SimpleNamespace(origin="https://grok.com"))
    with pytest.raises(ValueError, match="cannot override fingerprint"):
        _request_headers(
            entry,
            BrowserRequest(
                method="GET",
                path="/index",
                headers={"Sec-Fetch-Site": "cross-site"},
                fingerprint_profile="navigation",
            ),
            "https://grok.com",
        )


def test_browser_transport_decodes_bounded_binary_body_and_owns_referer() -> None:
    captured: dict[str, object] = {}

    def send(method: str, url: str, **kwargs: object) -> object:
        captured.update(method=method, url=url, **kwargs)
        return object()

    entry = SimpleNamespace(
        browser=SimpleNamespace(origin="https://grok.com", client=SimpleNamespace(request=send)),
        origins=("https://grok.com", "https://accounts.x.ai"),
    )
    _request(
        entry,
        BrowserRequest(
            method="POST",
            path="/grpc",
            origin="https://accounts.x.ai",
            referer_path="/accept-tos",
            fingerprint_profile="same_origin_fetch",
            body_base64=base64.b64encode(b"\x00\x01").decode(),
        ),
        stream=False,
    )

    assert captured["url"] == "https://accounts.x.ai/grpc"
    assert captured["data"] == b"\x00\x01"
    assert captured["headers"]["Referer"] == "https://accounts.x.ai/accept-tos"
