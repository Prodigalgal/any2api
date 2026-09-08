from __future__ import annotations

import asyncio
import json
import logging
import re
from collections.abc import AsyncIterator
from http.cookies import CookieError, SimpleCookie
from typing import Any
from uuid import uuid4

from ..config import settings as core_settings
from .multimodal import text_content
from .official_browser import OfficialBrowserRuntime, OfficialBrowserSession
from .runtime_rules import RuntimePlan, RuntimeRuleDiscoveryError, runtime_canary, successful_canary

logger = logging.getLogger("any2api_automation.providers.grok_web_browser")

_COOKIE_NAME = re.compile(r"^[!#$%&'*+\-.^_`|~0-9A-Za-z]{1,128}$")
_MODEL_MODES = {
    "grok-chat-fast": "fast",
    "grok-chat-auto": "auto",
    "grok-chat-expert": "expert",
    "grok-chat-heavy": "heavy",
}
_MODEL_IDS = (
    *tuple(_MODEL_MODES),
    "grok-imagine-image",
    "grok-imagine-image-quality",
    "grok-imagine-image-edit",
    "grok-imagine-video",
)

_SESSION_REQUEST = r"""async request => {
  const response = await fetch('/api/auth/session', {
    credentials: 'include',
    cache: 'no-store'
  });
  return {
    status: response.status,
    body: await response.text(),
    contentType: response.headers.get('content-type') || ''
  };
}"""

_STREAM_REQUEST = r"""async request => {
  const emit = event => window.__any2apiGrokWebEmit({requestId: request.requestId, ...event});
  let socket;
  let finished = false;
  let promptSent = false;
  const timeout = setTimeout(() => {
    if (!finished) {
      finished = true;
      try { socket?.close(); } catch (_) {}
      emit({type: 'error', data: 'Grok Web gateway timed out'});
    }
  }, request.timeoutMs);
  const done = () => {
    if (finished) return;
    finished = true;
    clearTimeout(timeout);
    try { socket?.close(); } catch (_) {}
  };
  const fail = async (status, detail) => {
    await emit({type: 'status', status});
    await emit({type: 'error', data: String(detail || 'Grok Web gateway failed').slice(0, 16384)});
    done();
  };
  try {
    const sessionResponse = await fetch('/api/auth/session', {
      credentials: 'include',
      cache: 'no-store'
    });
    const sessionText = await sessionResponse.text();
    let session;
    try { session = JSON.parse(sessionText); } catch (_) { session = {}; }
    const userId = String(session?.session?.userId || '').trim();
    if (!sessionResponse.ok || !userId) {
      await fail(sessionResponse.ok ? 401 : sessionResponse.status,
        sessionText || 'Grok Web session is not authenticated');
      return;
    }
    const websocketUrl = new URL('/ws/mgw/?uid=' + encodeURIComponent(userId), location.href);
    websocketUrl.protocol = websocketUrl.protocol === 'https:' ? 'wss:' : 'ws:';
    socket = new WebSocket(websocketUrl.toString());
    socket.onopen = () => {
      socket.send(JSON.stringify({event: {
        type: 'session.create',
        event_id: 'evt_session_' + crypto.randomUUID().replaceAll('-', ''),
        session: {
          model: request.mode,
          x_grok: {
            protocol_capabilities: ['conversation_attached', 'custom_methods_v1'],
            use_chunk: true,
            enable_side_by_side: request.enableSideBySide,
            force_side_by_side: request.forceSideBySide,
            enable_image_generation: request.enableImageGeneration,
            image_generation_count: request.imageGenerationCount,
            disable_text_follow_ups: request.disableTextFollowUps,
            disable_artifact: true,
            force_concise: request.forceConcise,
            disable_memory: request.disableMemory,
            keep_context: false,
            is_temporary: true,
            ...(request.conversationId ? {
              conversation_id: request.conversationId,
              load_existing: true,
              needs_history: false
            } : {})
          }
        }
      }}));
    };
    socket.onmessage = async message => {
      if (finished) return;
      const raw = typeof message.data === 'string'
        ? message.data : new TextDecoder().decode(message.data);
      await emit({type: 'data', data: raw});
      let root;
      try { root = JSON.parse(raw); } catch (_) { return; }
      const event = root?.event || {};
      if (event.type === 'error') {
        await emit({type: 'error', data: JSON.stringify(event.error || event)});
        done();
        return;
      }
      if (event.type === 'conversation.attached' && !promptSent) {
        promptSent = true;
        const sessionId = String(root.session_id || '').trim();
        if (!sessionId) {
          await emit({type: 'error', data: 'Grok Web gateway omitted session_id'});
          done();
          return;
        }
        const item = {
          type: 'message',
          role: 'user',
          x_grok: {
            client_message_id: crypto.randomUUID(),
            input_chunks: [{text: {text: request.message}}]
          }
        };
        const itemEvent = {
          type: 'conversation.item.create',
          event_id: 'evt_msg_' + crypto.randomUUID().replaceAll('-', ''),
          item
        };
        if (request.parentResponseId) itemEvent.parent_response_id = request.parentResponseId;
        socket.send(JSON.stringify({session_id: sessionId, event: itemEvent}));
        socket.send(JSON.stringify({session_id: sessionId, event: {
          type: 'response.create',
          event_id: 'evt_resp_' + crypto.randomUUID().replaceAll('-', '')
        }}));
      }
      if (event.type === 'response.done') done();
    };
    socket.onerror = () => { void fail(502, 'Grok Web gateway websocket error'); };
    socket.onclose = () => {
      if (!finished) void fail(502, 'Grok Web gateway closed before response.done');
    };
    await emit({type: 'status', status: 200});
    await new Promise(resolve => {
      const check = () => finished ? resolve() : setTimeout(check, 50);
      check();
    });
  } catch (error) {
    await emit({type: 'error', data: String(error).slice(0, 16384)});
    done();
  } finally {
    clearTimeout(timeout);
    try { socket?.close(); } catch (_) {}
  }
}"""


