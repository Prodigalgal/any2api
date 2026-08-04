from __future__ import annotations

import base64
import json
from unittest.mock import AsyncMock

import pytest
from pydantic import ValidationError

from any2api_automation.providers.qwen_risk import (
    NativeBrowserRequest,
    QwenNativeBrowserTransport,
    _qwen_punish_url,
)


def test_qwen_native_browser_request_accepts_provider_scoped_paths() -> None:
    request = NativeBrowserRequest(
        path="/api/v2/chat/completions?chat_id=chat-1",
        body="{}",
        bearer_token="token-value-that-is-long-enough",
        referer_path="/c/chat-1",
    )

    assert request.path.endswith("chat_id=chat-1")
    assert request.referer_path == "/c/chat-1"


@pytest.mark.parametrize(
    ("path", "referer"),
    [
        ("https://example.com/api/v2/chat", "/"),
        ("//example.com/api/v2/chat", "/"),
        ("/api/v2/chat", "https://example.com/"),
        ("/admin/secrets", "/"),
    ],
)
def test_qwen_native_browser_request_rejects_cross_origin_paths(path: str, referer: str) -> None:
    with pytest.raises(ValidationError):
        NativeBrowserRequest(
            path=path,
            bearer_token="token-value-that-is-long-enough",
            referer_path=referer,
        )


def test_qwen_native_browser_request_rejects_cookie_header_injection() -> None:
    with pytest.raises(ValidationError):
        NativeBrowserRequest(
            path="/api/v2/chats/new",
            bearer_token="token-value-that-is-long-enough",
            cookies={"session": "value\r\nX-Injected: true"},
        )


@pytest.mark.asyncio
async def test_qwen_native_transport_passes_the_path_to_the_browser_script() -> None:
    class FakePage:
        def is_closed(self) -> bool:
            return False

        async def evaluate(self, script: str, payload: dict[str, object]) -> dict[str, object]:
            assert "credentials: 'same-origin'" in script
            assert payload["path"] == "/api/v2/chats/new"
            assert str(payload["timezone"]).isascii()
            assert str(payload["timezone"]).endswith("GMT+0800")
            return {
                "status": 200,
                "contentType": "application/json",
                "requestId": "request-id",
                "retryAfter": "",
                "bodyBase64": base64.b64encode(b'{"data":{"id":"chat"}}').decode(),
            }

    transport = QwenNativeBrowserTransport()
    transport._page = FakePage()
    transport._activate_account = AsyncMock()
    transport._ensure_baxia_ready = AsyncMock()
    transport._frontend_version = "current"
    transport._prepare_authenticated_surface = AsyncMock()

    response = await transport.fetch(
        NativeBrowserRequest(
            path="/api/v2/chats/new",
            body="{}",
            bearer_token="token-value-that-is-long-enough",
            referer_path="/c/new-chat",
        )
    )

    assert response["status"] == 200
    assert base64.b64decode(response["body_base64"]) == b'{"data":{"id":"chat"}}'


@pytest.mark.asyncio
async def test_qwen_native_transport_hydrates_login_state_on_the_real_chat_route() -> None:
    class FakePage:
        def __init__(self) -> None:
            self.url = "https://chat.qwen.ai/"
            self.token = ""
            self.visited = ""

        async def evaluate(self, _script: str, token: str) -> None:
            self.token = token

        async def goto(self, url: str, **_kwargs: object) -> None:
            self.url = url
            self.visited = url

        async def wait_for_function(self, _script: str, **_kwargs: object) -> None:
            return None

        async def wait_for_timeout(self, _timeout: int) -> None:
            return None

    transport = QwenNativeBrowserTransport()
    transport._page = FakePage()
    transport._activate_account = AsyncMock()
    transport._ensure_baxia_ready = AsyncMock()
    request = NativeBrowserRequest(
        path="/api/v2/chats/new",
        body="{}",
        bearer_token="token-value-that-is-long-enough",
        referer_path="/c/new-chat",
    )

    await transport._prepare_authenticated_surface(request)

    assert transport._page.token == request.bearer_token
    assert transport._page.visited == "https://chat.qwen.ai/c/new-chat"


def test_qwen_punish_url_accepts_only_the_configured_qwen_origin() -> None:
    valid = json.dumps(
        {
            "ret": ["FAIL_SYS_USER_VALIDATE", "captcha required"],
            "data": {
                "url": (
                    "https://chat.qwen.ai:443/api/v2/chat/completions/"
                    "_____tmd_____/punish?x5step=2&action=captcha"
                )
            },
        }
    ).encode()
    foreign = valid.replace(b"chat.qwen.ai", b"example.com")

    assert _qwen_punish_url(valid).startswith("https://chat.qwen.ai:443/")
    assert _qwen_punish_url(foreign) == ""


@pytest.mark.asyncio
async def test_qwen_native_transport_solves_and_replays_one_punished_request() -> None:
    punish_url = (
        "https://chat.qwen.ai/api/v2/chat/completions/_____tmd_____/punish?x5step=2&action=captcha"
    )
    punished = json.dumps(
        {
            "ret": ["FAIL_SYS_USER_VALIDATE", "captcha required"],
            "data": {"url": punish_url},
        }
    ).encode()
    completed = b'data: {"choices":[{"delta":{"content":"ok"}}]}\n\n'

    class FakePage:
        def __init__(self) -> None:
            self.calls = 0

        def is_closed(self) -> bool:
            return False

        async def evaluate(self, _script: str, _payload: dict[str, object]) -> dict[str, object]:
            self.calls += 1
            body = punished if self.calls == 1 else completed
            return {
                "status": 200,
                "contentType": "application/json" if self.calls == 1 else "text/event-stream",
                "requestId": "request-id",
                "retryAfter": "",
                "bodyBase64": base64.b64encode(body).decode(),
            }

    transport = QwenNativeBrowserTransport()
    transport._page = FakePage()
    transport._frontend_version = "current"
    transport._prepare_authenticated_surface = AsyncMock()
    transport._challenge_solver.solve = AsyncMock(
        return_value=type("Outcome", (), {"attempts": 1, "diagnostic": "ok"})()
    )
    transport._load_page_runtime = AsyncMock()

    response = await transport.fetch(
        NativeBrowserRequest(
            path="/api/v2/chat/completions?chat_id=chat",
            body="{}",
            bearer_token="token-value-that-is-long-enough",
            referer_path="/c/chat",
        )
    )

    assert transport._page.calls == 2
    transport._challenge_solver.solve.assert_awaited_once_with(transport._page, punish_url)
    transport._load_page_runtime.assert_awaited_once()
    assert base64.b64decode(response["body_base64"]) == completed
