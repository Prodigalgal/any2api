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
