from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import json
from collections.abc import AsyncIterator
from datetime import UTC, datetime
from typing import Any
from urllib.parse import urlencode
from uuid import uuid4

from ..config import settings as core_settings
from ..lifecycle.account import credential
from .actions import ProviderAction, ProviderActionRequest
from .api_transport import (
    allowlisted_base_url,
    api_action_bindings,
    api_headers,
    api_multipart_request_sync,
    api_request_sync,
    api_stream,
    merge_credential_patch,
    require_api_success,
)
from .glm_runtime import (
    _image_filename,
    _uploaded_file_from_response,
    build_glm_command,
)
from .glm_settings import settings
from .multimodal import decode_inline_data_url, iter_media_blocks, media_source
from .transport_support import transport_frame, transport_proxy_lease

_MAX_IMAGE_BYTES = 50 * 1024 * 1024


class GlmApiActionHandler:
    """GLM direct Web API channel with explicit provider-issued captcha support."""

    async def execute(self, request: ProviderActionRequest) -> dict[str, Any]:
        current = credential(dict(request.payload))
        base_url = _base_url(request)
        async with transport_proxy_lease(dict(request.payload), check_url=base_url) as proxy_url:
            operation = request.operation or request.action.default_legacy_operation
            if operation == "models":
                return await _request(
                    request,
                    current,
                    base_url,
                    proxy_url,
                    "GET",
                    "/api/models",
                    "",
                    referer_path="/",
                )
            if operation == "chat":
                path, body, headers = await _chat_input(request, current, base_url, proxy_url)
                result = await asyncio.to_thread(
                    api_request_sync,
                    base_url,
                    "POST",
                    path,
                    headers=headers,
                    body=body,
                    proxy_url=proxy_url,
                    timeout_seconds=300,
                    impersonate=str(current.get("browser_profile") or "chrome146"),
                )
                merge_credential_patch(current, result)
                return result
        raise ValueError("GLM API action is not allowlisted")

    async def stream(self, request: ProviderActionRequest) -> AsyncIterator[bytes]:
        if request.action is not ProviderAction.CHAT:
            raise ValueError("GLM API channel only streams the chat action")
        current = credential(dict(request.payload))
        base_url = _base_url(request)
        async with transport_proxy_lease(dict(request.payload), check_url=base_url) as proxy_url:
            path, body, headers = await _chat_input(request, current, base_url, proxy_url)
            async for event in api_stream(
                base_url,
                "POST",
                path,
                headers=headers,
                body=body,
                proxy_url=proxy_url,
                timeout_seconds=300,
                impersonate=str(current.get("browser_profile") or "chrome146"),
            ):
                event_type = str(event.get("type") or "error")
                yield transport_frame(
                    event_type,
                    **{key: value for key, value in event.items() if key != "type"},
                )


async def _chat_input(
    request: ProviderActionRequest,
    current: dict[str, Any],
    base_url: str,
    proxy_url: str,
) -> tuple[str, str, dict[str, str]]:
    command = dict(request.semantic_command)
    user_message_id = str(uuid4())
    uploaded = await _upload_images(request, current, base_url, proxy_url, user_message_id)
    timestamp = round(datetime.now(UTC).timestamp() * 1000)
    provider_command = build_glm_command(
        command,
        _required(current, "email"),
        timestamp,
        uploaded_files=uploaded,
        user_message_id=user_message_id,
    )
    headers = _request_headers(base_url, current, request)
    new_chat = await asyncio.to_thread(
        api_request_sync,
        base_url,
        "POST",
        "/api/v1/chats/new",
        headers=headers,
        body=json.dumps(
            {"chat": provider_command["chat"]}, ensure_ascii=True, separators=(",", ":")
        ),
        proxy_url=proxy_url,
        timeout_seconds=120,
        impersonate=str(current.get("browser_profile") or "chrome146"),
    )
    merge_credential_patch(current, new_chat)
    require_api_success(new_chat, "GLM chats/new")
    chat_body = _json(new_chat, "GLM chats/new")
    chat_id = str(chat_body.get("id") or chat_body.get("chat_id") or "").strip()
    if not chat_id:
        raise RuntimeError("GLM chats/new returned no chat id")
    completion = dict(provider_command["completion"])
    completion["chat_id"] = chat_id
    ticket = _captcha_ticket(request, current)
    if ticket:
        completion["captcha_verify_param"] = ticket
    prompt = provider_command["prompt"]
    timestamp = round(datetime.now(UTC).timestamp() * 1000)
    request_id = str(uuid4())
    signature = _signature(current, request, request_id, prompt, timestamp)
    path = _completion_path(
        base_url,
        current,
        request,
        chat_id,
        request_id,
        timestamp,
        signature,
    )
    headers = _request_headers(base_url, current, request)
    headers.update(
        {
            "X-FE-Version": "prod-fe-" + _frontend_version(request, current),
            "X-Signature": signature,
        }
    )
    return path, json.dumps(completion, ensure_ascii=True, separators=(",", ":")), headers


