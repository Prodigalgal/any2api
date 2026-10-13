import pytest

from any2api_automation.providers import api_transport


def test_allowlisted_base_url_is_https_and_same_provider_origin() -> None:
    assert (
        api_transport.allowlisted_base_url(
            "https://chat.arena.ai/", "https://arena.ai", ("arena.ai",)
        )
        == "https://chat.arena.ai"
    )

    for value in (
        "http://arena.ai",
        "https://arena.ai.evil.example",
        "https://user:pass@arena.ai",
        "https://arena.ai:443",
        "https://arena.ai/path",
    ):
        with pytest.raises(ValueError, match="allowlisted"):
            api_transport.allowlisted_base_url(value, "https://arena.ai", ("arena.ai",))


def test_same_origin_path_rejects_absolute_and_traversal_targets() -> None:
    assert api_transport.same_origin_path("/api/chat?stream=1") == "/api/chat?stream=1"
    for value in (
        "https://evil.example/api/chat",
        "//evil.example/api/chat",
        "/api/../admin",
        "/api/./chat",
        "/api\\chat",
    ):
        with pytest.raises(ValueError, match="same-origin"):
            api_transport.same_origin_path(value)


def test_api_headers_keep_credentials_and_filter_provider_extensions() -> None:
    headers = api_transport.api_headers(
        "https://arena.ai",
        {
            "token": "token-value",
            "cookies": {"session": "cookie-value"},
        },
        extra={"X-Provider": "enabled", "Authorization": "overridden"},
    )

    assert headers["Authorization"] == "overridden"
    assert headers["Cookie"] == "session=cookie-value"
    assert headers["Origin"] == "https://arena.ai"

    assert api_transport.merge_allowed_headers(
        {"bx-ua": "risk", "Cookie": "must-not-copy"}, {"bx-ua"}
    ) == {"bx-ua": "risk"}

    assert "Authorization" not in api_transport.api_headers(
        "https://aistudio.xiaomimimo.com",
        {"token": "must-not-be-used"},
        bearer_names=(),
    )


def test_require_api_success_preserves_bounded_upstream_status() -> None:
    with pytest.raises(api_transport.ApiActionError) as caught:
        api_transport.require_api_success(
            {"status": 403, "body": "captcha validation failed"},
            "GLM chats/new",
        )

    assert caught.value.status == 403
    assert caught.value.body == "captcha validation failed"


def test_set_cookie_is_reduced_to_a_credential_cookie_patch() -> None:
    class Headers:
        def get_list(self, name):
            assert name == "set-cookie"
            return [
                "arena-auth-prod-v1=rotated-token; Path=/; HttpOnly",
                "arena-auth-prod-v1.1=rotated-token-1; Path=/",
            ]

    response = type(
        "Response",
        (),
        {"headers": Headers()},
    )()

    patch = api_transport.credential_patch_from_response(response)
    credential = {"cookies": {"existing": "keep"}}
    api_transport.merge_credential_patch(credential, {"credential_patch": patch})

    assert patch == {
        "cookies": {
            "arena-auth-prod-v1": "rotated-token",
            "arena-auth-prod-v1.1": "rotated-token-1",
        }
    }
    assert credential["cookies"] == {
        "existing": "keep",
        "arena-auth-prod-v1": "rotated-token",
        "arena-auth-prod-v1.1": "rotated-token-1",
    }


def test_cookie_patch_keeps_top_level_cookie_alias_in_sync() -> None:
    credential = {
        "passport_token_key": "original",
        "cookies": {"passport_token_key": "original"},
    }

    api_transport.merge_credential_patch(
        credential,
        {"credential_patch": {"cookies": {"passport_token_key": "rotated"}}},
    )

    assert credential["passport_token_key"] == "rotated"
    assert credential["cookies"]["passport_token_key"] == "rotated"


def test_api_request_uses_bounded_direct_http_without_browser(monkeypatch) -> None:
    calls = []

    class Response:
        status_code = 201

        def __init__(self):
            self.headers = {"content-type": "application/json"}

        def iter_content(self, chunk_size):
            assert chunk_size == 64 * 1024
            yield b'{"ok":true}'

    class Stream:
        def __enter__(self):
            return Response()

        def __exit__(self, exc_type, exc_value, traceback):
            return False

    class Client:
        def __init__(self, impersonate):
            calls.append(("client", impersonate))

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc_value, traceback):
            return False

        def stream(self, method, url, **kwargs):
            calls.append((method, url, kwargs))
            return Stream()

    monkeypatch.setattr(api_transport, "CurlSession", Client)

    result = api_transport.api_request_sync(
        "https://arena.ai",
        "GET",
        "/api/models",
        headers={"Accept": "application/json"},
        proxy_url="http://proxy.example:8080",
        impersonate="chrome146",
    )

    assert result == {
        "status": 201,
        "body": '{"ok":true}',
        "content_type": "application/json",
        "transport_mode": "api",
    }
    assert calls[0] == ("client", "chrome146")
    assert calls[1][0:2] == ("GET", "https://arena.ai/api/models")
    assert calls[1][2]["proxy"] == "http://proxy.example:8080"


