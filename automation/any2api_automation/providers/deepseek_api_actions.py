from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator
from typing import Any

from ..lifecycle.account import credential
from .actions import ProviderAction, ProviderActionRequest
from .api_transport import (
    allowlisted_base_url,
    api_action_bindings,
    api_headers,
    api_request_sync,
    api_stream,
    merge_credential_patch,
    require_api_success,
    same_origin_path,
)
from .deepseek_browser import (
    DEEPSEEK_COMPLETION_PATH,
    DEEPSEEK_POW_PATH,
    DEEPSEEK_SESSION_PATH,
    _challenge,
    _session_id,
    build_deepseek_request,
    build_pow_proof,
    solve_pow,
)
from .deepseek_browser import (
    _headers as deepseek_headers,
)
from .deepseek_settings import settings
from .transport_support import transport_proxy_lease


class DeepseekApiActionHandler:
    """DeepSeek Web API channel; it uses only stored account credentials and HTTP."""

    async def execute(self, request: ProviderActionRequest) -> dict[str, Any]:
        current = credential(dict(request.payload))
        base_url = _base_url(request)
        async with transport_proxy_lease(dict(request.payload), check_url=base_url) as proxy_url:
            operation = request.operation or request.action.default_legacy_operation
            if operation == "models":
                device_id = _required(current, "device_id")
                path = f"/api/v0/client/settings?did={_quote(device_id)}&scope=model"
                return await _request(request, current, base_url, proxy_url, "GET", path, "")
            if operation == "chat":
                path, body, headers = await _chat_input(request, current, base_url, proxy_url)
                return await _request(
                    request,
                    current,
                    base_url,
                    proxy_url,
                    "POST",
                    path,
                    body,
                    headers=headers,
                )
        raise ValueError("DeepSeek API action is not allowlisted")

    async def stream(self, request: ProviderActionRequest) -> AsyncIterator[bytes]:
        if request.action is not ProviderAction.CHAT:
            raise ValueError("DeepSeek API channel only streams the chat action")
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
                yield _frame(
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
    session = await _request(
        request,
        current,
        base_url,
        proxy_url,
        "POST",
        DEEPSEEK_SESSION_PATH,
        "{}",
    )
    require_api_success(session, "DeepSeek create session")
    session_id = _session_id(session)
    challenge = await _request(
        request,
        current,
        base_url,
        proxy_url,
        "POST",
        DEEPSEEK_POW_PATH,
        json.dumps(
            {"target_path": DEEPSEEK_COMPLETION_PATH},
            ensure_ascii=True,
            separators=(",", ":"),
        ),
    )
    require_api_success(challenge, "DeepSeek create POW challenge")
    challenge_value = _challenge(challenge)
    answer = await asyncio.to_thread(solve_pow, challenge_value)
    proof = build_pow_proof(challenge_value, answer)
    options = _runtime_options(request)
    headers = _request_headers(base_url, current, options)
    headers["X-DS-PoW-Response"] = proof
    body = json.dumps(
        build_deepseek_request(command, session_id),
        ensure_ascii=True,
        separators=(",", ":"),
    )
    return DEEPSEEK_COMPLETION_PATH, body, headers


async def _request(
    request: ProviderActionRequest,
    current: dict[str, Any],
    base_url: str,
    proxy_url: str,
    method: str,
    path: str,
    body: str,
    *,
    headers: dict[str, str] | None = None,
) -> dict[str, Any]:
    options = _runtime_options(request)
    result = await asyncio.to_thread(
        api_request_sync,
        base_url,
        method,
        same_origin_path(path),
        headers=headers or _request_headers(base_url, current, options),
        body=body,
        proxy_url=proxy_url,
        timeout_seconds=300 if method == "POST" else 120,
        impersonate=str(current.get("browser_profile") or "chrome146"),
    )
    merge_credential_patch(current, result)
    return result


def _request_headers(
    base_url: str,
    current: dict[str, Any],
    options: dict[str, Any],
) -> dict[str, str]:
    return api_headers(
        base_url,
        current,
        extra=deepseek_headers(current, options),
        accept="application/json, */*",
        content_type="application/json",
    )


def _base_url(request: ProviderActionRequest) -> str:
    return allowlisted_base_url(
        _runtime_option(request, "base_url"),
        settings().deepseek_base_url,
        ("deepseek.com",),
    )


def _runtime_options(request: ProviderActionRequest) -> dict[str, Any]:
    value = request.payload.get("runtime_options")
    if not isinstance(value, dict):
        return {}
    return {
        key: value[key]
        for key in ("bundle_id", "platform", "client_version", "locale", "timezone_offset")
        if value.get(key) is not None
    }


def _runtime_option(request: ProviderActionRequest, name: str) -> str | None:
    options = request.payload.get("runtime_options")
    if not isinstance(options, dict):
        return None
    value = options.get(name)
    return str(value).strip() if value is not None else None


def _required(source: dict[str, Any], name: str) -> str:
    value = str(source.get(name) or "").strip()
    if not value:
        raise ValueError(f"DeepSeek credential requires {name}")
    return value


def _quote(value: str) -> str:
    from urllib.parse import quote

    return quote(value, safe="")


def _frame(kind: str, **payload: Any) -> bytes:
    return (json.dumps({"type": kind, **payload}, separators=(",", ":")) + "\n").encode()


def action_bindings(provider: Any):
    return api_action_bindings(provider, DeepseekApiActionHandler())
