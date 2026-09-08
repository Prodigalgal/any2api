from __future__ import annotations

import json
import time
from typing import Any

from .longcat_settings import settings
from .multimodal import text_content
from .page_fetch_browser import PageFetchBrowserRuntime
from .runtime_rules import RuntimePlan

_MODEL_MODES = {
    "longcat-flash": ("1", False, False),
    "longcat-default": ("1", False, False),
    "longcat-thinking": ("1", True, False),
    "longcat-reason": ("1", True, False),
    "longcat-search": ("1", False, True),
    "longcat-reason-search": ("1", True, True),
    "longcat-pro": ("2", True, True),
}


class LongcatOfficialBrowserTransport(PageFetchBrowserRuntime):
    """Runs LongCat session creation and SSE completion in the account page."""

    def __init__(self, base_url: str) -> None:
        super().__init__(
            "longcat",
            base_url,
            allowed_domain_suffixes=("longcat.chat",),
            identity_fields=(
                "email",
                "cookie",
                "passport_token_key",
                "passport_token",
            ),
            cookie_fields=("passport_token_key", "_lxsdk_cuid", "_lxsdk_s"),
            require_cookie=True,
            page_url=base_url.rstrip("/") + "/t",
        )

    async def session_request(
        self,
        credential: dict[str, Any],
        proxy_url: str,
        plan: RuntimePlan,
        agent_id: str,
        *,
        runtime_options: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        return await self.request(
            credential,
            proxy_url,
            plan,
            method="POST",
            endpoint_key="session",
            headers=_headers(runtime_options),
            body=json.dumps(
                {"model": "", "agentId": agent_id},
                ensure_ascii=True,
                separators=(",", ":"),
            ),
            timeout_ms=plan.active.rules.canary_timeout_seconds * 1000,
        )

    async def chat_stream(
        self,
        credential: dict[str, Any],
        command: dict[str, Any],
        proxy_url: str,
        plan: RuntimePlan,
        *,
        runtime_options: dict[str, Any] | None = None,
    ):
        prepared = build_longcat_request(command)
        session = await self.session_request(
            credential,
            proxy_url,
            plan,
            prepared["agent_id"],
            runtime_options=runtime_options,
        )
        conversation_id = _conversation_id(session)
        options = runtime_options if isinstance(runtime_options, dict) else {}
        headers = _headers(options)
        body = {
            "content": prepared["content"],
            "conversationId": conversation_id,
            "agentId": prepared["agent_id"],
            "reasonEnabled": 1 if prepared["reason_enabled"] else 0,
            "searchEnabled": 1 if prepared["search_enabled"] else 0,
            "regenerate": 0,
            "parentMessageId": 0,
            "files": [],
        }
        async for event in self.stream(
            credential,
            proxy_url,
            plan,
            method="POST",
            endpoint_key="chat",
            headers=headers,
            body=json.dumps(body, ensure_ascii=True, separators=(",", ":")),
            timeout_ms=300_000,
        ):
            yield event


def build_longcat_request(command: dict[str, Any]) -> dict[str, Any]:
    _validate_command(command)
    model = str(command["model"])
    default_agent, default_reason, default_search = _MODEL_MODES.get(
        model, ("1", False, False)
    )
    options = command["providerOptions"]
    raw = command.get("rawRequest") if isinstance(command.get("rawRequest"), dict) else {}
    agent_id = _string(options.get("agent_id"), str(raw.get("agent_id") or default_agent))
    reason = _bool(
        options.get("reason_enabled"),
        raw.get("reason_enabled")
        if isinstance(raw.get("reason_enabled"), bool)
        else _reasoning(command, default_reason),
    )
    search = _bool(
        options.get("search_enabled"),
        raw.get("search_enabled")
        if isinstance(raw.get("search_enabled"), bool)
        else default_search,
    )
    tools = _normalize_tools(command.get("tools"))
    choice = raw.get("tool_choice")
    if choice == "none":
        tools = []
    content = _prompt(command.get("messages"))
    if tools:
        content = _append_tool_contract(content, tools, choice, raw.get("parallel_tool_calls"))
    return {
        "content": content,
        "agent_id": agent_id,
        "reason_enabled": reason,
        "search_enabled": search,
    }


def _headers(runtime_options: dict[str, Any] | None) -> dict[str, str]:
    options = runtime_options if isinstance(runtime_options, dict) else {}
    config = settings()
    return {
        "Accept": "application/json, text/event-stream",
        "Content-Type": "application/json",
        "m-appkey": str(options.get("app_key") or config.longcat_app_key),
        "m-traceid": str(options.get("trace_id") or int(time.time() * 1000)),
        "x-client-language": str(options.get("language") or config.longcat_language),
        "x-requested-with": str(
            options.get("requested_with") or config.longcat_requested_with
        ),
    }


def _conversation_id(result: dict[str, Any]) -> str:
    status = int(result.get("status") or 502)
    if status >= 400:
        raise RuntimeError(f"LongCat session-create returned HTTP {status}")
    try:
        body = json.loads(str(result.get("body") or ""))
    except json.JSONDecodeError as error:
        raise RuntimeError("LongCat session-create returned invalid JSON") from error
    if not isinstance(body, dict) or int(body.get("code") or -1) != 0:
        raise RuntimeError("LongCat session-create was rejected")
    value = str((body.get("data") or {}).get("conversationId") or "").strip()
    if not value:
        raise RuntimeError("LongCat session-create returned no conversationId")
    return value


def _prompt(messages: Any) -> str:
    if not isinstance(messages, list):
        raise TypeError("LongCat messages must be an array")
    blocks: list[str] = []
    for message in messages:
        if not isinstance(message, dict):
            continue
        role = str(message.get("role") or "user").upper()
        content = _text(message.get("content"))
        if role == "ASSISTANT" and isinstance(message.get("tool_calls"), list):
            content += "\n" + json.dumps(message["tool_calls"], ensure_ascii=True)
        if content.strip():
            blocks.append(f"[{role}]\n{content}")
    if not blocks:
        raise ValueError("LongCat prompt is empty")
    return "\n\n".join(blocks)


def _append_tool_contract(
    prompt: str,
    tools: list[dict[str, Any]],
    choice: Any,
    parallel: Any,
) -> str:
    if choice == "none":
        return prompt
    label = "required" if choice in {"required", "any"} else "auto"
    if isinstance(choice, dict):
        function = choice.get("function") if isinstance(choice.get("function"), dict) else choice
        label = str(function.get("name") or "").strip() or "required"
    definitions = [
        {
            "name": tool["name"],
            "description": tool.get("description", ""),
            "parameters": tool.get("parameters") or {"type": "object", "properties": {}},
        }
        for tool in tools
    ]
    contract = (
        "[Tool calling contract]\n"
        f"Available tools: {json.dumps(definitions, ensure_ascii=True, separators=(',', ':'))}\n"
        f"Tool choice: {label}. Parallel calls allowed: {parallel is not False}.\n"
        "When a tool is needed, output only this JSON object and no prose:\n"
        '{"tool_calls":[{"name":"tool_name","arguments":{}}]}\n'
        "When no tool is needed, answer normally without a tool_calls object."
    )
    return f"{prompt.strip()}\n\n{contract}" if prompt.strip() else contract


def _normalize_tools(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    output: list[dict[str, Any]] = []
    names: set[str] = set()
    for raw in value:
        if not isinstance(raw, dict):
            continue
        definition = raw.get("function") if isinstance(raw.get("function"), dict) else raw
        name = str(definition.get("name") or "").strip()
        if not name or name in names:
            if name:
                raise ValueError(f"duplicate LongCat function tool: {name}")
            raise ValueError("LongCat function tool name is invalid")
        if definition.get("strict") is True:
            raise ValueError("LongCat emulated tools do not support strict=true")
        names.add(name)
        output.append(
            {
                "name": name,
                "description": str(definition.get("description") or ""),
                "parameters": definition.get("parameters")
                or {"type": "object", "properties": {}},
            }
        )
    return output


def _reasoning(command: dict[str, Any], fallback: bool) -> bool:
    value = (
        command["providerOptions"].get("reasoning_effort")
        or command["reasoning"].get("effort")
        or (command.get("rawRequest") or {}).get("reasoning_effort")
    )
    return fallback if value in {None, ""} else str(value).lower() not in {"none", "minimal"}


def _text(value: Any) -> str:
    if value is None:
        return ""
    return text_content(value, "LongCat")


def _string(value: Any, fallback: str) -> str:
    normalized = str(value or "").strip()
    return normalized or fallback


def _bool(value: Any, fallback: Any) -> bool:
    return value if isinstance(value, bool) else bool(fallback)


def _validate_command(command: dict[str, Any]) -> None:
    if not isinstance(command, dict) or command.get("schemaVersion") != 1:
        raise ValueError("LongCat semantic command schema is unsupported")
    if not str(command.get("model") or "").strip():
        raise ValueError("LongCat semantic command requires a model")
    if not isinstance(command.get("messages"), list):
        raise TypeError("LongCat semantic command messages must be an array")
    for field in ("reasoning", "providerOptions", "controls"):
        if not isinstance(command.get(field), dict):
            raise TypeError(f"LongCat semantic command {field} must be an object")
