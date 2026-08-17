from __future__ import annotations

import base64
import json
from contextlib import asynccontextmanager
from copy import deepcopy
from types import SimpleNamespace
from typing import ClassVar, Self
from unittest.mock import AsyncMock

import pytest
from pydantic import ValidationError

from any2api_automation.providers.qwen_fingerprint import (
    new_qwen_fingerprint,
    qwen_fingerprint_digest,
)
from any2api_automation.providers.qwen_inference_challenge import (
    _drag_slider_full_track,
)
from any2api_automation.providers.qwen_risk import (
    NativeBrowserRequest,
    QwenNativeBrowserTransport,
    _AccountBrowserSession,
    _qwen_network_failure_reason,
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


@pytest.mark.asyncio
async def test_qwen_native_request_persists_a_migrated_legacy_fingerprint() -> None:
    legacy = deepcopy(new_qwen_fingerprint("camoufox"))
    legacy["schema_version"] = 1
    legacy.pop("interaction_profile")
    legacy["camoufox_config"]["humanize"] = True
    legacy["camoufox_config"]["humanize:maxTime"] = 1.5
    request = NativeBrowserRequest(
        path="/api/v2/chats/new",
        bearer_token="token-value-that-is-long-enough",
        browser_fingerprint=legacy,
    )

    class Context:
        async def storage_state(self, *, indexed_db: bool) -> dict[str, object]:
            assert indexed_db is True
            return {"cookies": [], "origins": []}

    session = _AccountBrowserSession(
        "account",
        Context(),
        object(),
        "",
        browser_fingerprint=request.browser_fingerprint,
        fingerprint_digest=qwen_fingerprint_digest(request.browser_fingerprint),
    )

    patch = await QwenNativeBrowserTransport()._credential_patch(session, request)

    assert request._browser_fingerprint_requires_persistence is True
    assert patch["browser_fingerprint"]["schema_version"] == 2
    assert "humanize" not in patch["browser_fingerprint"]["camoufox_config"]
    assert "humanize:maxTime" not in patch["browser_fingerprint"]["camoufox_config"]


@pytest.mark.asyncio
async def test_qwen_transport_keeps_only_one_camoufox_runtime() -> None:
    transport = QwenNativeBrowserTransport()
    first_camoufox = _AccountBrowserSession(
        "first-camoufox", object(), object(), backend="camoufox"
    )
    patchright = _AccountBrowserSession("patchright", object(), object(), backend="patchright")
    transport._sessions[first_camoufox.key] = first_camoufox
    transport._sessions[patchright.key] = patchright
    transport._close_session = AsyncMock()

    await transport._evict_other_camoufox_sessions("next-camoufox")

    assert list(transport._sessions) == [patchright.key]
    transport._close_session.assert_awaited_once_with(first_camoufox)


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
async def test_qwen_full_track_drag_uses_bounded_mouse_commands_for_camoufox() -> None:
    class Mouse:
        def __init__(self) -> None:
            self.moves: list[tuple[float, float, int | None]] = []
            self.down_count = 0
            self.up_count = 0

        async def move(self, x: float, y: float, *, steps: int | None = None) -> None:
            self.moves.append((x, y, steps))

        async def down(self) -> None:
            self.down_count += 1

        async def up(self) -> None:
            self.up_count += 1

    class Page:
        def __init__(self) -> None:
            self.mouse = Mouse()

        async def wait_for_timeout(self, _milliseconds: int) -> None:
            return None

    class Slider:
        async def bounding_box(self) -> dict[str, float]:
            return {"x": 10.0, "y": 20.0, "width": 42.0, "height": 42.0}

    page = Page()

    await _drag_slider_full_track(page, Slider(), 258.0)

    assert len(page.mouse.moves) == 4
    assert page.mouse.down_count == 1
    assert page.mouse.up_count == 1
    assert page.mouse.moves[-1][2] is not None


@pytest.mark.asyncio
async def test_qwen_native_transport_passes_the_path_to_the_browser_script() -> None:
    class FakePage:
        pass

    class FakeContext:
        pass

    transport = QwenNativeBrowserTransport()
    session = _AccountBrowserSession("account", FakeContext(), FakePage(), "current")
    transport._ensure_ready = AsyncMock()
    transport._session_for = AsyncMock(return_value=session)
    transport._prepare_authenticated_surface = AsyncMock()
    transport._credential_patch = AsyncMock(return_value={})
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
    payload = transport._fetch_in_main_world.await_args.args[1]
    assert payload["path"] == "/api/v2/chats/new"
    assert str(payload["timezone"]).isascii()
    assert str(payload["timezone"]).endswith("GMT+0800")


@pytest.mark.asyncio
async def test_qwen_native_transport_holds_the_proxy_lease_through_the_request() -> None:
    lease_held = False

    @asynccontextmanager
    async def proxy_lease(_session_id: str):
        nonlocal lease_held
        lease_held = True
        try:
            yield ("http://proxy.internal:8080", "binding-1")
        finally:
            lease_held = False

    session = _AccountBrowserSession("account", object(), object(), "current")
    transport = QwenNativeBrowserTransport()
    transport._transport_proxy_lease = proxy_lease
    transport._session_for = AsyncMock(return_value=session)
    transport._credential_patch = AsyncMock(return_value={})

    async def evaluate(_session, _request):
        assert lease_held is True
        return (
            {
                "status": 200,
                "contentType": "application/json",
                "requestId": "request-id",
                "retryAfter": "",
                "bodyBase64": base64.b64encode(b"{}").decode(),
            },
            b"{}",
        )

    transport._evaluate = evaluate

    await transport.fetch(
        NativeBrowserRequest(
            path="/api/v2/chats/new",
            bearer_token="token-value-that-is-long-enough",
            transport_session_id="a" * 32,
        )
    )

    assert lease_held is False
    transport._session_for.assert_awaited_once_with(
        transport._session_for.await_args.args[0],
        ("http://proxy.internal:8080", "binding-1"),
    )


@pytest.mark.asyncio
async def test_qwen_native_transport_executes_the_real_request_in_the_page_main_world() -> None:
    body = b'{"data":{"id":"chat"}}'

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

    class FakePage:
        def __init__(self) -> None:
            self.script = ""
            self.payload: dict[str, object] = {}
            self.listeners: dict[str, object] = {}

        def on(self, event: str, callback: object) -> None:
            self.listeners[event] = callback

        def remove_listener(self, event: str, callback: object) -> None:
            assert self.listeners.get(event) is callback
            self.listeners.pop(event)

        def expect_request(self, predicate, **_kwargs: object) -> FakeRequestInfo:
            assert predicate(FakeRequest())
            return FakeRequestInfo()

        async def evaluate(self, script: str, payload: dict[str, object]) -> dict[str, object]:
            self.script = script
            self.payload = payload
            return {
                "status": 200,
                "contentType": "application/json",
                "requestId": "upstream-id",
                "retryAfter": "",
                "bodyBase64": base64.b64encode(body).decode(),
            }

    page = FakePage()
    session = _AccountBrowserSession("account", object(), page, "current")
    transport = QwenNativeBrowserTransport()
    payload = {
        "url": "https://chat.qwen.ai/api/v2/chats/new",
        "path": "/api/v2/chats/new",
        "method": "POST",
        "body": "{}",
        "referrer": "https://chat.qwen.ai/c/new-chat",
        "timeoutMs": 60_000,
        "maximumBytes": 1024,
        "version": "current",
        "requestId": "request-id",
        "timezone": "Wed Aug 05 2026 12:00:00 GMT+0800",
    }

    result, actual_body = await transport._fetch_in_main_world(session, payload)

    assert "await fetch(request.url" in page.script
    assert "response.body?.getReader()" in page.script
    assert page.payload["path"] == "/api/v2/chats/new"
    assert "token-value-that-is-long-enough" not in page.script
    assert "Authorization" not in page.script
    assert result["requestId"] == "upstream-id"
    assert actual_body == body
    assert page.listeners == {}


def test_qwen_network_failure_reason_is_bounded_and_single_line() -> None:
    reason = _qwen_network_failure_reason("net::ERR_FAILED\n" + "x" * 300)

    assert "\n" not in reason
    assert len(reason) == 160


@pytest.mark.asyncio
async def test_qwen_native_transport_reports_browser_network_failure() -> None:
    class FailedRequest:
        url = "https://chat.qwen.ai/api/v2/chat/completions?chat_id=chat"
        method = "POST"
        failure = "net::ERR_FAILED"
        headers: ClassVar[dict[str, str]] = {"x-request-id": "request-id"}

    class RequestInfo:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *_args: object) -> None:
            return None

    class Page:
        def __init__(self) -> None:
            self.listener = None

        def on(self, event: str, callback) -> None:
            assert event == "requestfailed"
            self.listener = callback

        def remove_listener(self, event: str, callback) -> None:
            assert event == "requestfailed"
            assert self.listener is callback
            self.listener = None

        def expect_request(self, predicate, **_kwargs: object) -> RequestInfo:
            assert predicate(FailedRequest())
            return RequestInfo()

        async def evaluate(self, _script: str, _payload: dict[str, object]) -> None:
            assert self.listener is not None
            self.listener(FailedRequest())
            raise RuntimeError("Page.evaluate: NetworkError when attempting to fetch resource")

    page = Page()
    session = _AccountBrowserSession("account", object(), page, "current")
    payload = {
        "url": FailedRequest.url,
        "path": "/api/v2/chat/completions?chat_id=chat",
        "method": "POST",
        "body": "{}",
        "referrer": "https://chat.qwen.ai/c/chat",
        "timeoutMs": 60_000,
        "maximumBytes": 1024,
        "version": "current",
        "requestId": "request-id",
        "timezone": "Wed Aug 05 2026 12:00:00 GMT+0800",
    }

    with pytest.raises(RuntimeError, match="net::ERR_FAILED"):
        await QwenNativeBrowserTransport()._fetch_in_main_world(session, payload)

    assert page.listener is None


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
    class FakeContext:
        async def add_cookies(self, _cookies: list[dict[str, str]]) -> None:
            return None

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
    transport._ensure_baxia_ready = AsyncMock()
    session = _AccountBrowserSession("account", FakeContext(), FakePage(), "current")
    request = NativeBrowserRequest(
        path="/api/v2/chats/new",
        body="{}",
        bearer_token="token-value-that-is-long-enough",
        referer_path="/c/new-chat",
    )

    await transport._prepare_authenticated_surface(session, request)

    assert session.page.token == request.bearer_token
    assert session.page.visited == "https://chat.qwen.ai/c/new-chat"


