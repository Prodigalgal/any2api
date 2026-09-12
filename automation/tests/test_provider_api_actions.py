import json
from urllib.parse import parse_qs, urlsplit

import pytest

from any2api_automation.config import settings as core_settings
from any2api_automation.providers.actions import ProviderAction, ProviderActionRequest
from any2api_automation.providers.arena_api_actions import _chat_input as arena_chat_input
from any2api_automation.providers.deepseek_api_actions import (
    _chat_input as deepseek_chat_input,
)
from any2api_automation.providers.glm_api_actions import _chat_input as glm_chat_input
from any2api_automation.providers.longcat_api_actions import _chat_input as longcat_chat_input
from any2api_automation.providers.mimo_api_actions import _chat_input as mimo_chat_input
from any2api_automation.providers.minmax_api_actions import (
    MinmaxApiActionHandler,
    _api_request_input,
)
from any2api_automation.providers.qwen_api_actions import _chat_input as qwen_chat_input

_TINY_PNG_DATA_URL = (
    "data:image/png;base64,"
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
)


def _command(
    model: str,
    *,
    options: dict[str, object] | None = None,
    messages: list[dict[str, object]] | None = None,
) -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "requestId": "api-action-test",
        "protocol": "CHAT_COMPLETIONS",
        "model": model,
        "stream": True,
        "messages": messages or [{"role": "user", "content": "hello"}],
        "generation": {},
        "reasoning": {},
        "tools": [],
        "providerOptions": options or {},
        "controls": {},
    }


def _request(
    provider_id: str,
    command: dict[str, object],
    credential: dict[str, object],
    *,
    options: dict[str, object] | None = None,
) -> ProviderActionRequest:
    return ProviderActionRequest(
        provider_id=provider_id,
        action=ProviderAction.CHAT,
        channel="api",
        operation="chat",
        payload={"credential": credential, "runtime_options": options or {}},
        semantic_command=command,
    )


@pytest.mark.asyncio
async def test_deepseek_api_prepares_session_pow_and_completion(monkeypatch) -> None:
    import any2api_automation.providers.deepseek_api_actions as module

    calls: list[tuple[str, str]] = []

    async def fake_request(request, current, base_url, proxy_url, method, path, body, **kwargs):
        del request, current, base_url, proxy_url, method, kwargs
        calls.append((path, body))
        if path.endswith("chat_session/create"):
            return {
                "status": 200,
                "body": json.dumps(
                    {"code": 0, "data": {"biz_data": {"chat_session": {"id": "session-1"}}}}
                ),
            }
        return {
            "status": 200,
            "body": json.dumps(
                {
                    "code": 0,
                    "data": {
                        "biz_data": {
                            "challenge": {
                                "algorithm": "DeepSeekHashV1",
                                "challenge": "00" * 32,
                                "salt": "salt",
                                "expire_at": 4_000_000_000_000,
                                "difficulty": 4,
                                "signature": "sig",
                                "target_path": "/api/v0/chat/completion",
                            }
                        }
                    },
                }
            ),
        }

    monkeypatch.setattr(module, "_request", fake_request)
    monkeypatch.setattr(module, "solve_pow", lambda challenge: 3)

    path, body, headers = await deepseek_chat_input(
        _request(
            "deepseek",
            _command("expert"),
            {"token": "token", "device_id": "device"},
        ),
        {"token": "token", "device_id": "device"},
        "https://chat.deepseek.com",
        "",
    )

    assert [path for path, _ in calls] == [
        "/api/v0/chat_session/create",
        "/api/v0/chat/create_pow_challenge",
    ]
    assert path == "/api/v0/chat/completion"
    assert json.loads(body)["chat_session_id"] == "session-1"
    assert headers["X-DS-PoW-Response"]


def test_deepseek_json_helper_rejects_redirect_responses() -> None:
    from any2api_automation.providers.deepseek_browser import _json_body

    with pytest.raises(RuntimeError, match="HTTP 302"):
        _json_body({"status": 302, "body": '{"code":0}'})


def test_deepseek_pow_matches_the_official_browser_fixture() -> None:
    from any2api_automation.providers.deepseek_browser import _hash_matches

    prefix = b"84226691b35fac66c866_1785338327707_"
    target = bytes.fromhex("34faae2603ea238bb11d85042a19e8d4e34582f0550733fb7e5062e85a749260")

    assert _hash_matches(prefix, 122_014, target)
    assert not _hash_matches(prefix, 122_013, target)