def test_api_multipart_request_uses_curl_mime_not_requests_files(monkeypatch) -> None:
    calls = []

    class Mime:
        def __init__(self):
            self.parts = []
            self.closed = False

        def addpart(self, **kwargs):
            self.parts.append(kwargs)

        def close(self):
            self.closed = True

    class Response:
        status_code = 201

        def __init__(self):
            self.headers = {"content-type": "application/json"}

        def iter_content(self, chunk_size):
            assert chunk_size == 64 * 1024
            yield b'{"uploaded":true}'

    class Stream:
        def __enter__(self):
            return Response()

        def __exit__(self, exc_type, exc_value, traceback):
            return False

    class Client:
        def __init__(self, impersonate):
            calls.append(("client", impersonate))

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc_value, traceback):
            return False

        def stream(self, method, url, **kwargs):
            calls.append((method, url, kwargs))
            assert "files" not in kwargs
            assert kwargs["multipart"].parts == [
                {
                    "name": "file",
                    "filename": "sample.png",
                    "content_type": "image/png",
                    "data": b"png",
                }
            ]
            return Stream()

    mime_instances = []

    def create_mime():
        value = Mime()
        mime_instances.append(value)
        return value

    monkeypatch.setattr(api_transport, "CurlMime", create_mime)
    monkeypatch.setattr(api_transport, "CurlSession", Client)

    result = api_transport.api_multipart_request_sync(
        "https://longcat.chat",
        "POST",
        "/api/v1/appendix-upload",
        file_field="file",
        filename="sample.png",
        content=b"png",
        mime_type="image/png",
        headers={"Content-Type": "application/json"},
        form={"scope": "chat"},
        proxy_url="http://proxy.example:8080",
        impersonate="chrome146",
    )

    assert result == {
        "status": 201,
        "body": '{"uploaded":true}',
        "content_type": "application/json",
        "transport_mode": "api",
    }
    assert calls[0] == ("client", "chrome146")
    assert calls[1][2]["data"] == {"scope": "chat"}
    assert mime_instances[0].closed is True


@pytest.mark.asyncio
async def test_api_stream_emits_status_and_sse_data(monkeypatch) -> None:
    class Headers(dict):
        def get_list(self, name):
            assert name == "set-cookie"
            return ["session=rotated; Path=/; HttpOnly"]

    class Response:
        status_code = 200

        def __init__(self):
            self.headers = Headers({"content-type": "text/event-stream"})

        def iter_lines(self):
            yield b": heartbeat"
            yield b"data: first"
            yield b""
            yield b"raw-second"

    class Stream:
        def __enter__(self):
            return Response()

        def __exit__(self, exc_type, exc_value, traceback):
            return False

    class Client:
        def __init__(self, impersonate):
            self.impersonate = impersonate

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc_value, traceback):
            return False

        def stream(self, method, url, **kwargs):
            return Stream()

    monkeypatch.setattr(api_transport, "CurlSession", Client)
    events = [
        event
        async for event in api_transport.api_stream(
            "https://arena.ai",
            "POST",
            "/api/stream",
            body="{}",
            include_unprefixed_lines=True,
        )
    ]

    assert events[0]["type"] == "status"
    assert events[0]["status"] == 200
    assert events[1] == {
        "type": "credential_patch",
        "data": {"cookies": {"session": "rotated"}},
    }
    assert [event["data"] for event in events if event["type"] == "data"] == [
        "first",
        "raw-second",
    ]


@pytest.mark.asyncio
async def test_api_stream_treats_redirect_as_an_upstream_failure(monkeypatch) -> None:
    class Response:
        status_code = 302

        def __init__(self):
            self.headers = {"content-type": "text/html"}

        def iter_content(self, chunk_size):
            assert chunk_size == 64 * 1024
            yield b"redirect"

    class Stream:
        def __enter__(self):
            return Response()

        def __exit__(self, exc_type, exc_value, traceback):
            return False

    class Client:
        def __init__(self, impersonate):
            del impersonate

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc_value, traceback):
            return False

        def stream(self, method, url, **kwargs):
            del method, url, kwargs
            return Stream()

    monkeypatch.setattr(api_transport, "CurlSession", Client)
    events = [
        event
        async for event in api_transport.api_stream(
            "https://arena.ai",
            "POST",
            "/api/stream",
            body="{}",
        )
    ]

    assert events[0]["type"] == "status"
    assert events[0]["status"] == 302
    assert events[1]["type"] == "error"


def _install_stream_response(monkeypatch, response, exited=None) -> None:
    class Stream:
        def __enter__(self):
            return response

        def __exit__(self, exc_type, exc_value, traceback):
            if exited is not None:
                exited.set()
            return False

    class Client:
        def __init__(self, **kwargs):
            pass

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc_value, traceback):
            return False

        def stream(self, *args, **kwargs):
            return Stream()

    monkeypatch.setattr(api_transport, "CurlSession", Client)


