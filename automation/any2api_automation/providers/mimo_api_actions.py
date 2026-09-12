from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator
from typing import Any
from urllib.parse import quote, urlsplit

from ..lifecycle.account import credential
from .actions import ProviderAction, ProviderActionRequest
from .api_transport import (
    ApiActionError,
    allowlisted_base_url,
    api_action_bindings,
    api_headers,
    api_provider_put_sync,
    api_request_sync,
    api_stream,
    cookie_header,
    credential_cookie_map,
    merge_credential_patch,
    require_api_success,
)
from .mimo_browser import (
    _headers as mimo_headers,
)
from .mimo_browser import (
    _mimo_media_sources,
    _with_phase,
    build_mimo_chat_request,
)
from .mimo_settings import settings
from .multimodal import decode_inline_data_url
from .transport_support import transport_frame, transport_proxy_lease

_MAX_UPLOAD_BYTES = 25 * 1024 * 1024


class MimoApiActionHandler:
    """MiMo direct HTTP channel, including signed object-storage media upload."""

    async def execute(self, request: ProviderActionRequest) -> dict[str, Any]:
        current = credential(dict(request.payload))
        base_url = _base_url(request)
        async with transport_proxy_lease(dict(request.payload), check_url=base_url) as proxy_url:
            operation = request.operation or request.action.default_legacy_operation
            if operation == "models":
                path = _with_phase("/open-apis/bot/config", current)
                return await _request(request, current, base_url, proxy_url, "GET", path, "")
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
                _merge_credential_patch(current, result)
                return result
        raise ValueError("MiMo API action is not allowlisted")

    async def stream(self, request: ProviderActionRequest) -> AsyncIterator[bytes]:
        if request.action is not ProviderAction.CHAT:
            raise ValueError("MiMo API channel only streams the chat action")
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
                if str(event.get("type") or "") == "credential_patch":
                    normalized = {"credential_patch": event.get("data")}
                    _merge_credential_patch(current, normalized)
                    event = {
                        **event,
                        "data": normalized.get("credential_patch", event.get("data")),
                    }
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
    sources = _mimo_media_sources(command.get("messages"))
    uploaded = await _upload_media(current, base_url, proxy_url, sources, request)
    body = json.dumps(
        build_mimo_chat_request(command, uploaded_media=uploaded),
        ensure_ascii=True,
        separators=(",", ":"),
    )
    return (
        _with_phase("/open-apis/bot/chat", current),
        body,
        _request_headers(base_url, current, request),
    )


async def _request(
    request: ProviderActionRequest,
    current: dict[str, Any],
    base_url: str,
    proxy_url: str,
    method: str,
    path: str,
    body: str,
) -> dict[str, Any]:
    result = await asyncio.to_thread(
        api_request_sync,
        base_url,
        method,
        path,
        headers=_request_headers(base_url, current, request),
        body=body,
        proxy_url=proxy_url,
        timeout_seconds=120 if method == "GET" else 300,
        impersonate=str(current.get("browser_profile") or "chrome146"),
    )
    _merge_credential_patch(current, result)
    return result