@pytest.mark.asyncio
async def test_action_stream_guard_preserves_setup_status() -> None:
    from any2api_automation.provider_api import _guard_action_stream
    from any2api_automation.providers.api_transport import ApiActionError

    async def failing_stream():
        raise ApiActionError(
            "DeepSeek create session returned HTTP 403",
            status=403,
            body="captcha validation failed",
        )
        yield b""

    frames = [json.loads(frame) async for frame in _guard_action_stream(failing_stream())]

    assert frames[0] == {"type": "status", "status": 403}
    assert frames[1]["type"] == "error"
    assert "captcha validation failed" in frames[1]["data"]


@pytest.mark.asyncio
async def test_glm_api_prepares_signed_completion_and_chat_session(monkeypatch) -> None:
    import any2api_automation.providers.glm_api_actions as module

    calls: list[str] = []

    def fake_request(base_url, method, path, **kwargs):
        del base_url, method, kwargs
        calls.append(path)
        return {"status": 200, "body": '{"id":"chat-1"}'}

    monkeypatch.setattr(module, "api_request_sync", fake_request)
    request = _request(
        "glm",
        _command("glm-5.2"),
        {"email": "user@example.test", "token": "token", "user_id": "user-1"},
    )

    path, body, headers = await glm_chat_input(
        request,
        dict(request.payload["credential"]),
        "https://chat.z.ai",
        "",
    )

    assert calls == ["/api/v1/chats/new"]
    assert urlsplit(path).path == "/api/v2/chat/completions"
    assert "signature_timestamp" in parse_qs(urlsplit(path).query)
    assert json.loads(body)["chat_id"] == "chat-1"
    assert headers["X-FE-Version"].startswith("prod-fe-")
    assert headers["X-Signature"]
    assert headers["User-Agent"] == core_settings().provider_user_agent
    assert parse_qs(urlsplit(path).query)["user_agent"] == [core_settings().provider_user_agent]


@pytest.mark.asyncio
async def test_glm_api_preserves_chat_creation_status_in_setup_failure(monkeypatch) -> None:
    import any2api_automation.providers.glm_api_actions as module

    def fake_request(base_url, method, path, **kwargs):
        del base_url, method, path, kwargs
        return {"status": 403, "body": "forbidden"}

    monkeypatch.setattr(module, "api_request_sync", fake_request)
    request = _request(
        "glm",
        _command("glm-5.2"),
        {"email": "user@example.test", "token": "token", "user_id": "user-1"},
    )

    with pytest.raises(RuntimeError, match="HTTP 403"):
        await glm_chat_input(
            request,
            dict(request.payload["credential"]),
            "https://chat.z.ai",
            "",
        )


@pytest.mark.asyncio
async def test_longcat_api_prepares_session_and_file_shape_without_browser(monkeypatch) -> None:
    import any2api_automation.providers.longcat_api_actions as module

    def fake_request(base_url, method, path, **kwargs):
        del base_url, method, path, kwargs
        return {
            "status": 200,
            "body": '{"code":0,"data":{"conversationId":"conversation-1"}}',
        }

    monkeypatch.setattr(module, "api_request_sync", fake_request)
    request = _request(
        "longcat",
        _command("longcat-pro"),
        {"passport_token_key": "session-cookie"},
    )

    path, body, headers = await longcat_chat_input(
        request,
        dict(request.payload["credential"]),
        "https://longcat.chat",
        "",
    )

    assert path == "/api/v1/chat-completion-V2"
    assert json.loads(body)["conversationId"] == "conversation-1"
    assert json.loads(body)["files"] == []
    assert headers["Cookie"] == "passport_token_key=session-cookie"


@pytest.mark.asyncio
async def test_longcat_api_rebuilds_completion_headers_after_session_cookie_rotation(
    monkeypatch,
) -> None:
    import any2api_automation.providers.longcat_api_actions as module

    def fake_request(base_url, method, path, **kwargs):
        del base_url, method, kwargs
        assert path == "/api/v1/session-create"
        return {
            "status": 200,
            "body": '{"code":0,"data":{"conversationId":"conversation-1"}}',
            "credential_patch": {"cookies": {"passport_token_key": "rotated"}},
        }

    monkeypatch.setattr(module, "api_request_sync", fake_request)
    request = _request(
        "longcat",
        _command("longcat-pro"),
        {"cookies": {"passport_token_key": "original"}},
    )

    _path, _body, headers = await longcat_chat_input(
        request,
        dict(request.payload["credential"]),
        "https://longcat.chat",
        "",
    )

    assert headers["Cookie"] == "passport_token_key=rotated"