async def _request(
    request: ProviderActionRequest,
    current: dict[str, Any],
    base_url: str,
    proxy_url: str,
    method: str,
    path: str,
    body: str,
    *,
    referer_path: str,
) -> dict[str, Any]:
    result = await asyncio.to_thread(
        api_request_sync,
        base_url,
        method,
        path,
        headers=_request_headers(base_url, current, request, referer_path),
        body=body,
        proxy_url=proxy_url,
        timeout_seconds=120,
        impersonate=str(current.get("browser_profile") or "chrome146"),
    )
    merge_credential_patch(current, result)
    return result


async def _upload_images(
    request: ProviderActionRequest,
    current: dict[str, Any],
    base_url: str,
    proxy_url: str,
    user_message_id: str,
) -> list[dict[str, Any]]:
    uploaded: list[dict[str, Any]] = []
    for message_index, kind, block in iter_media_blocks(
        request.semantic_command.get("messages"), "GLM"
    ):
        if kind != "image":
            raise ValueError(f"GLM API upload does not support {kind} content")
        role = str(
            request.semantic_command["messages"][message_index].get("role") or "user"
        ).lower()
        if role != "user":
            raise ValueError("GLM image content must be attached to a user message")
        mime_type, content = decode_inline_data_url(
            media_source(block),
            "GLM image",
            max_bytes=_MAX_IMAGE_BYTES,
            expected_prefix="image/",
        )
        filename = _image_filename(block, mime_type)
        headers = _request_headers(base_url, current, request)
        result = await asyncio.to_thread(
            api_multipart_request_sync,
            base_url,
            "POST",
            "/api/v1/files/",
            file_field="file",
            filename=filename,
            content=content,
            mime_type=mime_type,
            headers=headers,
            proxy_url=proxy_url,
            timeout_seconds=180,
            impersonate=str(current.get("browser_profile") or "chrome146"),
        )
        merge_credential_patch(current, result)
        require_api_success(result, "GLM image upload")
        file = _uploaded_file_from_response(result, filename, len(content), base_url)
        file["ref_user_msg_id"] = user_message_id
        uploaded.append({"message_index": message_index, "file": file})
    return uploaded


def _completion_path(
    base_url: str,
    current: dict[str, Any],
    request: ProviderActionRequest,
    chat_id: str,
    request_id: str,
    timestamp: int,
    signature: str,
) -> str:
    profile = current.get("device_profile")
    profile = profile if isinstance(profile, dict) else {}
    width = _positive(profile.get("screen_width"), 1440)
    height = _positive(profile.get("screen_height"), 900)
    viewport_width = _positive(profile.get("viewport_width"), width)
    viewport_height = _positive(profile.get("viewport_height"), height)
    pathname = f"/c/{chat_id}"
    timezone = str(
        current.get("timezone")
        or profile.get("timezone")
        or _runtime_option(request, "timezone")
        or "Asia/Shanghai"
    )
    token = _required(current, "token", "access_token", "jwt")
    user_id = _required(current, "user_id", "userId", "id")
    user_agent = str(current.get("user_agent") or core_settings().provider_user_agent)
    values = {
        "timestamp": str(timestamp),
        "requestId": request_id,
        "user_id": user_id,
        "version": _frontend_version(request, current),
        "platform": "web",
        "token": token,
        "user_agent": user_agent,
        "language": "en-US",
        "languages": "en-US,en",
        "timezone": timezone,
        "cookie_enabled": "true",
        "screen_width": str(width),
        "screen_height": str(height),
        "screen_resolution": f"{width}x{height}",
        "viewport_height": str(viewport_height),
        "viewport_width": str(viewport_width),
        "viewport_size": f"{viewport_width}x{viewport_height}",
        "color_depth": "24",
        "pixel_ratio": "1",
        "current_url": base_url.rstrip("/") + pathname,
        "pathname": pathname,
        "search": "",
        "hash": "",
        "host": url_host(base_url),
        "hostname": url_host(base_url),
        "protocol": "https:",
        "referrer": "",
        "title": "Z.ai - Advanced AI Chatbot & Agent",
        "timezone_offset": "-480" if timezone == "Asia/Shanghai" else "0",
        "local_time": datetime.now(UTC).astimezone().isoformat(),
        "utc_time": datetime.now(UTC).strftime("%a, %d %b %Y %H:%M:%S GMT"),
        "is_mobile": "false",
        "is_touch": "false",
        "max_touch_points": "0",
        "browser_name": str(profile.get("browser_name") or "chrome"),
        "os_name": str(profile.get("os_name") or "Windows"),
    }
    url_params = urlencode(values)
    return "/api/v2/chat/completions?" + url_params + "&signature_timestamp=" + str(timestamp)