class GrokWebOfficialBrowserTransport(OfficialBrowserRuntime):
    """Executes Grok Web gateway traffic inside the authenticated page context."""

    def __init__(self, base_url: str) -> None:
        super().__init__(
            "grok_web",
            base_url,
            allowed_domain_suffixes=("grok.com", "x.ai"),
            identity_fields=("sso", "sso-rw", "sso_rw", "email"),
            require_build_assets=False,
            page_url=base_url,
        )
        self._stream_queues: dict[str, asyncio.Queue[dict[str, Any]]] = {}

    async def request(
        self,
        credential: dict[str, Any],
        proxy_url: str,
        plan: RuntimePlan,
        *,
        operation: str = "keepalive",
    ) -> dict[str, Any]:
        if operation not in {"keepalive", "models"}:
            raise ValueError("Grok Web request operation is not allowlisted")
        async with self.account_operation(credential):
            session, _selection, reports = await self._select_session(credential, proxy_url, plan)
            if operation == "models":
                body = json.dumps(
                    {"data": [{"id": model, "object": "model"} for model in _MODEL_IDS]},
                    separators=(",", ":"),
                )
                return {
                    "status": 200,
                    "body": body,
                    "credential_patch": await self.credential_patch(session, credential),
                    "runtime_reports": reports,
                    "transport_mode": "camoufox_browser_runtime",
                }
            result = await session.page.evaluate(_SESSION_REQUEST)
            if not isinstance(result, dict):
                raise TypeError("Grok Web browser returned an invalid session response")
            return {
                "status": int(result.get("status") or 502),
                "body": str(result.get("body") or ""),
                "credential_patch": await self.credential_patch(session, credential),
                "runtime_reports": reports,
                "transport_mode": "camoufox_browser_runtime",
            }

    async def stream(
        self,
        credential: dict[str, Any],
        command: dict[str, Any],
        proxy_url: str,
        plan: RuntimePlan,
    ) -> AsyncIterator[dict[str, Any]]:
        request = build_grok_web_request(command)
        async with self.account_operation(credential):
            session, selection, reports = await self._select_session(credential, proxy_url, plan)
            for report in reports:
                yield {"type": "runtime_canary", **report}
            request_id = uuid4().hex
            queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue()
            self._stream_queues[request_id] = queue

            async def execute() -> None:
                try:
                    await session.page.evaluate(
                        _STREAM_REQUEST,
                        {
                            "requestId": request_id,
                            **request,
                            "timeoutMs": max(
                                30_000,
                                plan.active.rules.canary_timeout_seconds * 1000,
                                core_settings().registration_timeout_seconds * 1000,
                            ),
                        },
                    )
                except Exception as error:  # noqa: BLE001 - stream boundary
                    logger.warning(
                        "grok_web_official_browser_stream_failed error_type=%s",
                        type(error).__name__,
                    )
                    await queue.put(
                        {
                            "type": "error",
                            "data": f"official browser stream failed ({type(error).__name__})",
                        }
                    )
                finally:
                    await queue.put({"type": "done"})

            task = asyncio.create_task(execute())
            pending_error: dict[str, Any] | None = None
            status = -1
            data_seen = False
            try:
                while True:
                    event = await queue.get()
                    event_type = str(event.get("type") or "error")
                    if event_type == "done":
                        break
                    if event_type == "error":
                        pending_error = event
                        continue
                    if event_type == "status":
                        status = int(event.get("status") or 502)
                    if event_type == "data" and str(event.get("data") or ""):
                        data_seen = True
                    yield event
                if pending_error is None and data_seen and 200 <= status < 300:
                    report = successful_canary(plan, selection, session.build_id)
                    if report is not None:
                        yield {"type": "runtime_canary", **report}
                patch = await self.credential_patch(session, credential)
                if patch:
                    yield {"type": "credential_patch", "data": patch}
                if pending_error is not None:
                    yield pending_error
            finally:
                self._stream_queues.pop(request_id, None)
                if not task.done():
                    task.cancel()
                await asyncio.gather(task, return_exceptions=True)

    async def configure_context(self, context: Any, credential: dict[str, Any]) -> None:
        cookies = _credential_cookies(credential)
        if not cookies:
            raise ValueError("Grok Web official browser requires an SSO cookie")
        await context.add_cookies(cookies)

    async def configure_page(
        self, session: OfficialBrowserSession, credential: dict[str, Any]
    ) -> None:
        del credential
        await session.page.expose_binding(
            "__any2apiGrokWebEmit",
            lambda _source, event: self._emit(event),
        )

    async def wait_until_ready(self, page: Any, rule: Any) -> None:
        del page, rule

    async def _select_session(
        self,
        credential: dict[str, Any],
        proxy_url: str,
        plan: RuntimePlan,
    ) -> tuple[OfficialBrowserSession, Any, list[dict[str, Any]]]:
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
        if not isinstance(event, dict):
            return
        queue = self._stream_queues.get(str(event.get("requestId") or ""))
        if queue is None:
            return
        queue.put_nowait({key: value for key, value in event.items() if key != "requestId"})