def test_longcat_api_json_helper_rejects_redirect_responses() -> None:
    from any2api_automation.providers.longcat_api_actions import _json

    with pytest.raises(RuntimeError, match="HTTP 302"):
        _json(
            {
                "status": 302,
                "body": '{"code":0,"data":{"url":"https://upload.example/file"}}',
            },
            "LongCat media upload",
        )


@pytest.mark.asyncio
async def test_mimo_api_prepares_phase_bound_chat_request() -> None:
    request = _request("mimo", _command("mimo-v2.5"), {"xiaomichatbot_ph": "phase-1"})

    path, body, headers = await mimo_chat_input(
        request,
        dict(request.payload["credential"]),
        "https://aistudio.xiaomimimo.com",
        "",
    )

    assert path.startswith("/open-apis/bot/chat?xiaomichatbot_ph=phase-1")
    assert json.loads(body)["modelConfig"]["model"] == "mimo-v2.5"
    assert "xiaomichatbot_ph=phase-1" in headers["Cookie"]


@pytest.mark.asyncio
async def test_qwen_api_prepares_chat_id_and_native_message_graph(monkeypatch) -> None:
    import any2api_automation.providers.qwen_api_actions as module

    def fake_request(base_url, method, path, **kwargs):
        del base_url, method, path, kwargs
        return {"status": 200, "body": '{"data":{"id":"chat-1"}}'}

    monkeypatch.setattr(module, "api_request_sync", fake_request)
    request = _request("qwen", _command("qwen3.7-plus"), {"token": "token"})

    path, body, headers = await qwen_chat_input(
        request,
        dict(request.payload["credential"]),
        "https://chat.qwen.ai",
        "",
    )

    assert parse_qs(urlsplit(path).query)["chat_id"] == ["chat-1"]
    assert json.loads(body)["chatId"] == "chat-1"
    assert json.loads(body)["messages"][0]["role"] == "user"
    assert headers["Authorization"] == "Bearer token"


@pytest.mark.asyncio
async def test_arena_api_prepares_direct_search_request_from_current_catalog(monkeypatch) -> None:
    import any2api_automation.providers.arena_api_actions as module

    arena_model_id = "123e4567-e89b-12d3-a456-426614174000"

    async def fake_models(request, current, base_url, proxy_url):
        del request, current, base_url, proxy_url
        return {
            "status": 200,
            "body": json.dumps(
                {
                    "models": [
                        {
                            "id": "Max",
                            "display_name": "Max",
                            "metadata": {
                                "arena_model_id": arena_model_id,
                                "arena_capabilities": {"outputCapabilities": {"search": True}},
                            },
                        }
                    ]
                }
            ),
        }

    monkeypatch.setattr(module, "_models", fake_models)
    request = _request(
        "arena",
        _command("Max", options={"mode": "direct", "web_search": True}),
        {
            "token": "must-not-be-forwarded",
            "cookies": {"arena-auth-prod-v1": "session"},
        },
        options={"recaptcha_v3_token": "provider-issued-token"},
    )

    path, body, headers = await arena_chat_input(
        request,
        dict(request.payload["credential"]),
        "https://arena.ai",
        "",
    )

    value = json.loads(body)
    assert path == "/nextjs-api/stream/create-evaluation"
    assert value["mode"] == "direct-battle"
    assert value["modelAId"] == arena_model_id
    assert value["modality"] == "search"
    assert value["recaptchaV3Token"] == "provider-issued-token"
    assert headers["Cookie"] == "arena-auth-prod-v1=session"
    assert "Authorization" not in headers


@pytest.mark.asyncio
async def test_glm_api_uploads_an_inline_image_before_completion(monkeypatch) -> None:
    import any2api_automation.providers.glm_api_actions as module

    def fake_request(base_url, method, path, **kwargs):
        del base_url, method, kwargs
        return {"status": 200, "body": '{"id":"chat-1"}'}

    def fake_multipart(base_url, method, path, **kwargs):
        del base_url, method, path, kwargs
        return {"status": 200, "body": '{"id":"file-1"}'}

    monkeypatch.setattr(module, "api_request_sync", fake_request)
    monkeypatch.setattr(module, "api_multipart_request_sync", fake_multipart)
    command = _command(
        "glm-4.6v",
        messages=[
            {
                "role": "user",
                "content": [
                    {"type": "input_text", "text": "describe"},
                    {"type": "input_image", "image_url": _TINY_PNG_DATA_URL},
                ],
            }
        ],
    )
    request = _request(
        "glm",
        command,
        {"email": "user@example.test", "token": "token", "user_id": "user-1"},
    )

    _path, body, _headers = await glm_chat_input(
        request,
        dict(request.payload["credential"]),
        "https://chat.z.ai",
        "",
    )

    value = json.loads(body)
    assert value["chat_id"] == "chat-1"
    assert value["messages"][-1]["content"][1]["image_url"]["url"] == "file-1"


