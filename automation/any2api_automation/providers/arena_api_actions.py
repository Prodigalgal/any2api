from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator
from typing import Any

from ..lifecycle.account import credential
from .actions import ProviderAction, ProviderActionRequest
from .api_transport import (
    ApiActionError,
    allowlisted_base_url,
    api_action_bindings,
    api_headers,
    api_request_sync,
    api_stream,
    merge_credential_patch,
)
from .arena_browser import (
    _arena_model_search_capability,
    _arena_search_enabled,
    arena_media_sources,
    build_arena_request,
    parse_arena_models,
    resolve_arena_model_id,
)
from .arena_settings import settings
from .transport_support import transport_frame, transport_proxy_lease


class ArenaApiActionHandler:
    """Arena direct API channel for model discovery and text/search evaluation.

    Arena's media uploader and reCAPTCHA escalation are page-owned operations. This
    channel does not recreate either protection: media requests fail closed and a
    provider-issued reCAPTCHA v3 token may be attached when the credential contains one.
    """

    async def execute(self, request: ProviderActionRequest) -> dict[str, Any]:
        current = credential(dict(request.payload))
        base_url = _base_url(request)
        async with transport_proxy_lease(dict(request.payload), check_url=base_url) as proxy_url:
            operation = request.operation or request.action.default_legacy_operation
            if operation == "models":
                return await _models(request, current, base_url, proxy_url)
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
        raise ValueError("Arena API action is not allowlisted")

    async def stream(self, request: ProviderActionRequest) -> AsyncIterator[bytes]:
        if request.action is not ProviderAction.CHAT:
            raise ValueError("Arena API channel only streams the chat action")
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
                include_unprefixed_lines=True,
            ):
                event_type = str(event.get("type") or "error")
                yield transport_frame(
                    event_type,
                    **{key: value for key, value in event.items() if key != "type"},
                )


async def _models(
    request: ProviderActionRequest,
    current: dict[str, Any],
    base_url: str,
    proxy_url: str,
) -> dict[str, Any]:
    result = await asyncio.to_thread(
        api_request_sync,
        base_url,
        "GET",
        settings().arena_page_path,
        headers=_request_headers(base_url, current, request, "text/html,application/xhtml+xml"),
        proxy_url=proxy_url,
        timeout_seconds=120,
        impersonate=str(current.get("browser_profile") or "chrome146"),
    )
    merge_credential_patch(current, result)
    status = int(result.get("status") or 502)
    if status < 200 or status >= 300:
        return result
    try:
        records = parse_arena_models(str(result.get("body") or ""))
    except (TypeError, ValueError):
        return {**result, "status": 502, "body": "Arena model catalog is unavailable"}
    return {
        **result,
        "body": json.dumps(
            {"models": records, "source": "arena_direct_page"},
            ensure_ascii=False,
            separators=(",", ":"),
        ),
    }


async def _chat_input(
    request: ProviderActionRequest,
    current: dict[str, Any],
    base_url: str,
    proxy_url: str,
) -> tuple[str, str, dict[str, str]]:
    command = dict(request.semantic_command)
    if arena_media_sources(command.get("messages")):
        raise ValueError(
            "Arena API media upload is page-owned; use Runtime or AUTO for image/PDF input"
        )
    catalog = await _models(request, current, base_url, proxy_url)
    status = int(catalog.get("status") or 502)
    if status < 200 or status >= 300:
        raise ApiActionError(
            "Arena model catalog is unavailable",
            status=status,
            body=str(catalog.get("body") or ""),
        )
    try:
        catalog_body = json.loads(str(catalog.get("body") or "{}"))
        records = catalog_body.get("models")
        if not isinstance(records, list):
            raise TypeError("Arena model catalog is invalid")
        options = command.get("providerOptions")
        controls = command.get("controls")
        options = options if isinstance(options, dict) else {}
        controls = controls if isinstance(controls, dict) else {}
        model_id = resolve_arena_model_id(
            records,
            str(command.get("model") or ""),
            explicit_model_id=options.get("model_id"),
        )
        web_search = _arena_search_enabled(options, controls)
        if web_search and _arena_model_search_capability(records, model_id) is False:
            raise ValueError("Arena selected model does not expose web search")
        body_value = build_arena_request(command, model_id=model_id, attachments=[])
    except (TypeError, ValueError, json.JSONDecodeError) as error:
        raise ValueError("Arena model or semantic command is invalid") from error
    recaptcha = _recaptcha_v3_token(request, current)
    if recaptcha:
        body_value["recaptchaV3Token"] = recaptcha
    body = json.dumps(body_value, ensure_ascii=False, separators=(",", ":"))
    return settings().arena_chat_path, body, _request_headers(base_url, current, request, "*/*")


def _request_headers(
    base_url: str,
    current: dict[str, Any],
    request: ProviderActionRequest,
    accept: str,
) -> dict[str, str]:
    return api_headers(
        base_url,
        current,
        accept=accept,
        content_type="text/plain;charset=UTF-8" if accept == "*/*" else None,
        cookie_fields=(
            "arena-auth-prod-v1",
            "arena-auth-prod-v1.0",
            "arena-auth-prod-v1.1",
        ),
        bearer_names=(),
    )


def _recaptcha_v3_token(request: ProviderActionRequest, current: dict[str, Any]) -> str:
    for source in (request.payload.get("runtime_options"), current):
        if not isinstance(source, dict):
            continue
        for name in ("recaptcha_v3_token", "recaptchaV3Token"):
            token = str(source.get(name) or "").strip()
            if token:
                return token
    return ""


def _base_url(request: ProviderActionRequest) -> str:
    return allowlisted_base_url(
        _runtime_option(request, "base_url"), settings().arena_base_url, ("arena.ai",)
    )


def _runtime_option(request: ProviderActionRequest, name: str) -> str | None:
    options = request.payload.get("runtime_options")
    if not isinstance(options, dict):
        return None
    value = options.get(name)
    return str(value).strip() if value is not None else None


def action_bindings(provider: Any):
    return api_action_bindings(provider, ArenaApiActionHandler())
