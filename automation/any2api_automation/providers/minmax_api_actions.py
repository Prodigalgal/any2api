from __future__ import annotations

import asyncio
import json
import queue
import threading
import uuid
from collections.abc import AsyncIterator
from typing import Any
from urllib.parse import quote

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
from .api_transport import (
    credential_patch_from_response,
    merge_credential_patch,
    require_api_success,
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

_MAX_API_RESPONSE_BYTES = 8 * 1024 * 1024
_MAX_API_SSE_LINE_BYTES = 2 * 1024 * 1024
_API_QUEUE_STOP = object()


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
        body = command.get("body") if isinstance(command, dict) else None
        if not isinstance(body, str):
            body = payload.get("body")
        if not isinstance(body, str):
            raise TypeError("MinMax files callback command must contain a body")
        return "POST", "/v1/api/files/policy_callback", body
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
            require_api_success(agents, "MinMax agent list")
            agent_id = _select_agent(agents, prepared["agent_role"])
        agent_id = _path_segment(agent_id, "agent")
        session = await asyncio.to_thread(
            _api_request_sync,
            current,
            "POST",
            f"/archon/api/v1/agent/{agent_id}/session",
            json.dumps({"model": prepared["session_model"]}, separators=(",", ":")),
            proxy_url,
        )
        require_api_success(session, "MinMax session creation")
        session_id = _path_segment(_session_id(session), "session")
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
    with (
        CurlSession(impersonate=_impersonate(current)) as client,
        client.stream(
            method,
            url,
            data=body if method not in {"GET", "HEAD"} else None,
            headers=headers,
            proxy=proxy_url or None,
            timeout=core_settings().registration_timeout_seconds,
        ) as response,
    ):
        response_body = _read_bounded(response, _MAX_API_RESPONSE_BYTES)
        credential_patch = credential_patch_from_response(response)
    result: dict[str, Any] = {
        "status": response.status_code,
        "body": response_body.decode("utf-8", "replace"),
        "transport_mode": API_TRANSPORT,
    }
    if credential_patch:
        result["credential_patch"] = credential_patch
    merge_credential_patch(current, result)
    return result


async def _api_stream(
    current: dict[str, Any],
    method: str,
    path: str,
    body: str,
    proxy_url: str,
) -> AsyncIterator[dict[str, Any]]:
    events: queue.Queue[dict[str, Any] | object] = queue.Queue(maxsize=64)
    stop = threading.Event()
    response_holder: list[Any] = [None]

    def publish(event: dict[str, Any] | object) -> bool:
        while not stop.is_set():
            try:
                events.put(event, timeout=0.2)
                return True
            except queue.Full:
                continue
        return False

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
                response_holder[0] = response
                if not publish({"type": "status", "status": response.status_code}):
                    return
                credential_patch = credential_patch_from_response(response)
                if credential_patch:
                    merge_credential_patch(
                        current,
                        {"credential_patch": credential_patch},
                    )
                if credential_patch and not publish(
                    {"type": "credential_patch", "data": credential_patch}
                ):
                    return
                if response.status_code < 200 or response.status_code >= 300:
                    body_bytes = _read_bounded(response, _MAX_API_RESPONSE_BYTES)
                    publish(
                        {
                            "type": "error",
                            "data": body_bytes.decode("utf-8", "replace")[:16_384],
                        }
                    )
                    return
                for line in response.iter_lines():
                    line_bytes = (
                        line if isinstance(line, bytes) else str(line).encode("utf-8", "replace")
                    )
                    if len(line_bytes) > _MAX_API_SSE_LINE_BYTES:
                        raise RuntimeError("MinMax API SSE event exceeds the configured byte limit")
                    value = line_bytes.decode("utf-8", "replace")
                    if (
                        value.startswith("data:")
                        and value[5:].strip()
                        and not publish({"type": "data", "data": value[5:].strip()})
                    ):
                        return
        except Exception as error:  # noqa: BLE001 - transport boundary
            publish(
                {"type": "error", "data": f"MinMax API channel failed ({type(error).__name__})"}
            )
        finally:
            publish(_API_QUEUE_STOP)

    task = asyncio.create_task(asyncio.to_thread(worker))
    try:
        while True:
            try:
                event = await asyncio.to_thread(events.get, True, 0.2)
            except queue.Empty:
                if task.done() and events.empty():
                    break
                continue
            if event is _API_QUEUE_STOP:
                break
            if isinstance(event, dict):
                yield event
    finally:
        stop.set()
        response = response_holder[0]
        close = getattr(response, "close", None)
        if callable(close):
            close()
        if not task.done():
            task.cancel()
        await asyncio.gather(task, return_exceptions=True)


def _read_bounded(response: Any, limit: int) -> bytes:
    chunks: list[bytes] = []
    total = 0
    iterator = getattr(response, "iter_content", None)
    source = (
        iterator(chunk_size=64 * 1024)
        if callable(iterator)
        else (getattr(response, "content", b""),)
    )
    for chunk in source:
        data = chunk.encode() if isinstance(chunk, str) else bytes(chunk)
        total += len(data)
        if total > limit:
            raise RuntimeError("MinMax API response exceeds the configured byte limit")
        chunks.append(data)
    return b"".join(chunks)


def _path_segment(value: str, label: str) -> str:
    normalized = str(value or "").strip()
    if not normalized or any(ord(character) < 0x20 for character in normalized):
        raise ValueError(f"MinMax {label} id is invalid")
    return quote(normalized, safe="")


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