@pytest.mark.asyncio
async def test_longcat_api_uploads_an_inline_pdf_before_completion(monkeypatch) -> None:
    import any2api_automation.providers.longcat_api_actions as module

    def fake_request(base_url, method, path, **kwargs):
        del base_url, method, kwargs
        if path == "/api/v1/session-create":
            return {
                "status": 200,
                "body": '{"code":0,"data":{"conversationId":"conversation-1"}}',
            }
        raise AssertionError(f"unexpected API path: {path}")

    def fake_multipart(base_url, method, path, **kwargs):
        del base_url, method, path, kwargs
        return {
            "status": 200,
            "body": '{"data":{"url":"https://upload.longcat.chat/input.pdf","key":"pdf-key"}}',
        }

    monkeypatch.setattr(module, "api_request_sync", fake_request)
    monkeypatch.setattr(module, "api_multipart_request_sync", fake_multipart)
    command = _command(
        "longcat-pro",
        messages=[
            {
                "role": "user",
                "content": [
                    {"type": "input_text", "text": "read"},
                    {
                        "type": "input_file",
                        "input_file": {
                            "file_data": "data:application/pdf;base64,JVBERi0xLjQK",
                            "filename": "input.pdf",
                        },
                    },
                ],
            }
        ],
    )
    request = _request("longcat", command, {"passport_token_key": "session-cookie"})

    _path, body, _headers = await longcat_chat_input(
        request,
        dict(request.payload["credential"]),
        "https://longcat.chat",
        "",
    )

    files = json.loads(body)["files"]
    assert len(files) == 1
    assert files[0]["fileUrl"] == "https://upload.longcat.chat/input.pdf"
    assert files[0]["fileKey"] == "pdf-key"


@pytest.mark.asyncio
async def test_longcat_api_uploads_an_inline_image_before_completion(monkeypatch) -> None:
    import any2api_automation.providers.longcat_api_actions as module

    def fake_request(base_url, method, path, **kwargs):
        del base_url, method, kwargs
        if path == "/api/v1/session-create":
            return {
                "status": 200,
                "body": '{"code":0,"data":{"conversationId":"conversation-1"}}',
            }
        raise AssertionError(f"unexpected API path: {path}")

    def fake_multipart(base_url, method, path, **kwargs):
        del base_url, method, path, kwargs
        return {
            "status": 200,
            "body": '{"data":{"url":"https://upload.longcat.chat/input.png","key":"image-key"}}',
        }

    monkeypatch.setattr(module, "api_request_sync", fake_request)
    monkeypatch.setattr(module, "api_multipart_request_sync", fake_multipart)
    command = _command(
        "longcat-pro",
        messages=[
            {
                "role": "user",
                "content": [
                    {"type": "input_text", "text": "describe"},
                    {"type": "input_image", "image_url": _TINY_PNG_DATA_URL},
                ],
            }
        ],
    )
    request = _request("longcat", command, {"passport_token_key": "session-cookie"})

    _path, body, _headers = await longcat_chat_input(
        request,
        dict(request.payload["credential"]),
        "https://longcat.chat",
        "",
    )

    files = json.loads(body)["files"]
    assert len(files) == 1
    assert files[0]["fileUrl"] == "https://upload.longcat.chat/input.png"
    assert files[0]["fileKey"] == "image-key"
    assert files[0]["width"] == 1
    assert files[0]["height"] == 1


