from __future__ import annotations

import asyncio
import json
import uuid
from collections.abc import AsyncIterator
from typing import Any

from curl_cffi.requests import Session as CurlSession

from ..config import settings as core_settings
from ..lifecycle.account import credential
from .actions import (
    ActionBinding,
    ProviderAction,
    ProviderActionRequest,
    legacy_inference_action_bindings,
    lifecycle_action_bindings,
)
from .base import API_TRANSPORT
from .minmax import (
    _impersonate,
    _select_agent,
    _session_id,
    _signed_request,
    _transport_input,
    _transport_proxy_lease,
    build_minmax_request,
)
from .transport_support import transport_frame


def _api_request_input(payload: dict[str, Any]) -> tuple[str, str, str]:
    operation = str(payload.get("operation") or "")
    if operation == "models":
        return "GET", "/archon/api/v1/config", ""
    if operation == "agents":
        return "GET", "/archon/api/v1/agent?limit=20", ""
    if operation == "files_policy":
        return "GET", "/v1/api/files/request_policy", ""
    if operation == "files_callback":
        command = payload.get("semantic_command")
        if not isinstance(command, dict) or not isinstance(command.get("body"), str):
            raise TypeError("MinMax files callback command must contain a body")
        return "POST", "/v1/api/files/policy_callback", command["body"]
    return _transport_input(payload, stream=False)


class MinmaxApiActionHandler:
    """MinMax 的 API channel 实现；只处理签名 HTTP，不创建浏览器。"""

    async def execute(self, request: ProviderActionRequest) -> dict[str, Any]:
        current = credential(dict(request.payload))
        operation = request.operation or request.action.default_legacy_operation
        if request.action is ProviderAction.CHAT:
            command = dict(request.semantic_command)
            if not command:
                raise TypeError("MinMax semantic command must be an object")
            async with _transport_proxy_lease(dict(request.payload)) as proxy_url:
                method, path, body = await self._semantic_chat_input(current, command, proxy_url)
                return await asyncio.to_thread(
                    _api_request_sync, current, method, path, body, proxy_url
                )
        payload = request.legacy_payload(operation)
        method, path, body = _api_request_input(payload)
        async with _transport_proxy_lease(dict(request.payload)) as proxy_url:
            return await asyncio.to_thread(
                _api_request_sync, current, method, path, body, proxy_url
            )

    async def stream(self, request: ProviderActionRequest) -> AsyncIterator[bytes]:
        if request.action is not ProviderAction.CHAT:
            raise ValueError("MinMax API channel only streams the chat action")
        command = dict(request.semantic_command)
        if not command:
            raise TypeError("MinMax semantic command must be an object")
        current = credential(dict(request.payload))
        async with _transport_proxy_lease(dict(request.payload)) as proxy_url:
            method, path, body = await self._semantic_chat_input(current, command, proxy_url)
            async for event in _api_stream(current, method, path, body, proxy_url):
                event_type = str(event.get("type") or "error")
                details = {key: value for key, value in event.items() if key != "type"}
                yield transport_frame(event_type, **details)

    async def _semantic_chat_input(
        self,
        current: dict[str, Any],
        command: dict[str, Any],
        proxy_url: str,
    ) -> tuple[str, str, str]:
        prepared = build_minmax_request(command)
        if prepared["attachments"]:
            raise ValueError(
                "MinMax API channel does not support media upload; use AUTO or Runtime"
            )
        agent_id = prepared["agent_id"]
        if not agent_id:
            agents = await asyncio.to_thread(
                _api_request_sync, current, "GET", "/archon/api/v1/agent?limit=20", "", proxy_url
            )
            agent_id = _select_agent(agents, prepared["agent_role"])
        session = await asyncio.to_thread(
            _api_request_sync,
            current,
            "POST",
            f"/archon/api/v1/agent/{agent_id}/session",
            json.dumps({"model": prepared["session_model"]}, separators=(",", ":")),
            proxy_url,
        )
        session_id = _session_id(session)
        body = json.dumps(
            {
                "content": prepared["content"],
                "model": prepared["model"],
                "turn_id": str(uuid.uuid4()),
                "enable_team": prepared["enable_team"],
                "worktreeMode": prepared["worktree_mode"],
                "attachments": [],
            },
            ensure_ascii=False,
            separators=(",", ":"),
        )
        return "POST", f"/archon/api/v1/session/{session_id}/message", body