@pytest.mark.asyncio
async def test_qwen_native_transport_accepts_a_superseding_spa_navigation() -> None:
    class FakeContext:
        async def add_cookies(self, _cookies: list[dict[str, str]]) -> None:
            return None

    class FakePage:
        def __init__(self) -> None:
            self.url = "https://chat.qwen.ai/c/new-chat"
            self.token = ""
            self.superseded_target = ""

        async def evaluate(self, _script: str, token: str) -> None:
            self.token = token

        async def goto(self, url: str, **_kwargs: object) -> None:
            self.url = url
            raise RuntimeError("Page.goto: NS_BINDING_ABORTED")

        async def wait_for_url(self, url: str, **_kwargs: object) -> None:
            assert self.url == url
            self.superseded_target = url

        async def wait_for_function(self, _script: str, **_kwargs: object) -> None:
            return None

        async def wait_for_timeout(self, _timeout: int) -> None:
            return None

    transport = QwenNativeBrowserTransport()
    transport._ensure_baxia_ready = AsyncMock()
    session = _AccountBrowserSession("account", FakeContext(), FakePage(), "current")
    request = NativeBrowserRequest(
        path="/api/v2/chat/completions?chat_id=chat-1",
        body="{}",
        bearer_token="token-value-that-is-long-enough",
        referer_path="/c/chat-1",
    )

    await transport._prepare_authenticated_surface(session, request)

    assert session.page.token == request.bearer_token
    assert session.page.superseded_target == "https://chat.qwen.ai/c/chat-1"


