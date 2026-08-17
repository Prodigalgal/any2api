from __future__ import annotations

import asyncio
import base64
import json
import logging
from collections.abc import AsyncIterator
from typing import Any
from uuid import uuid4

from ..config import settings as core_settings
from .official_browser import OfficialBrowserRuntime, OfficialBrowserSession
from .runtime_rules import (
    RuntimePlan,
    RuntimeRule,
    RuntimeRuleDiscoveryError,
    RuntimeRuleSelection,
    runtime_canary,
    successful_canary,
)

logger = logging.getLogger("any2api_automation.providers.mimo_browser")


def _locate_bridge(rule: RuntimeRule) -> str:
    markers = json.dumps(rule.discovery_markers.get("requestModule", ()))
    chat_capability = json.dumps(rule.capabilities.get("chat", ""))
    models_capability = json.dumps(rule.capabilities.get("models", ""))
    template = r"""() => {
  const markers = __MARKERS__;
  const chatCapability = __CHAT_CAPABILITY__;
  const modelsCapability = __MODELS_CAPABILITY__;
  const cached = window.__any2apiMimoOfficialBridge;
  if (typeof cached?.chat?.[chatCapability] === 'function' &&
      typeof cached?.config?.[modelsCapability] === 'function') return cached;
  const chunkNames = Object.getOwnPropertyNames(window)
    .filter(name => name.startsWith('rspackChunk'));
  for (const chunkName of chunkNames) {
    const chunks = window[chunkName];
    if (!Array.isArray(chunks)) continue;
    let runtime;
    chunks.push([['any2api-' + Date.now()], {}, require => { runtime = require; }]);
    if (!runtime?.m) continue;
    for (const [id, factory] of Object.entries(runtime.m)) {
      const source = String(factory);
      if (!markers.length || !markers.every(marker => source.includes(marker))) {
        continue;
      }
      let exports;
      try { exports = runtime(id); } catch (_) { continue; }
      try {
        const values = Object.values(exports || {});
        const chat = values.find(value => value && typeof value === 'object' &&
          typeof value[chatCapability] === 'function');
        const config = values.find(value => value && typeof value === 'object' &&
          typeof value[modelsCapability] === 'function');
        if (chat && config) {
          window.__any2apiMimoOfficialBridge = {chat, config, diagnosticModuleId: id};
          return window.__any2apiMimoOfficialBridge;
        }
      } catch (_) { continue; }
    }
  }
  throw new Error('MiMo official request bridge was not found');
}"""
    return (
        template.replace("__MARKERS__", markers)
        .replace("__CHAT_CAPABILITY__", chat_capability)
        .replace("__MODELS_CAPABILITY__", models_capability)
    )


def _config_request(rule: RuntimeRule) -> str:
    locate = _locate_bridge(rule)
    capability = json.dumps(rule.capabilities.get("models", ""))
    return rf"""async request => {{
  const locate = {locate};
  const bridge = locate();
  const result = await bridge.config[{capability}]();
  const data = Array.isArray(result) ? result[0] : result;
  const error = Array.isArray(result) ? result[1] : null;
  if (error) throw error;
  const encoded = new TextEncoder().encode(JSON.stringify({{code: 0, data}}));
  if (encoded.length > request.maximumBytes) {{
    throw new Error('MiMo browser response exceeds the buffered byte limit');
  }}
  let binary = '';
  for (let index = 0; index < encoded.length; index += 32768) {{
    binary += String.fromCharCode(...encoded.subarray(index, index + 32768));
  }}
  return {{status: 200, bodyBase64: btoa(binary), moduleLocated: true}};
}}"""


def _stream_request(rule: RuntimeRule) -> str:
    locate = _locate_bridge(rule)
    capability = json.dumps(rule.capabilities.get("chat", ""))
    return rf"""async request => {{
  const locate = {locate};
  const bridge = locate();
  const emit = event => window.__any2apiMimoEmit({{requestId: request.requestId, ...event}});
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), request.timeoutMs);
  try {{
    const result = await bridge.chat[{capability}](request.body, controller);
    const response = Array.isArray(result) ? result[0] : result;
    const error = Array.isArray(result) ? result[1] : null;
    if (error) throw error;
    if (!(response instanceof Response)) {{
      throw new Error('MiMo official completion did not return a Response');
    }}
    await emit({{type: 'status', status: response.status}});
    if (!response.ok) {{
      await emit({{type: 'error', data: (await response.text()).slice(0, 16384)}});
      return;
    }}
    const reader = response.body?.getReader();
    if (!reader) throw new Error('MiMo official response has no stream body');
    const decoder = new TextDecoder();
    let pending = '';
    while (true) {{
      const {{done, value}} = await reader.read();
      if (done) break;
      pending += decoder.decode(value, {{stream: true}});
      const frames = pending.split(/\r?\n\r?\n/);
      pending = frames.pop() || '';
      for (const frame of frames) {{
        for (const line of frame.split(/\r?\n/)) {{
          if (line.startsWith('data:') && line.slice(5).trim()) {{
            await emit({{type: 'data', data: line.slice(5).trim()}});
          }}
        }}
      }}
    }}
    pending += decoder.decode();
    for (const line of pending.split(/\r?\n/)) {{
      if (line.startsWith('data:') && line.slice(5).trim()) {{
        await emit({{type: 'data', data: line.slice(5).trim()}});
      }}
    }}
  }} finally {{
    clearTimeout(timeout);
  }}
}}"""