def _api_request_sync(
    current: dict[str, Any],
    method: str,
    path: str,
    body: str,
    proxy_url: str,
) -> dict[str, Any]:
    url, headers = _signed_request(path, method, body, current, stream=False, proxy_url=proxy_url)
    with CurlSession(impersonate=_impersonate(current)) as client:
        response = client.request(
            method,
            url,
            data=body if method not in {"GET", "HEAD"} else None,
            headers=headers,
            proxy=proxy_url or None,
            timeout=core_settings().registration_timeout_seconds,
        )
    return {
        "status": response.status_code,
        "body": response.text,
        "transport_mode": API_TRANSPORT,
    }


async def _api_stream(
    current: dict[str, Any],
    method: str,
    path: str,
    body: str,
    proxy_url: str,
) -> AsyncIterator[dict[str, Any]]:
    loop = asyncio.get_running_loop()
    queue: asyncio.Queue[dict[str, Any] | None] = asyncio.Queue()

    def publish(event: dict[str, Any] | None) -> None:
        asyncio.run_coroutine_threadsafe(queue.put(event), loop).result()

    def worker() -> None:
        try:
            url, headers = _signed_request(
                path, method, body, current, stream=True, proxy_url=proxy_url
            )
            with (
                CurlSession(impersonate=_impersonate(current)) as client,
                client.stream(
                    method,
                    url,
                    data=body,
                    headers=headers,
                    proxy=proxy_url or None,
                    timeout=core_settings().registration_timeout_seconds,
                ) as response,
            ):
                publish({"type": "status", "status": response.status_code})
                if response.status_code >= 400:
                    publish({"type": "error", "data": response.text[:16_384]})
                    return
                for line in response.iter_lines():
                    value = line.decode("utf-8", "replace") if isinstance(line, bytes) else line
                    if value.startswith("data:") and value[5:].strip():
                        publish({"type": "data", "data": value[5:].strip()})
        except Exception as error:  # noqa: BLE001 - transport boundary
            publish(
                {"type": "error", "data": f"MinMax API channel failed ({type(error).__name__})"}
            )
        finally:
            publish(None)

    task = asyncio.create_task(asyncio.to_thread(worker))
    try:
        while True:
            event = await queue.get()
            if event is None:
                break
            yield event
    finally:
        if not task.done():
            task.cancel()
        await asyncio.gather(task, return_exceptions=True)


def action_bindings(provider) -> tuple[ActionBinding, ...]:
    api = MinmaxApiActionHandler()

    async def execute_operation(
        request: ProviderActionRequest,
        operation: str,
    ) -> dict[str, Any]:
        return await api.execute(
            ProviderActionRequest(
                provider_id=request.provider_id,
                action=request.action,
                channel=API_TRANSPORT,
                operation=operation,
                payload=request.payload,
                semantic_command=request.semantic_command,
                runtime_plan=request.runtime_plan,
                method=request.method,
                path=request.path,
                body=request.body,
                stream=request.stream,
            )
        )

    return (
        lifecycle_action_bindings(provider)
        + legacy_inference_action_bindings(provider)
        + (
            ActionBinding(
                action=ProviderAction.MODEL_DISCOVERY,
                channel=API_TRANSPORT,
                execute=lambda request: execute_operation(request, "models"),
                legacy_operation="models",
            ),
            ActionBinding(
                action=ProviderAction.CHAT,
                channel=API_TRANSPORT,
                execute=api.execute,
                stream=api.stream,
                legacy_operation="chat",
            ),
            ActionBinding(
                action=ProviderAction.PROVIDER_QUERY,
                channel=API_TRANSPORT,
                execute=lambda request: execute_operation(request, "agents"),
                legacy_operation="agents",
            ),
            ActionBinding(
                action=ProviderAction.MEDIA_POLICY,
                channel=API_TRANSPORT,
                execute=lambda request: execute_operation(request, "files_policy"),
                legacy_operation="files_policy",
            ),
            ActionBinding(
                action=ProviderAction.MEDIA_CALLBACK,
                channel=API_TRANSPORT,
                execute=lambda request: execute_operation(request, "files_callback"),
                legacy_operation="files_callback",
            ),
            ActionBinding(
                action=ProviderAction.RAW_REQUEST,
                channel=API_TRANSPORT,
                execute=api.execute,
            ),
        )
    )
