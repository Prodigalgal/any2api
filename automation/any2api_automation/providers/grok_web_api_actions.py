from __future__ import annotations

import asyncio
import json
import logging
from collections.abc import AsyncIterator
from typing import Any
from urllib.parse import quote, urlsplit
from uuid import uuid4

import websockets
from websockets.exceptions import WebSocketException

from ..lifecycle.account import credential
from .actions import ProviderAction, ProviderActionRequest
from .api_transport import (
    allowlisted_base_url,
    api_action_bindings,
    api_request_sync,
)
from .grok_web_browser import (
    _MODEL_IDS,
    _credential_cookies,
    build_grok_web_request,
)
from .transport_support import transport_frame, transport_proxy_lease

logger = logging.getLogger("any2api_automation.providers.grok_web_api_actions")

_BASE_URL = "https://grok.com"
_HOST_SUFFIXES = ("grok.com", "x.ai")
_DEFAULT_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/146.0.0.0 Safari/537.36"
)


class GrokWebApiActionHandler:
    """Direct API/WebSocket channel for Grok Web inference and model discovery."""

    async def execute(self, request: ProviderActionRequest) -> dict[str, Any]:
        operation = request.operation or request.action.default_legacy_operation
        current = credential(dict(request.payload))
        base_url = _base_url(request)
        if operation == "models":
            return await _discover_models(request, current, base_url)
        raise ValueError(f"Grok Web API action is not supported: {operation}")

    async def stream(self, request: ProviderActionRequest) -> AsyncIterator[bytes]:
        if request.action is not ProviderAction.CHAT:
            raise ValueError("Grok Web API channel only streams the chat action")
        current = credential(dict(request.payload))
        base_url = _base_url(request)
        async with transport_proxy_lease(dict(request.payload), check_url=base_url) as proxy_url:
            async for frame in _chat_stream(request, current, base_url, proxy_url):
                yield frame


def grok_web_api_action_bindings(provider: Any):
    return api_action_bindings(provider, GrokWebApiActionHandler())


def _base_url(request: ProviderActionRequest) -> str:
    raw = request.payload.get("base_url") if isinstance(request.payload, dict) else None
    return allowlisted_base_url(raw, _BASE_URL, _HOST_SUFFIXES)


def _cookie_header(current: dict[str, Any]) -> str:
    cookies = _credential_cookies(current)
    return "; ".join(f"{c['name']}={c['value']}" for c in cookies)


async def _discover_models(
    request: ProviderActionRequest,
    current: dict[str, Any],
    base_url: str,
) -> dict[str, Any]:
    cookie_str = _cookie_header(current)
    if not cookie_str:
        return {
            "status": 401,
            "body": json.dumps({"error": "Grok Web credential requires an SSO cookie"}),
            "content_type": "application/json",
            "transport_mode": "api",
        }
    async with transport_proxy_lease(dict(request.payload), check_url=base_url) as proxy_url:
        result = await asyncio.to_thread(
            api_request_sync,
            base_url,
            "GET",
            "/api/auth/session",
            headers={
                "Cookie": cookie_str,
                "Accept": "application/json",
                "User-Agent": _DEFAULT_USER_AGENT,
            },
            proxy_url=proxy_url,
            timeout_seconds=30,
            impersonate="chrome146",
        )
        status = int(result.get("status") or 502)
        if status < 200 or status >= 300:
            return {
                "status": status,
                "body": str(result.get("body") or ""),
                "content_type": "application/json",
                "transport_mode": "api",
            }
        body = json.dumps(
            {"data": [{"id": model_id, "object": "model"} for model_id in _MODEL_IDS]},
            separators=(",", ":"),
        )
        return {
            "status": 200,
            "body": body,
            "content_type": "application/json",
            "transport_mode": "api",
        }