class MimoOfficialBrowserTransport(OfficialBrowserRuntime):
    def __init__(self, base_url: str) -> None:
        super().__init__(
            "mimo",
            base_url,
            allowed_domain_suffixes=("xiaomimimo.com",),
            identity_fields=("user_id", "email"),
        )
        self._active_stream_id = ""
        self._active_stream_queue: asyncio.Queue[dict[str, Any]] | None = None

    async def request(
        self,
        credential: dict[str, Any],
        proxy_url: str,
        plan: RuntimePlan | None = None,
    ) -> dict[str, Any]:
        async with self.lock:
            session, selection, reports = await self._select_session(
                credential, proxy_url, plan or default_runtime_plan()
            )
            result = await session.page.evaluate(
                _config_request(selection.rules),
                {"maximumBytes": core_settings().browser_transport_max_buffered_bytes},
            )
            if not isinstance(result, dict):
                raise TypeError("MiMo official browser returned an invalid response")
            try:
                body = base64.b64decode(
                    str(result.get("bodyBase64") or ""),
                    validate=True,
                ).decode("utf-8", errors="replace")
            except ValueError as error:
                raise RuntimeError(
                    "MiMo official browser returned invalid body encoding"
                ) from error
            return {
                "status": int(result.get("status") or 502),
                "body": body,
                "credential_patch": await self.credential_patch(session, credential),
                "transport_mode": "official_browser_runtime",
                "runtime_reports": reports,
            }

    async def stream(
        self,
        credential: dict[str, Any],
        semantic_command: dict[str, Any],
        proxy_url: str,
        plan: RuntimePlan,
    ) -> AsyncIterator[dict[str, Any]]:
        body = build_mimo_chat_request(semantic_command)
        async with self.lock:
            session, selection, reports = await self._select_session(credential, proxy_url, plan)
            for report in reports:
                yield {"type": "runtime_canary", **report}
            request_id = uuid4().hex
            queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue()
            self._active_stream_id = request_id
            self._active_stream_queue = queue

            async def execute() -> None:
                try:
                    await session.page.evaluate(
                        _stream_request(selection.rules),
                        {
                            "requestId": request_id,
                            "body": body,
                            "timeoutMs": core_settings().registration_timeout_seconds * 1000,
                        },
                    )
                except Exception as error:  # noqa: BLE001 - normalized stream boundary
                    logger.warning(
                        "mimo_official_browser_stream_failed error_type=%s",
                        type(error).__name__,
                    )
                    await queue.put(
                        {
                            "type": "error",
                            "data": (f"official browser stream failed ({type(error).__name__})"),
                        }
                    )
                finally:
                    await queue.put({"type": "done"})

            task = asyncio.create_task(execute())
            pending_error: dict[str, Any] | None = None
            terminal_data: dict[str, Any] | None = None
            status = -1
            data_seen = False
            try:
                while True:
                    event = await queue.get()
                    if event.get("type") == "done":
                        break
                    if event.get("type") == "error":
                        pending_error = event
                        continue
                    if event.get("type") == "status":
                        status = int(event.get("status") or 502)
                    if (
                        event.get("type") == "data"
                        and str(event.get("data") or "").strip() == "[DONE]"
                    ):
                        terminal_data = event
                        continue
                    if event.get("type") == "data" and str(event.get("data") or ""):
                        data_seen = True
                    yield event
                if pending_error is None and data_seen and 200 <= status < 300:
                    success_report = successful_canary(plan, selection, session.build_id)
                    if success_report is not None:
                        yield {"type": "runtime_canary", **success_report}
                if terminal_data is not None:
                    yield terminal_data
                patch = await self.credential_patch(session, credential)
                if patch:
                    yield {"type": "credential_patch", "data": patch}
                if pending_error is not None:
                    yield pending_error
            finally:
                self._active_stream_id = ""
                self._active_stream_queue = None
                if not task.done():
                    task.cancel()
                await asyncio.gather(task, return_exceptions=True)

    async def configure_context(
        self,
        context: Any,
        credential: dict[str, Any],
    ) -> None:
        cookies = []
        for name, field in (
            ("serviceToken", "service_token"),
            ("userId", "user_id"),
            ("xiaomichatbot_ph", "xiaomichatbot_ph"),
        ):
            value = str(credential.get(field) or "").strip()
            if value:
                cookies.append(
                    {
                        "name": name,
                        "value": value,
                        "domain": ".xiaomimimo.com",
                        "path": "/",
                        "secure": True,
                        "sameSite": "Lax",
                    }
                )
        if len(cookies) != 3:
            raise ValueError("MiMo official browser requires all service cookies")
        await context.add_cookies(cookies)

    async def configure_page(
        self,
        session: OfficialBrowserSession,
        credential: dict[str, Any],
    ) -> None:
        del credential
        await session.page.expose_binding(
            "__any2apiMimoEmit",
            lambda _source, event: self._emit(event),
        )

    async def wait_until_ready(self, page: Any, rule: RuntimeRule) -> None:
        await page.wait_for_function(
            """() => Object.getOwnPropertyNames(window)
              .some(name => name.startsWith('rspackChunk'))""",
            timeout=rule.canary_timeout_seconds * 1000,
        )
        deadline = asyncio.get_running_loop().time() + rule.canary_timeout_seconds
        last_error: Exception | None = None
        while asyncio.get_running_loop().time() < deadline:
            try:
                await page.evaluate(_locate_bridge(rule))
                return
            except Exception as error:
                if "official request bridge was not found" not in str(error):
                    raise
                last_error = error
                await page.wait_for_timeout(1_000)
        raise RuntimeError("MiMo official request bridge did not load in time") from last_error

    async def _select_session(
        self,
        credential: dict[str, Any],
        proxy_url: str,
        plan: RuntimePlan,
    ) -> tuple[OfficialBrowserSession, RuntimeRuleSelection, list[dict[str, Any]]]:
        reports: list[dict[str, Any]] = []
        if plan.candidate is not None:
            try:
                session = await self.session_for(credential, proxy_url, plan.candidate)
                return session, plan.candidate, reports
            except RuntimeRuleDiscoveryError as error:
                reports.append(runtime_canary(plan.candidate, "", "FAILED", str(error)))
        session = await self.session_for(credential, proxy_url, plan.active)
        return session, plan.active, reports

    def _emit(self, event: Any) -> None:
        if not isinstance(event, dict) or event.get("requestId") != self._active_stream_id:
            return
        queue = self._active_stream_queue
        if queue is None:
            return
        queue.put_nowait({key: value for key, value in event.items() if key != "requestId"})