@pytest.mark.asyncio
async def test_api_stream_backpressure_preserves_burst_and_end(monkeypatch) -> None:
    import asyncio

    event_count = 1500
    queue_filled = asyncio.Event()
    queue_type = asyncio.Queue

    class ObservedQueue(queue_type):
        def put_nowait(self, item):
            super().put_nowait(item)
            if self.full():
                queue_filled.set()

    monkeypatch.setattr(api_transport.asyncio, "Queue", ObservedQueue)

    class Response:
        status_code = 200

        def __init__(self):
            self.headers = {"content-type": "text/event-stream"}

        def iter_lines(self):
            for index in range(event_count):
                yield f"data: {index}".encode()

    _install_stream_response(monkeypatch, Response())
    loop = asyncio.get_running_loop()
    previous_handler = loop.get_exception_handler()
    callback_errors = []
    loop.set_exception_handler(lambda _, context: callback_errors.append(context.get("exception")))
    received = []

    async def consume():
        async for event in api_transport.api_stream("https://arena.ai", "POST", "/api/stream"):
            if event["type"] == "status":
                await asyncio.wait_for(queue_filled.wait(), timeout=2)
            elif event["type"] == "data":
                received.append(event["data"])

    try:
        try:
            await asyncio.wait_for(consume(), timeout=5)
        except TimeoutError:
            pytest.fail(
                f"SSE burst lost termination: received={len(received)}/{event_count}; "
                f"callback_errors={len(callback_errors)}"
            )
        assert received == [str(index) for index in range(event_count)]
        assert callback_errors == []
    finally:
        loop.set_exception_handler(previous_handler)


@pytest.mark.asyncio
async def test_slow_api_stream_close_keeps_health_responsive(monkeypatch) -> None:
    import asyncio
    import threading
    import time

    from httpx import ASGITransport, AsyncClient

    from any2api_automation.main import app

    closed = threading.Event()
    exited = threading.Event()

    class Response:
        status_code = 200

        def __init__(self):
            self.headers = {"content-type": "text/event-stream"}

        def iter_lines(self):
            yield b"data: first"
            closed.wait(timeout=2)

        def close(self):
            time.sleep(0.25)
            closed.set()

    _install_stream_response(monkeypatch, Response(), exited)
    stream = api_transport.api_stream("https://arena.ai", "POST", "/api/stream")
    assert (await anext(stream))["type"] == "status"
    assert (await anext(stream))["data"] == "first"
    started = time.monotonic()
    closing = asyncio.create_task(stream.aclose())
    try:
        await asyncio.sleep(0)
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
            response = await client.get("/health/live")
        assert response.status_code == 200
        assert time.monotonic() - started < 0.15
    finally:
        await closing
        closed.set()
    assert await asyncio.to_thread(exited.wait, 1)


@pytest.mark.asyncio
async def test_api_stream_cancellation_unblocks_saturated_producer(monkeypatch) -> None:
    import asyncio
    import threading

    queue_filled = asyncio.Event()
    exited = threading.Event()
    closed = threading.Event()
    queue_type = asyncio.Queue

    class ObservedQueue(queue_type):
        def put_nowait(self, item):
            super().put_nowait(item)
            if self.full():
                queue_filled.set()

    class Response:
        status_code = 200

        def __init__(self):
            self.headers = {"content-type": "text/event-stream"}

        def iter_lines(self):
            for index in range(1500):
                yield f"data: {index}".encode()

        def close(self):
            closed.set()

    monkeypatch.setattr(api_transport.asyncio, "Queue", ObservedQueue)
    _install_stream_response(monkeypatch, Response(), exited)
    stream = api_transport.api_stream("https://arena.ai", "POST", "/api/stream")
    assert (await anext(stream))["type"] == "status"
    await asyncio.wait_for(queue_filled.wait(), timeout=2)
    await asyncio.wait_for(stream.aclose(), timeout=2)
    assert closed.is_set()
    assert await asyncio.to_thread(exited.wait, 1)


@pytest.mark.asyncio
async def test_api_stream_close_failure_still_stops_producer(monkeypatch) -> None:
    import asyncio
    import threading

    released = threading.Event()
    exited = threading.Event()

    class Response:
        status_code = 200

        def __init__(self):
            self.headers = {"content-type": "text/event-stream"}

        def iter_lines(self):
            yield b"data: first"
            released.wait(timeout=2)

        def close(self):
            released.set()
            raise RuntimeError("synthetic close failure")

    _install_stream_response(monkeypatch, Response(), exited)
    stream = api_transport.api_stream("https://arena.ai", "POST", "/api/stream")
    assert (await anext(stream))["type"] == "status"
    assert (await anext(stream))["data"] == "first"
    with pytest.raises(RuntimeError, match="synthetic close failure"):
        await stream.aclose()
    assert await asyncio.to_thread(exited.wait, 1)