def build_grok_web_request(command: dict[str, Any]) -> dict[str, Any]:
    _validate_command(command)
    options = command.get("providerOptions") or {}
    controls = command.get("controls") or {}
    mode = str(options.get("mode_id") or _MODEL_MODES.get(str(command["model"]), "fast"))
    tools = _supported_tools(command.get("tools"))
    choice = controls.get("tool_choice")
    if choice == "none":
        tools = []
    message = _prompt(command["messages"])
    if tools:
        message = _tool_prompt(message, tools, choice)
    return {
        "mode": mode,
        "message": message,
        "conversationId": str(command.get("previousConversationId") or "").strip(),
        "parentResponseId": str(command.get("previousUpstreamResponseId") or "").strip(),
        "enableSideBySide": True,
        "forceSideBySide": False,
        "enableImageGeneration": False,
        "imageGenerationCount": 2,
        "disableTextFollowUps": False,
        "forceConcise": False,
        "disableMemory": True,
    }


def _validate_command(command: dict[str, Any]) -> None:
    if not isinstance(command, dict) or command.get("schemaVersion") != 1:
        raise ValueError("Grok Web semantic command schema is unsupported")
    if not str(command.get("model") or "").strip():
        raise ValueError("Grok Web semantic command requires a model")
    if not isinstance(command.get("messages"), list):
        raise TypeError("Grok Web semantic command messages must be an array")
    for field in ("providerOptions", "controls"):
        if not isinstance(command.get(field), dict):
            raise TypeError(f"Grok Web semantic command {field} must be an object")


def _prompt(messages: Any) -> str:
    blocks: list[str] = []
    for message in messages:
        if not isinstance(message, dict):
            continue
        message_type = str(message.get("type") or "").lower()
        if message_type == "function_call":
            blocks.append(_function_call_xml(message))
            continue
        if message_type == "function_call_output":
            blocks.append(
                f"[tool result for {message.get('call_id', '')}]\n{_text(message.get('output'))}"
            )
            continue
        role = str(message.get("role") or "user")
        content = _text(message.get("content"))
        if isinstance(message.get("tool_calls"), list):
            content = (content + "\n" if content else "") + json.dumps(
                message["tool_calls"], ensure_ascii=False, separators=(",", ":")
            )
        if message.get("tool_call_id"):
            role = "tool"
            content = f"Tool result ({message['tool_call_id']}):\n{content}"
        if content.strip():
            blocks.append(f"[{role}]\n{content}")
    if not blocks:
        raise ValueError("Grok Web prompt is empty")
    return "\n\n".join(blocks)