def build_mimo_chat_request(command: dict[str, Any]) -> dict[str, Any]:
    _validate_semantic_command(command)
    controls = command.get("controls") or {}
    generation = command.get("generation") or {}
    reasoning = command.get("reasoning") or {}
    provider_options = command.get("providerOptions") or {}
    tools = list(command.get("tools") or [])
    choice = controls.get("tool_choice", "auto")
    required = choice in {"required", "any"}
    if choice == "none":
        tools = []
    elif isinstance(choice, dict):
        name = str((choice.get("function") or {}).get("name") or choice.get("name") or "")
        tools = [tool for tool in tools if _tool_name(tool) == name]
        required = True
    parallel = controls.get("parallel_tool_calls", True)
    blocks: list[str] = []
    system: list[str] = []
    conversation: list[str] = []
    for message in command["messages"]:
        role = str(message.get("role") or "user").lower()
        content = _message_content(message.get("content"))
        if role in {"system", "developer"}:
            if content:
                system.append(content)
        elif role == "tool":
            conversation.append(f"[TOOL {message.get('tool_call_id', '')}]\n{content}")
        elif role == "assistant" and isinstance(message.get("tool_calls"), list):
            calls = []
            for call in message["tool_calls"]:
                function = call.get("function") if isinstance(call, dict) else {}
                function = function if isinstance(function, dict) else {}
                calls.append(
                    f"TOOL_CALL: {function.get('name', '')}({function.get('arguments', '{}')})"
                )
            conversation.append("[ASSISTANT]\n" + "\n".join(calls))
        else:
            conversation.append(f"[{role.upper()}]\n{content}")
    if system:
        blocks.append("\n\n".join(system))
    if tools:
        blocks.append(_tool_prompt(tools, bool(parallel), required))
    if conversation:
        blocks.append("\n\n".join(conversation))
    effort = str(reasoning.get("effort") or controls.get("reasoning_effort") or "").lower()
    thinking = provider_options.get("thinking", controls.get("thinking"))
    if not isinstance(thinking, bool):
        thinking = bool(effort and effort not in {"none", "minimal"})
    uploaded_media = command.get("uploadedMedia") or []
    if not isinstance(uploaded_media, list):
        raise TypeError("MiMo uploadedMedia must be an array")
    return {
        "msgId": uuid4().hex,
        "conversationId": str(provider_options.get("conversation_id") or uuid4().hex),
        "query": "\n\n".join(blocks),
        "modelConfig": {
            "enableThinking": thinking,
            "temperature": _number(generation.get("temperature"), 0.8),
            "topP": _number(generation.get("top_p"), 0.95),
            "webSearchStatus": str(
                provider_options.get("web_search_status")
                or controls.get("web_search_status")
                or "disabled"
            ),
            "model": command["model"],
        },
        "multiMedias": uploaded_media,
        "attachments": [],
    }


