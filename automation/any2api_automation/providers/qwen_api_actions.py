from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
from collections.abc import AsyncIterator
from datetime import UTC, datetime
from typing import Any
from urllib.parse import quote, urlsplit, urlunsplit
from uuid import uuid4

from ..lifecycle.account import credential
from .actions import ProviderAction, ProviderActionRequest
from .api_transport import (
    allowlisted_base_url,
    api_action_bindings,
    api_headers,
    api_provider_put_sync,
    api_request_sync,
    api_stream,
    merge_allowed_headers,
    merge_credential_patch,
    require_api_success,
)
from .qwen import (
    _qwen_chat_id,
    _qwen_media_sources,
    _qwen_token,
    build_qwen_request,
)
from .qwen_settings import settings
from .transport_support import transport_frame, transport_proxy_lease

_QWEN_RISK_HEADERS = (
    "bx-ua",
    "bx-umidtoken",
    "bx-v",
    "version",
    "x-client-version",
    "x-device-id",
)
_MAX_UPLOAD_BYTES = 20 * 1024 * 1024


class QwenApiActionHandler:
    """Qwen direct Web API channel using stored credentials only.

    Qwen may require a provider-issued Baxia challenge header. The API channel never
    starts the Runtime risk browser; when those headers are absent the upstream can
    reject the request and AUTO may choose the Runtime channel.
    """

    async def execute(self, request: ProviderActionRequest) -> dict[str, Any]:
        current = credential(dict(request.payload))
        _qwen_token(current)
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
                    "/api/v2/models/",
                    "",
                    referer_path="/",
                )
            if operation == "chat":
                path, body, headers = await _chat_input(request, current, base_url, proxy_url)
                return await asyncio.to_thread(
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
        raise ValueError("Qwen API action is not allowlisted")

    async def stream(self, request: ProviderActionRequest) -> AsyncIterator[bytes]:
        if request.action is not ProviderAction.CHAT:
            raise ValueError("Qwen API channel only streams the chat action")
        current = credential(dict(request.payload))
        _qwen_token(current)
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
    session_body = json.dumps(
        {
            "chatId": "",
            "project_id": "",
            "timestamp": int(datetime.now(UTC).timestamp() * 1000),
            "chat_type": "t2t",
            "chat_mode": "normal",
            "models": [str(command.get("model") or "")],
        },
        ensure_ascii=False,
        separators=(",", ":"),
    )
    session = await _request(
        request,
        current,
        base_url,
        proxy_url,
        "POST",
        "/api/v2/chats/new",
        session_body,
        referer_path="/c/new-chat",
    )
    session_body_value = _json(session, "Qwen chats/new")
    chat_id = _qwen_chat_id(session_body_value)
    if not chat_id:
        raise RuntimeError("Qwen chats/new returned no chat id")
    media_sources = _qwen_media_sources(command.get("messages"))
    uploaded = await _upload_media(current, base_url, proxy_url, request, media_sources, chat_id)
    body = json.dumps(
        build_qwen_request(command, chat_id, uploaded_files=uploaded),
        ensure_ascii=False,
        separators=(",", ":"),
    )
    return (
        f"/api/v2/chat/completions?chat_id={quote(chat_id, safe='')}",
        body,
        _request_headers(base_url, current, request, f"/c/{chat_id}"),
    )


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
        timeout_seconds=300 if method != "GET" else 120,
        impersonate=str(current.get("browser_profile") or "chrome146"),
    )
    merge_credential_patch(current, result)
    return result


async def _upload_media(
    current: dict[str, Any],
    base_url: str,
    proxy_url: str,
    request: ProviderActionRequest,
    sources: list[dict[str, Any]],
    chat_id: str,
) -> list[dict[str, Any]]:
    if not sources:
        return []
    uploaded: list[dict[str, Any]] = []
    user_id = str(current.get("user_id") or current.get("userId") or "")
    for source in sources:
        from .multimodal import decode_inline_data_url

        mime_type, content = decode_inline_data_url(
            str(source.get("data_url") or ""),
            "Qwen image",
            max_bytes=_MAX_UPLOAD_BYTES,
            expected_prefix="image/",
        )
        filename = str(source.get("filename") or f"upload-{uuid4().hex}.bin")
        headers = _request_headers(base_url, current, request, f"/c/{chat_id}")
        token_result = await asyncio.to_thread(
            api_request_sync,
            base_url,
            "POST",
            "/api/v2/files/getstsToken",
            headers=headers,
            body=json.dumps(
                {"filename": filename, "filesize": str(len(content)), "filetype": "image"},
                separators=(",", ":"),
            ),
            proxy_url=proxy_url,
            timeout_seconds=120,
            impersonate=str(current.get("browser_profile") or "chrome146"),
        )
        merge_credential_patch(current, token_result)
        token_body = _json(token_result, "Qwen media upload token")
        sts = token_body.get("data") if isinstance(token_body.get("data"), dict) else {}
        required = {
            name: str(sts.get(name) or "").strip()
            for name in (
                "access_key_id",
                "access_key_secret",
                "security_token",
                "bucketname",
                "region",
                "endpoint",
                "file_id",
                "file_path",
                "file_url",
            )
        }
        if any(not value for value in required.values()):
            raise RuntimeError("Qwen media upload token was rejected")
        upload_url = _oss_upload_url(
            required["endpoint"], required["bucketname"], required["file_path"]
        )
        now = datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ")
        short_date = now[:8]
        region = required["region"].removeprefix("oss-")
        scope = f"{short_date}/{region}/oss/aliyun_v4_request"
        payload_hash = "UNSIGNED-PAYLOAD"
        encoded_object = _encode_object_path(required["file_path"])
        canonical_headers = (
            f"content-type:{mime_type}\n"
            f"x-oss-content-sha256:{payload_hash}\n"
            f"x-oss-date:{now}\n"
            f"x-oss-security-token:{required['security_token']}\n"
        )
        canonical_request = "\n".join(
            [
                "PUT",
                f"/{required['bucketname']}/{encoded_object}",
                "",
                canonical_headers,
                "",
                payload_hash,
            ]
        )
        string_to_sign = "\n".join(
            [
                "OSS4-HMAC-SHA256",
                now,
                scope,
                hashlib.sha256(canonical_request.encode()).hexdigest(),
            ]
        )
        signing_key = _hmac(
            _hmac(
                _hmac(
                    _hmac(
                        ("aliyun_v4" + required["access_key_secret"]).encode(),
                        short_date,
                    ),
                    region,
                ),
                "oss",
            ),
            "aliyun_v4_request",
        )
        signature = hmac.new(signing_key, string_to_sign.encode(), hashlib.sha256).hexdigest()
        upload_result = await asyncio.to_thread(
            api_provider_put_sync,
            upload_url,
            content,
            headers={
                "Content-Type": mime_type,
                "x-oss-content-sha256": payload_hash,
                "x-oss-date": now,
                "x-oss-security-token": required["security_token"],
                "Authorization": (
                    "OSS4-HMAC-SHA256 Credential="
                    + required["access_key_id"]
                    + "/"
                    + scope
                    + ",Signature="
                    + signature
                ),
            },
            proxy_url=proxy_url,
            timeout_seconds=180,
        )
        require_api_success(upload_result, "Qwen OSS upload")
        uploaded.append(_file_object(required, filename, mime_type, len(content), user_id))
    return uploaded