@pytest.mark.asyncio
async def test_qwen_native_transport_rejects_an_unrelated_navigation_error() -> None:
    class FakeContext:
        async def add_cookies(self, _cookies: list[dict[str, str]]) -> None:
            return None

    class FakePage:
        url = "https://chat.qwen.ai/c/new-chat"

        async def evaluate(self, _script: str, _token: str) -> None:
            return None

        async def goto(self, _url: str, **_kwargs: object) -> None:
            raise RuntimeError("Page.goto: NS_ERROR_CONNECTION_REFUSED")

    transport = QwenNativeBrowserTransport()
    session = _AccountBrowserSession("account", FakeContext(), FakePage(), "current")
    request = NativeBrowserRequest(
        path="/api/v2/chat/completions?chat_id=chat-1",
        body="{}",
        bearer_token="token-value-that-is-long-enough",
        referer_path="/c/chat-1",
    )

    with pytest.raises(RuntimeError, match="NS_ERROR_CONNECTION_REFUSED"):
        await transport._prepare_authenticated_surface(session, request)


@pytest.mark.asyncio
async def test_qwen_native_transport_retries_baxia_after_a_navigation() -> None:
    class FakePage:
        def __init__(self) -> None:
            self.url = "https://chat.qwen.ai/c/chat-1"
            self.evaluate_calls = 0
            self.load_state_calls = 0

        async def evaluate(self, _script: str) -> None:
            self.evaluate_calls += 1
            if self.evaluate_calls == 1:
                raise RuntimeError(
                    "Page.evaluate: Execution context was destroyed, "
                    "most likely because of a navigation"
                )

        async def wait_for_load_state(self, _state: str, **_kwargs: object) -> None:
            self.load_state_calls += 1

        async def wait_for_function(self, _script: str, **_kwargs: object) -> None:
            return None

    transport = QwenNativeBrowserTransport()
    session = _AccountBrowserSession("account", object(), FakePage(), "current")

    await transport._ensure_baxia_ready(session)

    assert session.page.evaluate_calls == 2
    assert session.page.load_state_calls == 1