def official_bridge_script(rule: RuntimeRule | None = None) -> str:
    return _locate_bridge(rule or default_runtime_plan().active.rules)


def default_runtime_plan() -> RuntimePlan:
    rule = RuntimeRule(
        schema_version=1,
        session_max_age_seconds=900,
        canary_timeout_seconds=60,
        build_asset_markers=("xiaomimimo.com",),
        discovery_markers={"requestModule": ("/open-apis/bot/chat", "genUploadInfo")},
        capabilities={"chat": "completions", "models": "getConfig"},
        endpoint_paths={
            "chat": "/open-apis/bot/chat",
            "models": "/open-apis/bot/config",
        },
    )
    return RuntimePlan(RuntimeRuleSelection("mimo", 1, rule), None, "", "")


def _validate_semantic_command(command: dict[str, Any]) -> None:
    if not isinstance(command, dict) or command.get("schemaVersion") != 1:
        raise ValueError("MiMo semantic command schema is unsupported")
    if not str(command.get("model") or "").strip():
        raise ValueError("MiMo semantic command requires a model")
    if not isinstance(command.get("messages"), list):
        raise TypeError("MiMo semantic command messages must be an array")
    if not isinstance(command.get("tools"), list):
        raise TypeError("MiMo semantic command tools must be an array")
    for name in ("generation", "reasoning", "providerOptions", "controls"):
        if not isinstance(command.get(name), dict):
            raise TypeError(f"MiMo semantic command {name} must be an object")


def _message_content(value: Any) -> str:
    if isinstance(value, str):
        return value
    if not isinstance(value, list):
        return ""
    parts: list[str] = []
    for part in value:
        if isinstance(part, str):
            parts.append(part)
        elif isinstance(part, dict) and part.get("type") in {
            "text",
            "input_text",
            "output_text",
        }:
            parts.append(str(part.get("text") or ""))
    return "\n".join(parts)


def _tool_name(tool: Any) -> str:
    if not isinstance(tool, dict):
        return ""
    function = tool.get("function") if isinstance(tool.get("function"), dict) else tool
    return str(function.get("name") or "")


def _tool_prompt(tools: list[Any], parallel: bool, required: bool) -> str:
    definitions = []
    for tool in tools:
        if not isinstance(tool, dict):
            raise TypeError("MiMo tool definitions must be objects")
        function = tool.get("function") if isinstance(tool.get("function"), dict) else tool
        definitions.append(
            {
                "name": str(function.get("name") or ""),
                "description": str(function.get("description") or ""),
                "parameters": function.get("parameters") or {"type": "object"},
            }
        )
    requirement = "You must call at least one declared function.\n" if required else ""
    return (
        "You can call the functions below. Their JSON Schemas are authoritative.\n<tools>"
        + json.dumps(definitions, ensure_ascii=True, separators=(",", ":"))
        + "</tools>\n"
        + f"Parallel calls allowed: {str(parallel).lower()}.\n"
        + requirement
        + "When calling functions, output only this exact structure and no prose:\n"
        + "<|MiMoML|tool_calls>\n"
        + '<|MiMoML|invoke name="FUNCTION_NAME">\n'
        + '<|MiMoML|parameter name="PARAMETER_NAME">JSON_VALUE'
        + "</|MiMoML|parameter>\n</|MiMoML|invoke>\n</|MiMoML|tool_calls>"
    )


def _number(value: Any, fallback: float) -> float:
    return (
        float(value)
        if isinstance(value, (int, float)) and not isinstance(value, bool)
        else fallback
    )