def _request_headers(
    base_url: str,
    current: dict[str, Any],
    request: ProviderActionRequest,
    referer_path: str,
) -> dict[str, str]:
    headers = api_headers(
        base_url,
        current,
        accept="application/json, text/event-stream",
        content_type="application/json",
    )
    headers["Referer"] = base_url.rstrip("/") + referer_path
    risk_headers = current.get("risk_headers")
    if not isinstance(risk_headers, dict):
        risk_headers = current.get("api_headers")
    if isinstance(risk_headers, dict):
        headers.update(merge_allowed_headers(risk_headers, _QWEN_RISK_HEADERS))
    return headers


def _oss_upload_url(endpoint: str, bucket: str, object_name: str) -> str:
    normalized = endpoint.strip()
    if normalized.startswith("//"):
        normalized = "https:" + normalized
    elif "://" not in normalized:
        normalized = "https://" + normalized
    parsed = urlsplit(normalized)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("Qwen OSS endpoint must use HTTPS")
    host = parsed.hostname
    if not host.startswith(bucket + "."):
        host = bucket + "." + host
    port = f":{parsed.port}" if parsed.port is not None else ""
    prefix = parsed.path.rstrip("/")
    return urlunsplit(
        ("https", host + port, prefix + "/" + _encode_object_path(object_name), "", "")
    )


def _encode_object_path(value: str) -> str:
    return "/".join(quote(part, safe="") for part in value.split("/"))


def _file_object(
    sts: dict[str, str],
    filename: str,
    mime_type: str,
    size: int,
    user_id: str,
) -> dict[str, Any]:
    now = int(datetime.now(UTC).timestamp() * 1000)
    inner = {
        "created_at": now,
        "data": {},
        "filename": filename,
        "hash": None,
        "id": sts["file_id"],
        "user_id": user_id,
        "meta": {"name": filename, "size": size, "content_type": mime_type},
        "update_at": now,
        "lastModified": now,
        "name": filename,
        "webkitRelativePath": "",
        "size": size,
        "type": mime_type,
    }
    return {
        "type": "image",
        "file": inner,
        "id": sts["file_id"],
        "url": sts["file_url"],
        "name": filename,
        "collection_name": "",
        "progress": 100,
        "status": "uploaded",
        "greenNet": "success",
        "size": size,
        "error": "",
        "itemId": str(uuid4()),
        "uploadTaskId": str(uuid4()),
        "file_type": mime_type,
        "showType": "image",
        "file_class": "vision",
    }


def _hmac(key: bytes, value: str) -> bytes:
    return hmac.new(key, value.encode(), hashlib.sha256).digest()


def _json(result: dict[str, Any], operation: str) -> dict[str, Any]:
    require_api_success(result, operation)
    try:
        value = json.loads(str(result.get("body") or ""))
    except json.JSONDecodeError as error:
        raise RuntimeError(f"{operation} returned invalid JSON") from error
    if not isinstance(value, dict):
        raise TypeError(f"{operation} returned an invalid object")
    return value


def _base_url(request: ProviderActionRequest) -> str:
    return allowlisted_base_url(
        _runtime_option(request, "base_url"), settings().qwen_base_url, ("qwen.ai",)
    )


def _runtime_option(request: ProviderActionRequest, name: str) -> str | None:
    options = request.payload.get("runtime_options")
    if not isinstance(options, dict):
        return None
    value = options.get(name)
    return str(value).strip() if value is not None else None


def action_bindings(provider: Any):
    return api_action_bindings(provider, QwenApiActionHandler())