def _signature(
    current: dict[str, Any],
    request: ProviderActionRequest,
    request_id: str,
    prompt: str,
    timestamp: int,
) -> str:
    user_id = _required(current, "user_id", "userId", "id")
    sorted_payload = ",".join(
        f"{key},{value}"
        for key, value in sorted(
            {"timestamp": str(timestamp), "requestId": request_id, "user_id": user_id}.items()
        )
    )
    prompt_base64 = base64.b64encode(prompt.encode()).decode()
    bucket = timestamp // (5 * 60 * 1000)
    key = _runtime_option(request, "signature_key") or str(
        current.get("signature_key") or settings().glm_signature_key
    )
    if not key:
        raise ValueError("GLM API signature key is not configured")
    rotating_key = hmac.new(key.encode(), str(bucket).encode(), hashlib.sha256).hexdigest()
    message = f"{sorted_payload}|{prompt_base64}|{timestamp}"
    return hmac.new(rotating_key.encode(), message.encode(), hashlib.sha256).hexdigest()


def _request_headers(
    base_url: str,
    current: dict[str, Any],
    request: ProviderActionRequest,
    referer_path: str = "/",
) -> dict[str, str]:
    headers = api_headers(
        base_url,
        current,
        accept="application/json, text/event-stream",
        content_type="application/json",
    )
    headers["Referer"] = base_url.rstrip("/") + referer_path
    headers["X-Region"] = _runtime_option(request, "region") or settings().glm_region
    return headers


def _captcha_ticket(request: ProviderActionRequest, current: dict[str, Any]) -> str:
    for source in (request.payload.get("runtime_options"), current):
        if not isinstance(source, dict):
            continue
        for name in ("captcha_verify_param", "captcha_ticket"):
            value = str(source.get(name) or "").strip()
            if value:
                return value
    return ""


def _frontend_version(request: ProviderActionRequest, current: dict[str, Any]) -> str:
    return str(
        _runtime_option(request, "frontend_version")
        or current.get("frontend_version")
        or settings().glm_frontend_version
    )


def _base_url(request: ProviderActionRequest) -> str:
    return allowlisted_base_url(
        _runtime_option(request, "base_url"), settings().glm_base_url, ("z.ai",)
    )


def _runtime_option(request: ProviderActionRequest, name: str) -> str | None:
    options = request.payload.get("runtime_options")
    if not isinstance(options, dict):
        return None
    value = options.get(name)
    return str(value).strip() if value is not None else None


def _required(source: dict[str, Any], *names: str) -> str:
    for name in names:
        value = str(source.get(name) or "").strip()
        if value:
            return value
    raise ValueError("GLM credential requires " + " or ".join(names))


def _positive(value: Any, fallback: int) -> int:
    try:
        number = int(value)
    except (TypeError, ValueError):
        number = 0
    return number if number > 0 else fallback


def url_host(value: str) -> str:
    from urllib.parse import urlsplit

    return str(urlsplit(value).hostname or "")


def _json(result: dict[str, Any], operation: str) -> dict[str, Any]:
    require_api_success(result, operation)
    try:
        value = json.loads(str(result.get("body") or ""))
    except json.JSONDecodeError as error:
        raise RuntimeError(f"{operation} returned invalid JSON") from error
    if not isinstance(value, dict):
        raise TypeError(f"{operation} returned an invalid object")
    return value


def action_bindings(provider: Any):
    return api_action_bindings(provider, GlmApiActionHandler())