async def _upload_media(
    current: dict[str, Any],
    base_url: str,
    proxy_url: str,
    sources: list[dict[str, str]],
    request: ProviderActionRequest,
) -> list[dict[str, Any]]:
    uploaded: list[dict[str, Any]] = []
    phase = str(current.get("xiaomichatbot_ph") or "").strip()
    if sources and not phase:
        raise ValueError("MiMo media upload requires xiaomichatbot_ph")
    for source in sources:
        mime_type, content = decode_inline_data_url(
            source["dataUrl"],
            "MiMo image",
            max_bytes=_MAX_UPLOAD_BYTES,
            expected_prefix="image/",
        )
        headers = _request_headers(base_url, current, request)
        info = await asyncio.to_thread(
            api_request_sync,
            base_url,
            "POST",
            _with_phase("/open-apis/resource/genUploadInfo", current),
            headers=headers,
            body=json.dumps({"fileName": source["filename"]}, separators=(",", ":")),
            proxy_url=proxy_url,
            timeout_seconds=120,
            impersonate=str(current.get("browser_profile") or "chrome146"),
        )
        _merge_credential_patch(current, info)
        require_api_success(info, "MiMo media upload information")
        info_body = _json(info, "MiMo media upload information")
        info_data = info_body.get("data") if isinstance(info_body.get("data"), dict) else info_body
        upload_url = str(info_data.get("uploadUrl") or "").strip()
        resource_url = str(info_data.get("resourceUrl") or "").strip()
        object_name = str(info_data.get("objectName") or "").strip()
        if not upload_url or not resource_url or not object_name:
            raise RuntimeError("MiMo media upload information was rejected")
        _require_https(upload_url, "MiMo upload URL")
        _require_https(resource_url, "MiMo resource URL")
        uploaded_response = await asyncio.to_thread(
            api_provider_put_sync,
            upload_url,
            content,
            headers={"Content-Type": mime_type},
            proxy_url=proxy_url,
            timeout_seconds=180,
        )
        require_api_success(uploaded_response, "MiMo object upload")
        parsed: dict[str, Any] | None = None
        last_status = 0
        last_code = "missing"
        for attempt in range(5):
            headers = _request_headers(base_url, current, request)
            parse_path = (
                _with_phase("/open-apis/resource/parse", current)
                + "&fileUrl="
                + quote(resource_url, safe="")
                + "&objectName="
                + quote(object_name, safe="")
                + "&model="
                + quote(str(request.semantic_command.get("model") or ""), safe="")
            )
            parsed_result = await asyncio.to_thread(
                api_request_sync,
                base_url,
                "POST",
                parse_path,
                headers=headers,
                body="{}",
                proxy_url=proxy_url,
                timeout_seconds=120,
                impersonate=str(current.get("browser_profile") or "chrome146"),
            )
            _merge_credential_patch(current, parsed_result)
            last_status = int(parsed_result.get("status") or 502)
            if last_status < 200 or last_status >= 300:
                if attempt < 4:
                    await asyncio.sleep(2)
                    continue
                break
            try:
                parsed_body = _json(parsed_result, "MiMo media parse")
            except (RuntimeError, TypeError):
                last_code = "invalid_json"
                if attempt < 4:
                    await asyncio.sleep(2)
                    continue
                break
            parsed_data = (
                parsed_body.get("data")
                if isinstance(parsed_body.get("data"), dict)
                else parsed_body
            )
            last_code = str(parsed_body.get("code") or "missing")
            if (
                parsed_body.get("code") in {None, 0, "0"}
                and isinstance(parsed_data, dict)
                and str(parsed_data.get("id") or "").strip()
            ):
                parsed = parsed_data
                break
            if attempt < 4:
                await asyncio.sleep(2)
        if parsed is None:
            if last_status < 200 or last_status >= 300:
                raise ApiActionError(
                    f"MiMo media parse returned HTTP {last_status} after retries",
                    status=last_status,
                )
            raise RuntimeError(
                f"MiMo media parsing did not complete status={last_status} code={last_code}"
            )
        uploaded.append(
            {
                "mediaType": "image",
                "fileUrl": resource_url,
                "compressedVideoUrl": "",
                "audioTrackUrl": "",
                "name": source["filename"],
                "size": len(content),
                "status": "completed",
                "objectName": object_name,
                "tokenUsage": int(parsed.get("tokenUsage") or 0),
                "url": str(parsed["id"]),
            }
        )
    return uploaded


def _request_headers(
    base_url: str,
    current: dict[str, Any],
    request: ProviderActionRequest,
) -> dict[str, str]:
    headers = api_headers(
        base_url,
        current,
        extra=mimo_headers(),
        accept="*/*",
        content_type="application/json",
        bearer_names=(),
    )
    cookies = credential_cookie_map(current)
    for cookie_name, field in (
        ("serviceToken", "service_token"),
        ("userId", "user_id"),
        ("xiaomichatbot_ph", "xiaomichatbot_ph"),
    ):
        value = str(current.get(field) or "").strip()
        if value:
            cookies[cookie_name] = value
    if cookies:
        headers["Cookie"] = cookie_header(cookies)
    return headers


def _merge_credential_patch(
    current: dict[str, Any],
    result: dict[str, Any],
) -> None:
    patch = result.get("credential_patch")
    if not isinstance(patch, dict):
        return
    cookies = patch.get("cookies")
    if not isinstance(cookies, dict):
        merge_credential_patch(current, result)
        return
    normalized_patch = dict(patch)
    for cookie_name, field in (
        ("serviceToken", "service_token"),
        ("userId", "user_id"),
        ("xiaomichatbot_ph", "xiaomichatbot_ph"),
    ):
        value = str(cookies.get(cookie_name) or "").strip()
        if value:
            current[field] = value
            normalized_patch[field] = value
    result["credential_patch"] = normalized_patch
    merge_credential_patch(current, result)


def _base_url(request: ProviderActionRequest) -> str:
    return allowlisted_base_url(
        _runtime_option(request, "base_url"), settings().mimo_base_url, ("xiaomimimo.com",)
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


def _require_https(value: str, label: str) -> None:
    parsed = urlsplit(value)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise RuntimeError(f"{label} must use HTTPS")


def action_bindings(provider: Any):
    return api_action_bindings(provider, MimoApiActionHandler())
