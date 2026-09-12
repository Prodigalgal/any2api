from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator
from typing import Any
from urllib.parse import urlsplit

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
    token_from_credential,
)
from .longcat_browser import (
    _conversation_id,
    _longcat_upload_sources,
    build_longcat_request,
)
from .longcat_browser import (
    _headers as longcat_headers,
)
from .longcat_settings import settings
from .multimodal import decode_inline_data_url
from .transport_support import transport_frame, transport_proxy_lease

_MAX_UPLOAD_BYTES = 10 * 1024 * 1024


class LongcatApiActionHandler:
    """LongCat direct Web API channel, including its provider file-upload object."""

    async def execute(self, request: ProviderActionRequest) -> dict[str, Any]:
        if request.action is not ProviderAction.CHAT:
            raise ValueError("LongCat API channel only implements the chat action")
        current = credential(dict(request.payload))
        base_url = _base_url(request)
        async with transport_proxy_lease(dict(request.payload), check_url=base_url) as proxy_url:
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

    async def stream(self, request: ProviderActionRequest) -> AsyncIterator[bytes]:
        if request.action is not ProviderAction.CHAT:
            raise ValueError("LongCat API channel only streams the chat action")
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
    sources = _longcat_upload_sources(command.get("messages"))
    uploaded = await _upload_media(current, base_url, proxy_url, sources, request)
    prepared = build_longcat_request(command, uploaded_files=uploaded)
    session_headers = _request_headers(base_url, current, request)
    session = await asyncio.to_thread(
        api_request_sync,
        base_url,
        "POST",
        "/api/v1/session-create",
        headers=session_headers,
        body=json.dumps(
            {"model": "", "agentId": prepared["agent_id"]},
            ensure_ascii=True,
            separators=(",", ":"),
        ),
        proxy_url=proxy_url,
        timeout_seconds=120,
        impersonate=str(current.get("browser_profile") or "chrome146"),
    )
    merge_credential_patch(current, session)
    require_api_success(session, "LongCat session-create")
    conversation_id = _conversation_id(session)
    headers = _request_headers(base_url, current, request)
    body = json.dumps(
        {
            "content": prepared["content"],
            "conversationId": conversation_id,
            "agentId": prepared["agent_id"],
            "reasonEnabled": 1 if prepared["reason_enabled"] else 0,
            "searchEnabled": 1 if prepared["search_enabled"] else 0,
            "regenerate": 0,
            "parentMessageId": 0,
            "creationParam": prepared["creation_param"],
            "files": prepared["files"],
        },
        ensure_ascii=True,
        separators=(",", ":"),
    )
    return "/api/v1/chat-completion-V2", body, headers


async def _upload_media(
    current: dict[str, Any],
    base_url: str,
    proxy_url: str,
    sources: list[dict[str, Any]],
    request: ProviderActionRequest,
) -> list[dict[str, Any]]:
    uploaded: list[dict[str, Any]] = []
    for source in sources:
        mime_type, content = decode_inline_data_url(
            str(source.get("dataUrl") or ""),
            "LongCat media",
            max_bytes=_MAX_UPLOAD_BYTES,
        )
        headers = _request_headers(base_url, current, request)
        result = await asyncio.to_thread(
            api_multipart_request_sync,
            base_url,
            "POST",
            "/api/v1/appendix-upload",
            file_field="file",
            filename=str(source["fileName"]),
            content=content,
            mime_type=mime_type,
            headers=headers,
            proxy_url=proxy_url,
            timeout_seconds=180,
            impersonate=str(current.get("browser_profile") or "chrome146"),
        )
        merge_credential_patch(current, result)
        payload = _json(result, "LongCat media upload")
        data = payload.get("data") if isinstance(payload.get("data"), dict) else payload
        if (
            not isinstance(data, dict)
            or not str(data.get("url") or "").strip()
            or not str(data.get("key") or "").strip()
        ):
            raise RuntimeError("LongCat media upload was rejected")
        _require_https(str(data["url"]))
        uploaded.append(
            {
                "fileId": source["fileId"],
                "fileName": source["fileName"],
                "fileUrl": str(data["url"]),
                "fileKey": str(data["key"]),
                "fileExt": source["fileExt"],
                "uploadingStatus": "success",
                "progress": 100,
                "fileSize": source["fileSize"],
                **{key: source[key] for key in ("width", "height") if key in source},
            }
        )
    return uploaded


def _request_headers(
    base_url: str,
    current: dict[str, Any],
    request: ProviderActionRequest,
) -> dict[str, str]:
    options = request.payload.get("runtime_options")
    options = options if isinstance(options, dict) else {}
    headers = api_headers(
        base_url,
        current,
        extra=longcat_headers(options),
        accept="application/json, text/event-stream",
        content_type="application/json",
        bearer_names=("token", "access_token", "passport_token"),
        cookie_fields=("passport_token_key", "_lxsdk_cuid", "_lxsdk_s"),
    )
    token = token_from_credential(current, "token", "access_token", "passport_token")
    if token:
        headers["access-token"] = token
    return headers


def _base_url(request: ProviderActionRequest) -> str:
    return allowlisted_base_url(
        _runtime_option(request, "base_url"), settings().longcat_base_url, ("longcat.chat",)
    )


def _runtime_option(request: ProviderActionRequest, name: str) -> str | None:
    options = request.payload.get("runtime_options")
    if not isinstance(options, dict):
        return None
    value = options.get(name)
    return str(value).strip() if value is not None else None


def _json(result: dict[str, Any], operation: str) -> dict[str, Any]:
    require_api_success(result, operation)
    try:
        value = json.loads(str(result.get("body") or ""))
    except json.JSONDecodeError as error:
        raise RuntimeError(f"{operation} returned invalid JSON") from error
    if not isinstance(value, dict):
        raise TypeError(f"{operation} returned an invalid object")
    return value


def _require_https(value: str) -> None:
    parsed = urlsplit(value)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise RuntimeError("LongCat upload returned an invalid HTTPS URL")


def action_bindings(provider: Any):
    return api_action_bindings(provider, LongcatApiActionHandler())