@pytest.mark.asyncio
async def test_mimo_api_uploads_and_parses_an_inline_image(monkeypatch) -> None:
    import any2api_automation.providers.mimo_api_actions as module

    upload_headers = []

    def fake_request(base_url, method, path, **kwargs):
        del base_url, method, kwargs
        if "genUploadInfo" in path:
            return {
                "status": 200,
                "body": json.dumps(
                    {
                        "data": {
                            "uploadUrl": "https://upload.example/mimo",
                            "resourceUrl": "https://resource.example/mimo",
                            "objectName": "object-1",
                        }
                    }
                ),
            }
        if "resource/parse" in path:
            return {"status": 200, "body": '{"code":0,"data":{"id":"media-1"}}'}
        raise AssertionError(f"unexpected API path: {path}")

    monkeypatch.setattr(module, "api_request_sync", fake_request)
    monkeypatch.setattr(
        module,
        "api_provider_put_sync",
        lambda url, content, **kwargs: (
            upload_headers.append(dict(kwargs["headers"])) or {"status": 200, "body": ""}
        ),
    )
    command = _command(
        "mimo-v2.5",
        messages=[
            {
                "role": "user",
                "content": [
                    {"type": "input_text", "text": "describe"},
                    {"type": "input_image", "image_url": "data:image/png;base64,YQ=="},
                ],
            }
        ],
    )
    request = _request("mimo", command, {"xiaomichatbot_ph": "phase-1"})

    _path, body, _headers = await mimo_chat_input(
        request,
        dict(request.payload["credential"]),
        "https://aistudio.xiaomimimo.com",
        "",
    )

    assert json.loads(body)["multiMedias"][0]["url"] == "media-1"
    assert upload_headers == [{"Content-Type": "application/octet-stream"}]


@pytest.mark.asyncio
async def test_mimo_api_applies_rotated_cookie_aliases_to_follow_up_requests(monkeypatch) -> None:
    import any2api_automation.providers.mimo_api_actions as module

    calls: list[tuple[str, dict[str, str]]] = []

    def fake_request(base_url, method, path, **kwargs):
        del base_url, method
        calls.append((path, dict(kwargs["headers"])))
        if "genUploadInfo" in path:
            return {
                "status": 200,
                "body": json.dumps(
                    {
                        "data": {
                            "uploadUrl": "https://upload.example/mimo",
                            "resourceUrl": "https://resource.example/mimo",
                            "objectName": "object-1",
                        }
                    }
                ),
                "credential_patch": {
                    "cookies": {
                        "serviceToken": "service-2",
                        "userId": "user-2",
                        "xiaomichatbot_ph": "phase-2",
                    }
                },
            }
        if "resource/parse" in path:
            return {"status": 200, "body": '{"code":0,"data":{"id":"media-1"}}'}
        raise AssertionError(f"unexpected API path: {path}")

    monkeypatch.setattr(module, "api_request_sync", fake_request)
    monkeypatch.setattr(
        module,
        "api_provider_put_sync",
        lambda url, content, **kwargs: {"status": 200, "body": ""},
    )
    command = _command(
        "mimo-v2.5",
        messages=[
            {
                "role": "user",
                "content": [{"type": "input_image", "image_url": "data:image/png;base64,YQ=="}],
            }
        ],
    )
    request = _request(
        "mimo",
        command,
        {
            "service_token": "service-1",
            "user_id": "user-1",
            "xiaomichatbot_ph": "phase-1",
        },
    )

    path, _body, headers = await module._chat_input(
        request,
        dict(request.payload["credential"]),
        "https://aistudio.xiaomimimo.com",
        "",
    )

    assert calls[1][0].startswith("/open-apis/resource/parse?xiaomichatbot_ph=phase-2")
    assert "serviceToken=service-2" in calls[1][1]["Cookie"]
    assert "userId=user-2" in calls[1][1]["Cookie"]
    assert "xiaomichatbot_ph=phase-2" in calls[1][1]["Cookie"]
    assert path == "/open-apis/bot/chat?xiaomichatbot_ph=phase-2"
    assert "serviceToken=service-2" in headers["Cookie"]


def test_mimo_api_normalizes_rotated_cookie_aliases_for_persistence() -> None:
    import any2api_automation.providers.mimo_api_actions as module

    current = {
        "service_token": "service-1",
        "user_id": "user-1",
        "xiaomichatbot_ph": "phase-1",
    }
    result = {
        "credential_patch": {
            "cookies": {
                "serviceToken": "service-2",
                "userId": "user-2",
                "xiaomichatbot_ph": "phase-2",
            }
        }
    }

    module._merge_credential_patch(current, result)

    assert current["service_token"] == "service-2"
    assert current["user_id"] == "user-2"
    assert current["xiaomichatbot_ph"] == "phase-2"
    assert result["credential_patch"]["service_token"] == "service-2"
    assert result["credential_patch"]["user_id"] == "user-2"


