from __future__ import annotations

import hashlib

from any2api_automation.providers import qwen


class _Response:
    def __init__(self, status_code: int, payload: dict[str, object] | None = None) -> None:
        self.status_code = status_code
        self._payload = payload or {}

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
    monkeypatch.setattr(qwen, "_risk_headers_from_page", lambda *_: {"bx-v": "current"})

    def post(url: str, **kwargs) -> _Response:
        password = str(kwargs["json"]["password"])
        calls.append((url, password))
        return _Response(200, {"data": {"token": "current-token"}})

    monkeypatch.setattr(qwen.httpx, "post", post)

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
    monkeypatch.setattr(qwen, "_risk_headers_from_page", lambda *_: {"bx-v": "current"})

    def post(url: str, **_kwargs) -> _Response:
        calls.append(url)
        if "/api/v2/" in url:
            return _Response(404)
        return _Response(200, {"token": "legacy-token"})

    monkeypatch.setattr(qwen.httpx, "post", post)

    token = qwen._signin_with_current_protocol(object(), "user@example.com", "secret")

    assert token == "legacy-token"
    assert calls == [
        "https://chat.qwen.ai/api/v2/auths/signin",
        "https://chat.qwen.ai/api/v1/auths/signin",
    ]