async def _chat_stream(
    request: ProviderActionRequest,
    current: dict[str, Any],
    base_url: str,
    proxy_url: str,
) -> AsyncIterator[bytes]:
    cookie_str = _cookie_header(current)
    if not cookie_str:
        yield transport_frame("status", status=401)
        yield transport_frame("error", data="Grok Web API channel requires an SSO cookie")
        return

    # Step 1: Resolve user ID from session
    session_result = await asyncio.to_thread(
        api_request_sync,
        base_url,
        "GET",
        "/api/auth/session",
        headers={
            "Cookie": cookie_str,
            "Accept": "application/json",
            "User-Agent": _DEFAULT_USER_AGENT,
        },
        proxy_url=proxy_url,
        timeout_seconds=30,
        impersonate="chrome146",
    )
    status = int(session_result.get("status") or 502)
    if status < 200 or status >= 300:
        yield transport_frame("status", status=status)
        yield transport_frame(
            "error",
            data=str(session_result.get("body") or f"Grok Web session failed with HTTP {status}"),
        )
        return

    try:
        session_json = json.loads(str(session_result.get("body") or "{}"))
        user_id = str(session_json.get("session", {}).get("userId") or "").strip()
    except (json.JSONDecodeError, AttributeError):
        user_id = ""

    if not user_id:
        yield transport_frame("status", status=401)
        yield transport_frame("error", data="Grok Web session is not authenticated")
        return

    # Step 2: Establish direct WebSocket connection
    command = build_grok_web_request(dict(request.semantic_command))
    host = urlsplit(base_url).netloc
    ws_url = f"wss://{host}/ws/mgw/?uid={quote(user_id)}"
    headers = {
        "Cookie": cookie_str,
        "Origin": base_url,
    }

    try:
        async with websockets.connect(
            ws_url,
            additional_headers=headers,
            user_agent_header=_DEFAULT_USER_AGENT,
            proxy=proxy_url or None,
            open_timeout=30,
        ) as ws:
            yield transport_frame("status", status=200)

            # Step 3: Initiate session
            session_payload: dict[str, Any] = {
                "event": {
                    "type": "session.create",
                    "event_id": f"evt_session_{uuid4().hex}",
                    "session": {
                        "model": command["mode"],
                        "x_grok": {
                            "protocol_capabilities": ["conversation_attached", "custom_methods_v1"],
                            "use_chunk": True,
                            "enable_side_by_side": command.get("enableSideBySide", True),
                            "force_side_by_side": command.get("forceSideBySide", False),
                            "enable_image_generation": command.get("enableImageGeneration", False),
                            "image_generation_count": command.get("imageGenerationCount", 2),
                            "disable_text_follow_ups": command.get("disableTextFollowUps", False),
                            "disable_artifact": True,
                            "force_concise": command.get("forceConcise", False),
                            "disable_memory": command.get("disableMemory", True),
                            "keep_context": False,
                            "is_temporary": True,
                        },
                    },
                }
            }
            if command.get("conversationId"):
                x_grok = session_payload["event"]["session"]["x_grok"]
                x_grok["conversation_id"] = command["conversationId"]
                x_grok["load_existing"] = True
                x_grok["needs_history"] = False

            await ws.send(json.dumps(session_payload))

            # Step 4: Stream response frames
            prompt_sent = False
            async for raw_message in ws:
                raw_text = (
                    raw_message
                    if isinstance(raw_message, str)
                    else raw_message.decode("utf-8", errors="replace")
                )
                yield transport_frame("data", data=raw_text)

                try:
                    root = json.loads(raw_text)
                except json.JSONDecodeError:
                    continue

                event = root.get("event") or {}
                event_type = event.get("type")
                if event_type == "error":
                    error_data = event.get("error") or event
                    yield transport_frame("error", data=json.dumps(error_data))
                    break

                if event_type == "conversation.attached" and not prompt_sent:
                    prompt_sent = True
                    session_id = str(root.get("session_id") or "").strip()
                    if not session_id:
                        yield transport_frame("error", data="Grok Web gateway omitted session_id")
                        break

                    item_event: dict[str, Any] = {
                        "type": "conversation.item.create",
                        "event_id": f"evt_msg_{uuid4().hex}",
                        "item": {
                            "type": "message",
                            "role": "user",
                            "x_grok": {
                                "client_message_id": str(uuid4()),
                                "input_chunks": [{"text": {"text": command["message"]}}],
                            },
                        },
                    }
                    if command.get("parentResponseId"):
                        item_event["parent_response_id"] = command["parentResponseId"]

                    await ws.send(json.dumps({"session_id": session_id, "event": item_event}))
                    await ws.send(
                        json.dumps(
                            {
                                "session_id": session_id,
                                "event": {
                                    "type": "response.create",
                                    "event_id": f"evt_resp_{uuid4().hex}",
                                },
                            }
                        )
                    )

                if event_type == "response.done":
                    break

    except (TimeoutError, WebSocketException, OSError) as exc:
        logger.warning("Grok Web API WebSocket failed: %s", exc)
        yield transport_frame("status", status=502)
        yield transport_frame("error", data=f"Grok Web direct API connection error: {exc}")