@pytest.mark.asyncio
async def test_qwen_api_uploads_an_inline_image_with_provider_issued_sts(monkeypatch) -> None:
    import any2api_automation.providers.qwen_api_actions as module

    def fake_request(base_url, method, path, **kwargs):
        del base_url, method, kwargs
        if path == "/api/v2/chats/new":
            return {"status": 200, "body": '{"data":{"id":"chat-1"}}'}
        if path == "/api/v2/files/getstsToken":
            return {
                "status": 200,
                "body": json.dumps(
                    {
                        "data": {
                            "access_key_id": "access-key",
                            "access_key_secret": "secret-key",
                            "security_token": "security-token",
                            "bucketname": "bucket",
                            "region": "oss-cn-hangzhou",
                            "endpoint": "oss-cn-hangzhou.aliyuncs.com",
                            "file_id": "file-1",
                            "file_path": "path/image.png",
                            "file_url": "https://resource.example/image.png",
                        }
                    }
                ),
            }
        raise AssertionError(f"unexpected API path: {path}")

    monkeypatch.setattr(module, "api_request_sync", fake_request)
    monkeypatch.setattr(
        module,
        "api_provider_put_sync",
        lambda url, content, **kwargs: {"status": 200, "body": ""},
    )
    command = _command(
        "qwen3.7-plus",
        messages=[
            {
                "role": "user",
                "content": [
                    {"type": "input_text", "text": "describe"},
                    {"type": "input_image", "image_url": "data:image/png;base64,YQ=="},
                ],
            }
        ],
    )
    request = _request("qwen", command, {"token": "token"})

    _path, body, _headers = await qwen_chat_input(
        request,
        dict(request.payload["credential"]),
        "https://chat.qwen.ai",
        "",
    )

    files = json.loads(body)["messages"][0]["files"]
    assert len(files) == 1
    assert files[0]["id"] == "file-1"


def test_qwen_api_json_helper_rejects_redirect_responses() -> None:
    from any2api_automation.providers.qwen_api_actions import _json

    with pytest.raises(RuntimeError, match="HTTP 302"):
        _json({"status": 302, "body": '{"data":{}}'}, "Qwen chats/new")


@pytest.mark.asyncio
async def test_arena_api_fails_closed_for_page_owned_media_upload() -> None:
    import any2api_automation.providers.arena_api_actions as module

    command = _command(
        "Max",
        messages=[
            {
                "role": "user",
                "content": [{"type": "input_image", "image_url": _TINY_PNG_DATA_URL}],
            }
        ],
    )
    request = _request("arena", command, {"cookies": {"arena-auth-prod-v1": "session"}})

    with pytest.raises(ValueError, match="page-owned"):
        await module._chat_input(
            request,
            dict(request.payload["credential"]),
            "https://arena.ai",
            "",
        )


def test_minmax_api_files_callback_accepts_legacy_top_level_body() -> None:
    method, path, body = _api_request_input(
        {
            "operation": "files_callback",
            "body": '{"dir":"mavis/","fileName":"sample.png"}',
        }
    )

    assert method == "POST"
    assert path == "/v1/api/files/policy_callback"
    assert body == '{"dir":"mavis/","fileName":"sample.png"}'


@pytest.mark.asyncio
async def test_minmax_api_builds_signed_session_message_without_browser(monkeypatch) -> None:
    import any2api_automation.providers.minmax_api_actions as module

    calls: list[tuple[str, str, str]] = []

    def fake_request(current, method, path, body, proxy_url):
        del current, proxy_url
        calls.append((method, path, body))
        if path == "/archon/api/v1/agent?limit=20":
            return {"status": 200, "body": '{"agents":[{"agent_role":"mavis","name":"agent-one"}]}'}
        if path == "/archon/api/v1/agent/agent-one/session":
            return {"status": 200, "body": '{"session_id":"session-one"}'}
        raise AssertionError(f"unexpected API path: {path}")

    monkeypatch.setattr(module, "_api_request_sync", fake_request)
    request = _request(
        "minmax",
        _command("MiniMax-M3"),
        {"token": "token", "user_id": "user", "device_id": "device"},
    )

    method, path, body = await MinmaxApiActionHandler()._semantic_chat_input(
        dict(request.payload["credential"]),
        dict(request.semantic_command),
        "",
    )

    assert method == "POST"
    assert calls[0][0:2] == ("GET", "/archon/api/v1/agent?limit=20")
    assert calls[1][0:2] == ("POST", "/archon/api/v1/agent/agent-one/session")
    assert path == "/archon/api/v1/session/session-one/message"
    assert json.loads(body)["model"]["model_id"] == "MiniMax-M3"