@pytest.mark.asyncio
async def test_qwen_native_transport_rejects_a_baxia_retry_on_a_foreign_origin() -> None:
    class FakePage:
        url = "https://example.com/c/chat-1"

        async def evaluate(self, _script: str) -> None:
            raise RuntimeError("Page.evaluate: Execution context was destroyed")

        async def wait_for_load_state(self, _state: str, **_kwargs: object) -> None:
            return None

    transport = QwenNativeBrowserTransport()
    session = _AccountBrowserSession("account", object(), FakePage(), "current")

    with pytest.raises(RuntimeError, match="left the configured origin"):
        await transport._ensure_baxia_ready(session)


@pytest.mark.asyncio
async def test_qwen_native_transport_rejects_an_unrelated_baxia_error() -> None:
    class FakePage:
        url = "https://chat.qwen.ai/c/chat-1"

        async def evaluate(self, _script: str) -> None:
            raise RuntimeError("Page.evaluate: JavaScript syntax error")

    transport = QwenNativeBrowserTransport()
    session = _AccountBrowserSession("account", object(), FakePage(), "current")

    with pytest.raises(RuntimeError, match="JavaScript syntax error"):
        await transport._ensure_baxia_ready(session)


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
        pass

    class FakeContext:
        pass

    transport = QwenNativeBrowserTransport()
    session = _AccountBrowserSession("account", FakeContext(), FakePage(), "current")
    transport._ensure_ready = AsyncMock()
    transport._session_for = AsyncMock(return_value=session)
    transport._prepare_authenticated_surface = AsyncMock()
    transport._credential_patch = AsyncMock(return_value={})
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
    transport._challenge_solver.solve.assert_awaited_once_with(session.page, punish_url)
    transport._load_page_runtime.assert_awaited_once_with(session)
    assert base64.b64decode(response["body_base64"]) == completed


@pytest.mark.asyncio
async def test_qwen_native_transport_returns_a_retryable_challenge_with_state_patch() -> None:
    punished = json.dumps(
        {
            "ret": ["FAIL_SYS_USER_VALIDATE"],
            "data": {
                "url": (
                    "https://chat.qwen.ai/api/v2/chat/completions/"
                    "_____tmd_____/punish?x5step=2&action=captcha"
                )
            },
        }
    ).encode()
    session = _AccountBrowserSession("account", object(), object(), "current")
    transport = QwenNativeBrowserTransport()
    transport._session_for = AsyncMock(return_value=session)
    transport._evaluate = AsyncMock(
        return_value=(
            {
                "status": 200,
                "contentType": "application/json",
                "requestId": "request-id",
                "retryAfter": "",
                "bodyBase64": base64.b64encode(punished).decode(),
            },
            punished,
        )
    )
    transport._recover_from_challenge = AsyncMock(side_effect=RuntimeError("slider exhausted"))
    transport._credential_patch = AsyncMock(return_value={"browser_state": {"schema_version": 1}})

    response = await transport.fetch(
        NativeBrowserRequest(
            path="/api/v2/chat/completions?chat_id=chat",
            body="{}",
            bearer_token="token-value-that-is-long-enough",
            referer_path="/c/chat",
        )
    )

    body = base64.b64decode(response["body_base64"])
    assert response["status"] == 403
    assert b"FAIL_SYS_USER_VALIDATE" in body
    assert response["credential_patch"]["browser_state"]["schema_version"] == 1


@pytest.mark.asyncio
async def test_qwen_challenge_diagnostics_hash_account_and_hide_proxy(
    caplog: pytest.LogCaptureFixture,
) -> None:
    transport = QwenNativeBrowserTransport()
    transport._challenge_solver.solve = AsyncMock(
        return_value=SimpleNamespace(attempts=1, diagnostic="cleared")
    )
    transport._load_page_runtime = AsyncMock()
    session = _AccountBrowserSession(
        "sensitive-account-id",
        object(),
        object(),
        proxy_url="http://proxy-user:proxy-password@example.com:8080",
        request_count=2,
    )

    with caplog.at_level("INFO"):
        await transport._recover_from_challenge(session, "https://chat.qwen.ai/punish")

    assert "sensitive-account-id" not in caplog.text
    assert "proxy-password" not in caplog.text
    assert "account_key_hash=18c50dd9aac6" in caplog.text
    assert "request_seq=2" in caplog.text
    assert "proxy_bound=True" in caplog.text
