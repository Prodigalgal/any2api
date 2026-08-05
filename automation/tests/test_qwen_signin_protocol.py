from __future__ import annotations

import hashlib
import json

from any2api_automation.lifecycle.browser import BrowserResult
from any2api_automation.providers import qwen
from any2api_automation.providers.qwen_fingerprint import new_qwen_fingerprint


class _Response:
    def __init__(
        self,
        status_code: int,
        payload: dict[str, object] | None = None,
        *,
        content: bytes = b"",
        content_type: str = "application/json",
    ) -> None:
        self.status_code = status_code
        self._payload = payload or {}
        self.content = content
        self.headers = {"content-type": content_type}

    @property
    def is_success(self) -> bool:
        return 200 <= self.status_code < 300

    def json(self) -> dict[str, object]:
        return self._payload

    def raise_for_status(self) -> None:
        if not self.is_success:
            raise RuntimeError(f"HTTP {self.status_code}")


def test_qwen_signin_prefers_the_current_v2_hashed_password(monkeypatch) -> None:
    calls: list[tuple[str, str]] = []

    def request(_page, url: str, _method: str, body: str, **_kwargs) -> _Response:
        password = str(json.loads(body)["password"])
        calls.append((url, password))
        return _Response(200, {"data": {"token": "current-token"}})

    monkeypatch.setattr(qwen, "_send_qwen_protocol_request", request)

    token = qwen._signin_with_current_protocol(object(), "user@example.com", "secret")

    assert token == "current-token"
    assert calls == [
        (
            "https://chat.qwen.ai/api/v2/auths/signin",
            hashlib.sha256(b"secret").hexdigest(),
        )
    ]


def test_qwen_signin_falls_back_to_v1_only_when_v2_is_unavailable(monkeypatch) -> None:
    calls: list[str] = []

    def request(_page, url: str, _method: str, _body: str, **_kwargs) -> _Response:
        calls.append(url)
        if "/api/v2/" in url:
            return _Response(404)
        return _Response(200, {"token": "legacy-token"})

    monkeypatch.setattr(qwen, "_send_qwen_protocol_request", request)

    token = qwen._signin_with_current_protocol(object(), "user@example.com", "secret")

    assert token == "legacy-token"
    assert calls == [
        "https://chat.qwen.ai/api/v2/auths/signin",
        "https://chat.qwen.ai/api/v1/auths/signin",
    ]


def test_qwen_post_registration_signin_falls_back_to_browser(monkeypatch) -> None:
    monkeypatch.setattr(qwen, "_signin_with_current_protocol", lambda *_: None)
    monkeypatch.setattr(qwen, "_signin_in_browser", lambda *_: "browser-token")

    token = qwen._signin_sync(object(), "user@example.com", "secret")

    assert token == "browser-token"


def test_qwen_protocol_request_reuses_browser_cookies_and_chrome_http2(monkeypatch) -> None:
    captured: dict[str, object] = {}
    response = _Response(200, {"ok": True})

    class Session:
        def __init__(self, **options: object) -> None:
            captured["options"] = options

        def __enter__(self):
            return self

        def __exit__(self, *_args: object) -> None:
            return None

        def request(self, method: str, url: str, **options: object) -> _Response:
            captured.update({"method": method, "url": url, **options})
            return response

    monkeypatch.setattr(
        qwen,
        "_risk_headers_from_page",
        lambda *_: {
            "bx-v": "current",
            "cookie": "cna=device; cbc=risk",
            "user-agent": "browser-user-agent",
        },
    )
    monkeypatch.setattr(qwen.curl_requests, "Session", Session)

    actual = qwen._send_qwen_protocol_request(
        object(),
        "https://chat.qwen.ai/api/v2/auths/signin",
        "POST",
        '{"email":"user@example.com"}',
        token="account-token",
        proxy_url="http://127.0.0.1:8080",
    )

    assert actual is response
    assert captured["options"] == {
        "impersonate": "chrome146",
        "http_version": "v2",
        "default_headers": False,
    }
    assert captured["data"] == '{"email":"user@example.com"}'
    assert captured["proxy"] == "http://127.0.0.1:8080"
    assert captured["headers"]["Authorization"] == "Bearer account-token"
    assert captured["headers"]["cookie"] == "cna=device; cbc=risk"


def test_qwen_lifecycle_does_not_treat_waf_challenges_as_expired_authentication() -> None:
    business_challenge = _Response(
        200,
        content=b'{"ret":["FAIL_SYS_USER_VALIDATE"],"data":{"url":"/punish?x=1"}}',
    )
    html_challenge = _Response(403, content=b"<html></html>", content_type="text/html")
    credential_rejection = _Response(403, content=b'{"error":"invalid token"}')

    assert qwen._qwen_antibot_response(business_challenge) is True
    assert qwen._qwen_antibot_response(html_challenge) is True
    assert qwen._qwen_antibot_response(credential_rejection) is False


def test_qwen_reauthentication_rotates_only_after_confirmed_runtime_drift(
    monkeypatch,
) -> None:
    attempts: list[dict[str, object]] = []
    current = {
        "email": "user@example.com",
        "password": "secret",
        "browser_fingerprint": new_qwen_fingerprint("patchright"),
        "browser_state": {"stale": True},
        "cookies": {"session": "stale"},
    }

    def run(_payload, credential, _operation):
        attempts.append(credential)
        if len(attempts) == 1:
            raise RuntimeError("Patchright runtime identity drifted from the persisted account")
        return BrowserResult("qwen", "user@example.com", {}, metadata={"healthy": True})

    monkeypatch.setattr(qwen, "_run_qwen_api_flow", run)

    result = qwen._reauthenticate_sync({}, current)

    assert result == {"healthy": True}
    assert attempts[0]["browser_fingerprint"]["backend"] == "patchright"
    assert attempts[1]["browser_fingerprint"]["backend"] == "camoufox"
    assert "browser_state" not in attempts[1]
    assert "cookies" not in attempts[1]
