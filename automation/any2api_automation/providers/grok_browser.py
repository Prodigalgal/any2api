from __future__ import annotations

import json
from typing import Any

from ..lifecycle.account import required
from .base import reject_raw_request
from .grok_settings import settings
from .multimodal import text_content, xai_input_content
from .page_fetch_browser import PageFetchBrowserRuntime
from .runtime_rules import RuntimePlan

_FORWARDED_FIELDS = (
    "temperature",
    "top_p",
    "tool_choice",
    "parallel_tool_calls",
    "prompt_cache_key",
    "user",
    "max_output_tokens",
    "stream_tool_calls",
)
_REASONING_MODELS = frozenset(
    {"grok-4.5", "grok-4.3", "grok-4.20-0309-reasoning", "grok-4.20-multi-agent-0309"}
)


class GrokOfficialBrowserTransport(PageFetchBrowserRuntime):
    """Keeps Grok's physical HTTP/SSE boundary inside the Camoufox page."""

    def __init__(self, base_url: str) -> None:
        super().__init__(
            "grok",
            base_url,
            allowed_domain_suffixes=("x.ai", "grok.com"),
            identity_fields=("email", "access_token", "refresh_token", "sso", "sso-rw"),
            cookie_fields=("sso", "sso-rw", "sso_rw"),
            page_url=base_url,
        )

    async def chat_request(
        self,
        credential: dict[str, Any],
        proxy_url: str,
        plan: RuntimePlan,
        command: dict[str, Any],
        *,
        timeout_ms: int | None = None,
        runtime_options: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        return await self.request(
            credential,
            proxy_url,
            plan,
            method="POST",
            endpoint_key="chat",
            headers=_request_headers(credential, command, runtime_options),
            body=json.dumps(build_grok_request(command), ensure_ascii=True, separators=(",", ":")),
            timeout_ms=timeout_ms,
        )

    async def chat_stream(
        self,
        credential: dict[str, Any],
        proxy_url: str,
        plan: RuntimePlan,
        command: dict[str, Any],
        *,
        timeout_ms: int | None = None,
        runtime_options: dict[str, Any] | None = None,
    ):
        async for event in self.stream(
            credential,
            proxy_url,
            plan,
            method="POST",
            endpoint_key="chat",
            headers=_request_headers(credential, command, runtime_options),
            body=json.dumps(build_grok_request(command), ensure_ascii=True, separators=(",", ":")),
            timeout_ms=timeout_ms,
        ):
            yield event

    async def models_request(
        self,
        credential: dict[str, Any],
        proxy_url: str,
        plan: RuntimePlan,
        runtime_options: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        return await self.request(
            credential,
            proxy_url,
            plan,
            method="GET",
            endpoint_key="models",
            headers=_request_headers(credential, {}, runtime_options),
            timeout_ms=plan.active.rules.canary_timeout_seconds * 1000,
        )


def build_grok_request(command: dict[str, Any]) -> dict[str, Any]:
    _validate_semantic_command(command)
    controls = command["controls"]
    payload: dict[str, Any] = {
        "model": str(command["model"]),
        "stream": True,
        "store": False,
        "parallel_tool_calls": controls.get("parallel_tool_calls", True),
        "include": ["reasoning.encrypted_content"],
        "input": _input(command.get("messages")),
    }
    for field in _FORWARDED_FIELDS:
        if field in command["generation"]:
            payload[field] = _copy(command["generation"][field])
        elif field in controls:
            payload[field] = _copy(controls[field])
    if "max_output_tokens" not in payload:
        for field in ("max_completion_tokens", "max_tokens"):
            if field in command["generation"]:
                payload["max_output_tokens"] = command["generation"][field]
                break
    payload["tools"] = _tools(command.get("tools"))
    skip_search = bool(
        command["providerOptions"].get("skip_x_search") or controls.get("_skip_x_search")
    )
    if not skip_search and not any(item.get("type") == "x_search" for item in payload["tools"]):
        payload["tools"].insert(0, {"type": "x_search"})
    if not payload["tools"]:
        payload.pop("tools")
    payload["reasoning"] = _reasoning(command)
    conversation_id = _conversation_id(command)
    if conversation_id:
        payload.setdefault("prompt_cache_key", conversation_id)
    return payload


def _request_headers(
    credential: dict[str, Any],
    command: dict[str, Any],
    runtime_options: dict[str, Any] | None = None,
) -> dict[str, str]:
    config = settings()
    options = runtime_options if isinstance(runtime_options, dict) else {}
    token = required(credential, "access_token", "key", "token")
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "text/event-stream",
        "Content-Type": "application/json",
        "x-xai-token-auth": str(options.get("token_auth") or config.grok_token_auth),
        "x-grok-client-version": str(options.get("client_version") or config.grok_client_version),
        "x-grok-client-identifier": str(
            options.get("client_identifier") or config.grok_client_identifier
        ),
    }
    conversation_id = _conversation_id(command)
    if conversation_id:
        headers["x-grok-conv-id"] = conversation_id
    return headers


def _conversation_id(command: dict[str, Any]) -> str:
    controls = command.get("controls")
    if not isinstance(controls, dict):
        controls = {}
    for field in (
        "prompt_cache_key",
        "conversation_id",
        "conversation",
        "thread_id",
        "session_id",
    ):
        value = str(controls.get(field) or "").strip()
        if value:
            return value
    metadata = controls.get("metadata")
    if isinstance(metadata, dict):
        for field in (
            "prompt_cache_key",
            "session_id",
            "sessionId",
            "thread_id",
            "conversation_id",
            "user_id",
        ):
            value = str(metadata.get(field) or "").strip()
            if value:
                return value
    return ""


def _input(messages: Any) -> list[dict[str, Any]]:
    if not isinstance(messages, list):
        raise TypeError("Grok messages must be an array")
    output: list[dict[str, Any]] = []
    for message in messages:
        if not isinstance(message, dict):
            continue
        role = str(message.get("role") or "user").lower()
        if role == "tool":
            output.append(
                {
                    "type": "function_call_output",
                    "call_id": str(message.get("tool_call_id") or message.get("call_id") or ""),
                    "output": _content_text(message.get("content")),
                }
            )
            continue
        if role == "assistant" and isinstance(message.get("tool_calls"), list):
            for call in message["tool_calls"]:
                if not isinstance(call, dict):
                    continue
                function = call.get("function")
                function = function if isinstance(function, dict) else {}
                output.append(
                    {
                        "type": "function_call",
                        "id": str(call.get("id") or ""),
                        "call_id": str(call.get("id") or "call_node"),
                        "name": str(function.get("name") or call.get("name") or ""),
                        "arguments": str(
                            function.get("arguments") or call.get("arguments") or "{}"
                        ),
                    }
                )
        normalized_role = "developer" if role == "system" else role
        content = xai_input_content(message.get("content", ""), "Grok", role=normalized_role)
        if not content or all(
            block.get("type") in {"input_text", "output_text"}
            and not str(block.get("text") or "").strip()
            for block in content
        ):
            continue
        output.append(
            {
                "type": "message",
                "role": normalized_role,
                "content": content,
            }
        )
    return output


def _tools(raw_tools: Any) -> list[dict[str, Any]]:
    if not isinstance(raw_tools, list):
        return []
    output: list[dict[str, Any]] = []
    for raw in raw_tools:
        if not isinstance(raw, dict):
            continue
        function = raw.get("function")
        source = function if isinstance(function, dict) else raw
        name = str(source.get("name") or "").strip()
        if not name:
            continue
        tool: dict[str, Any] = {"type": "function", "name": name}
        if source.get("description") is not None:
            tool["description"] = source["description"]
        tool["parameters"] = source.get("parameters") or {
            "type": "object",
            "properties": {},
        }
        output.append(tool)
    return output


def _reasoning(command: dict[str, Any]) -> dict[str, Any]:
    value = _copy(command.get("reasoning") or {})
    if not isinstance(value, dict):
        value = {}
    if "effort" not in value:
        effort = (
            command["providerOptions"].get("reasoning_effort")
            or command["controls"].get("reasoning_effort")
            or value.get("effort")
            or "low"
        )
        value["effort"] = str(effort)
    value.setdefault("summary", "auto")
    return value if str(command.get("model") or "") in _REASONING_MODELS else {}


def _content_text(value: Any) -> str:
    if value is None:
        return ""
    return text_content(value, "Grok")


def _copy(value: Any) -> Any:
    return json.loads(json.dumps(value, ensure_ascii=True))


def _validate_semantic_command(command: dict[str, Any]) -> None:
    reject_raw_request(command, "Grok")
    if not isinstance(command, dict) or command.get("schemaVersion") != 1:
        raise ValueError("Grok semantic command schema is unsupported")
    if not str(command.get("model") or "").strip():
        raise ValueError("Grok semantic command requires a model")
    if not isinstance(command.get("messages"), list):
        raise TypeError("Grok semantic command messages must be an array")
    for field in ("generation", "reasoning", "providerOptions", "controls"):
        if not isinstance(command.get(field), dict):
            raise TypeError(f"Grok semantic command {field} must be an object")
