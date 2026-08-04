from __future__ import annotations

import base64
import json
import re
from typing import ClassVar, Self
from unittest.mock import AsyncMock

import pytest
from pydantic import ValidationError

from any2api_automation.providers.qwen_risk import (
    NativeBrowserRequest,
    QwenNativeBrowserTransport,
    _qwen_punish_url,
    _qwen_sse_finished,
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

    transport = QwenNativeBrowserTransport()
    transport._page = FakePage()
    transport._activate_account = AsyncMock()
    transport._ensure_baxia_ready = AsyncMock()
    transport._frontend_version = "current"
    transport._prepare_authenticated_surface = AsyncMock()
    transport._fetch_in_main_world = AsyncMock(
        return_value=(
            {
                "status": 200,
                "contentType": "application/json",
                "requestId": "request-id",
                "retryAfter": "",
                "bodyBase64": base64.b64encode(b'{"data":{"id":"chat"}}').decode(),
            },
            b'{"data":{"id":"chat"}}',
        )
    )

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
    payload = transport._fetch_in_main_world.await_args.args[0]
    assert payload["path"] == "/api/v2/chats/new"
    assert str(payload["timezone"]).isascii()
    assert str(payload["timezone"]).endswith("GMT+0800")


@pytest.mark.asyncio
async def test_qwen_native_transport_captures_baxia_then_sends_browser_shaped(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    body = b'{"data":{"id":"chat"}}'
    captured_headers: dict[str, str] = {}

    def send(_payload: dict[str, object], headers: dict[str, str]):
        captured_headers.update(headers)
        return {
            "status": 200,
            "contentType": "application/json",
            "requestId": "upstream-id",
            "retryAfter": "",
            "bodyBase64": base64.b64encode(body).decode(),
        }, body

    monkeypatch.setattr("any2api_automation.providers.qwen_risk._send_qwen_request", send)

    class FakeRequest:
        url = "https://chat.qwen.ai/api/v2/chats/new"
        method = "POST"
        headers: ClassVar = {"x-request-id": "request-id"}

        async def all_headers(self) -> dict[str, str]:
            return {
                "x-request-id": "request-id",
                "bx-ua": "uab",
                "bx-umidtoken": "umid",
                "bx-v": "2.5.37",
            }

    class FakeRequestInfo:
        async def __aenter__(self) -> Self:
            return self

        async def __aexit__(self, *_args: object) -> None:
            return None

        @property
        def value(self):
            async def resolve() -> FakeRequest:
                return FakeRequest()

            return resolve()

    class FakeRoute:
        def __init__(self) -> None:
            self.aborted = False

        async def abort(self) -> None:
            self.aborted = True

    class FakePage:
        def __init__(self) -> None:
            self.script = ""
            self.routed = False
            self.unrouted = False
            self.route_handler = None
            self.fake_route = FakeRoute()

        async def route(self, _url: str, handler, **_kwargs: object) -> None:
            self.routed = True
            self.route_handler = handler

        async def unroute(self, _url: str) -> None:
            self.unrouted = True

        def expect_request(self, predicate, **_kwargs: object) -> FakeRequestInfo:
            assert predicate(FakeRequest())
            return FakeRequestInfo()

        async def add_script_tag(self, *, content: str) -> None:
            self.script = content
            await self.route_handler(self.fake_route)

    page = FakePage()
    transport = QwenNativeBrowserTransport()
    transport._page = page
    payload = {
        "url": "https://chat.qwen.ai/api/v2/chats/new",
        "path": "/api/v2/chats/new",
        "method": "POST",
        "body": "{}",
        "bearerToken": "token-value-that-is-long-enough",
        "useBearer": True,
        "referrer": "https://chat.qwen.ai/c/new-chat",
        "timeoutMs": 60_000,
        "maximumBytes": 1024,
        "version": "current",
        "requestId": "request-id",
        "timezone": "Wed Aug 05 2026 12:00:00 GMT+0800",
    }

    result, actual_body = await transport._fetch_in_main_world(payload)

    assert "fetch(request.url" in page.script
    assert page.routed is True
    assert page.unrouted is True
    assert page.fake_route.aborted is True
    assert "token-value-that-is-long-enough" not in page.script
    encoded_match = re.search(r"atob\('([^']+)'\)", page.script)
    assert encoded_match is not None
    encoded = encoded_match.group(1)
    assert json.loads(base64.b64decode(encoded))["bearerToken"] == payload["bearerToken"]
    assert captured_headers["bx-v"] == "2.5.37"
    assert result["requestId"] == "upstream-id"
    assert actual_body == body


@pytest.mark.parametrize(
    "body",
    [
        b"data: [DONE]\n\n",
        b'data: {"choices":[{"finish_reason":"stop","delta":{}}]}\n\n',
        b'data: {"choices":[{"delta":{"phase":"answer","status":"finished"}}]}\n\n',
        b'data: {"response.completed":{"response_id":"response"}}\n\n',
    ],
)
def test_qwen_sse_terminal_events_finish_the_browser_shaped_stream(body: bytes) -> None:
    assert _qwen_sse_finished(body) is True


def test_qwen_thinking_summary_does_not_finish_the_stream() -> None:
    body = b'data: {"choices":[{"delta":{"phase":"thinking_summary","status":"finished"}}]}\n\n'

    assert _qwen_sse_finished(body) is False


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
        def is_closed(self) -> bool:
            return False

    transport = QwenNativeBrowserTransport()
    transport._page = FakePage()
    transport._frontend_version = "current"
    transport._prepare_authenticated_surface = AsyncMock()
    transport._fetch_in_main_world = AsyncMock(
        side_effect=[
            (
                {
                    "status": 200,
                    "contentType": "application/json",
                    "requestId": "request-id",
                    "retryAfter": "",
                    "bodyBase64": base64.b64encode(punished).decode(),
                },
                punished,
            ),
            (
                {
                    "status": 200,
                    "contentType": "text/event-stream",
                    "requestId": "request-id",
                    "retryAfter": "",
                    "bodyBase64": base64.b64encode(completed).decode(),
                },
                completed,
            ),
        ]
    )
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

    assert transport._fetch_in_main_world.await_count == 2
    transport._challenge_solver.solve.assert_awaited_once_with(transport._page, punish_url)
    transport._load_page_runtime.assert_awaited_once()
    assert base64.b64decode(response["body_base64"]) == completed