def _supported_tools(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    output: list[dict[str, Any]] = []
    names: set[str] = set()
    for raw in value:
        if not isinstance(raw, dict):
            continue
        if raw.get("type") in {"web_search", "web_search_preview"}:
            continue
        function = raw.get("function") if isinstance(raw.get("function"), dict) else raw
        name = str(function.get("name") or "").strip()
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", name):
            raise ValueError("Grok Web function tool name is invalid")
        if name in names:
            raise ValueError(f"duplicate Grok Web function tool: {name}")
        names.add(name)
        output.append(
            {
                "name": name,
                "description": str(function.get("description") or "").strip(),
                "parameters": function.get("parameters")
                if isinstance(function.get("parameters"), dict)
                else {"type": "object", "properties": {}},
            }
        )
    return output


def _tool_prompt(prompt: str, tools: list[dict[str, Any]], choice: Any) -> str:
    definitions = []
    for tool in tools:
        value = f"Tool: {tool['name']}"
        if tool["description"]:
            value += f"\nDescription: {tool['description']}"
        value += "\nParameters: " + json.dumps(
            tool["parameters"], ensure_ascii=False, separators=(",", ":")
        )
        definitions.append(value)
    forced = ""
    if isinstance(choice, dict):
        function = choice.get("function") if isinstance(choice.get("function"), dict) else choice
        forced = str(function.get("name") or "").strip()
    instruction = (
        f'MUST call the tool named "{forced}" and must not write a plain-text reply.'
        if forced
        else "MUST call at least one available tool and must not write a plain-text reply."
        if choice in {"required", "any"}
        else "Call a tool when it is clearly needed. Otherwise respond in plain text."
    )
    return (
        "[system]\nYou have access to the following tools.\n\nAVAILABLE TOOLS:\n"
        + "\n\n".join(definitions)
        + "\n\nTOOL CALL FORMAT:\n"
        + "<tool_calls>\n  <tool_call>\n    <tool_name>TOOL_NAME</tool_name>\n"
        + '    <parameters>{"key":"value"}</parameters>\n  </tool_call>\n</tool_calls>\n\n'
        + "WHEN TO CALL: "
        + instruction
        + "\n\n"
        + prompt
    )


def _function_call_xml(message: dict[str, Any]) -> str:
    name = str(message.get("name") or "")
    arguments = str(message.get("arguments") or "{}")
    return (
        "<tool_calls>\n  <tool_call>\n    <tool_name>"
        + name
        + "</tool_name>\n    <parameters>"
        + arguments
        + "</parameters>\n  </tool_call>\n</tool_calls>"
    )


def _text(value: Any) -> str:
    if value is None:
        return ""
    return text_content(value, "Grok Web")


def _credential_cookies(credential: dict[str, Any]) -> list[dict[str, Any]]:
    values: dict[str, str] = {}
    for field in ("cookies", "cloudflare_cookies", "cf_cookies"):
        source = credential.get(field)
        if isinstance(source, dict):
            pairs = source.items()
        elif isinstance(source, str):
            parsed = SimpleCookie()
            try:
                parsed.load(source)
            except CookieError:
                continue
            pairs = ((name, morsel.value) for name, morsel in parsed.items())
        else:
            continue
        for name, value in pairs:
            name = str(name).strip()
            value = str(value).strip()
            if _COOKIE_NAME.fullmatch(name) and value and len(value) <= 8192:
                values[name] = value
    for field, cookie_name in (("sso", "sso"), ("sso-rw", "sso-rw"), ("sso_rw", "sso-rw")):
        value = str(credential.get(field) or "").strip()
        if value.startswith(cookie_name + "="):
            value = value.split("=", 1)[1].strip()
        if value:
            values[cookie_name] = value
    return [
        {
            "name": name,
            "value": value,
            "domain": ".grok.com",
            "path": "/",
            "secure": True,
            "sameSite": "Lax",
        }
        for name, value in values.items()
    ]
