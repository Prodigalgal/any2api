from __future__ import annotations

import asyncio
import json
import logging
import re
from collections.abc import AsyncIterator
from typing import Any
from uuid import uuid4

from ..config import settings as core_settings
from .base import reject_raw_request
from .multimodal import text_content, xai_input_content
from .official_browser import OfficialBrowserRuntime, OfficialBrowserSession
from .runtime_rules import (
    RuntimePlan,
    RuntimeRuleDiscoveryError,
    RuntimeRuleSelection,
    runtime_canary,
    successful_canary,
)
from .sso_channel import session_cookies

logger = logging.getLogger("any2api_automation.providers.grok_console_browser")

_COOKIE_NAME = re.compile(r"^[!#$%&'*+\-.^_`|~0-9A-Za-z]{1,128}$")
_REASONING_MODELS = frozenset(
    {"grok-4.3", "grok-4.20-0309-reasoning", "grok-4.20-multi-agent-0309"}
)
_REASONING_DEFAULT_EFFORTS = {
    "grok-4.3": "medium",
    "grok-4.20-multi-agent-0309": "medium",
}


_BUFFERED_REQUEST = r"""async request => {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), request.timeoutMs);
  try {
    const response = await fetch(request.url, {
      method: 'POST',
      credentials: 'include',
      headers: {
        'Accept': 'text/event-stream',
        'Content-Type': 'application/json',
        'Authorization': 'Bearer anonymous',
        'x-cluster': request.cluster
      },
      body: JSON.stringify(request.body),
      signal: controller.signal
    });
    const body = await response.text();
    if (body.length > request.maximumBytes) {
      throw new Error('Grok Console browser response exceeds the buffered byte limit');
    }
    return {status: response.status, body};
  } finally {
    clearTimeout(timeout);
  }
}"""


_STREAM_REQUEST = r"""async request => {
  const emit = event => window.__any2apiGrokConsoleEmit({
    requestId: request.requestId,
    ...event
  });
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), request.timeoutMs);
  try {
    const response = await fetch(request.url, {
      method: 'POST',
      credentials: 'include',
      headers: {
        'Accept': 'text/event-stream',
        'Content-Type': 'application/json',
        'Authorization': 'Bearer anonymous',
        'x-cluster': request.cluster
      },
      body: JSON.stringify(request.body),
      signal: controller.signal
    });
    await emit({type: 'status', status: response.status});
    if (!response.ok) {
      await emit({type: 'error', data: (await response.text()).slice(0, 16384)});
      return;
    }
    const reader = response.body?.getReader();
    if (!reader) throw new Error('Grok Console response has no stream body');
    const decoder = new TextDecoder();
    let pending = '';
    const consume = async text => {
      pending += text;
      const frames = pending.split(/\r?\n\r?\n/);
      pending = frames.pop() || '';
      for (const frame of frames) {
        const data = frame.split(/\r?\n/)
          .filter(line => line.startsWith('data:'))
          .map(line => line.slice(5).trimStart())
          .join('\n');
        if (data) await emit({type: 'data', data});
      }
    };
    while (true) {
      const {done, value} = await reader.read();
      if (done) break;
      await consume(decoder.decode(value, {stream: true}));
    }
    await consume(decoder.decode());
    if (pending.trim()) await consume('\n\n');
  } finally {
    clearTimeout(timeout);
  }
}"""


class GrokConsoleOfficialBrowserTransport(OfficialBrowserRuntime):
    """Runs Grok Console traffic inside the account's isolated Camoufox page."""

    def __init__(self, base_url: str) -> None:
        super().__init__(
            "grok_console",
            base_url,
            allowed_domain_suffixes=("x.ai", "grok.com"),
            identity_fields=("email", "sso", "sso-rw", "sso_rw"),
        )
        self._stream_queues: dict[str, asyncio.Queue[dict[str, Any]]] = {}
        self._logger = logging.getLogger("any2api_automation.providers.grok_console_browser")

    async def request(
        self,
        credential: dict[str, Any],
        proxy_url: str,
        plan: RuntimePlan,
        body: dict[str, Any],
        cluster: str,
    ) -> dict[str, Any]:
        async with self.account_operation(credential):
            session, selection, reports = await self._select_session(credential, proxy_url, plan)
            response = await session.page.evaluate(
                _BUFFERED_REQUEST,
                {
                    "url": self._endpoint(selection),
                    "body": body,
                    "cluster": cluster,
                    "timeoutMs": selection.rules.canary_timeout_seconds * 1000,
                    "maximumBytes": core_settings().browser_transport_max_buffered_bytes,
                },
            )
            if not isinstance(response, dict):
                raise TypeError("Grok Console official browser returned an invalid response")
            patch = await self.credential_patch(session, credential)
            return {
                "status": int(response.get("status") or 502),
                "body": str(response.get("body") or ""),
                "credential_patch": patch,
                "transport_mode": "camoufox_browser_runtime",
                "runtime_reports": reports,
            }

    async def stream(
        self,
        credential: dict[str, Any],
        semantic_command: dict[str, Any],
        proxy_url: str,
        plan: RuntimePlan,
        cluster: str,
    ) -> AsyncIterator[dict[str, Any]]:
        body = build_grok_console_request(semantic_command)
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
                            "url": self._endpoint(selection),
                            "body": body,
                            "cluster": cluster,
                            "timeoutMs": selection.rules.canary_timeout_seconds * 1000,
                        },
                    )
                except Exception as error:  # noqa: BLE001 - normalized stream boundary
                    self._logger.warning(
                        "grok_console_official_browser_stream_failed error_type=%s",
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
                    success_report = successful_canary(plan, selection, session.build_id)
                    if success_report is not None:
                        yield {"type": "runtime_canary", **success_report}
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

    async def configure_context(
        self,
        context: Any,
        credential: dict[str, Any],
    ) -> None:
        values = _cookie_values(credential)
        cookies = [
            {
                "name": name,
                "value": value,
                "domain": domain,
                "path": "/",
                "secure": True,
                "sameSite": "Lax",
            }
            for name, value in values.items()
            for domain in (".x.ai", ".grok.com")
        ]
        if not cookies:
            raise ValueError("Grok Console browser requires an SSO cookie")
        await context.add_cookies(cookies)

    async def configure_page(
        self,
        session: OfficialBrowserSession,
        credential: dict[str, Any],
    ) -> None:
        del credential
        await session.page.expose_binding(
            "__any2apiGrokConsoleEmit",
            lambda _source, event: self._emit(event),
        )

    async def wait_until_ready(self, page: Any, rule: Any) -> None:
        del page, rule

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

    def _endpoint(self, selection: RuntimeRuleSelection) -> str:
        path = selection.rules.endpoint_paths.get("chat", "/v1/responses")
        if not path.startswith("/") or "://" in path:
            raise ValueError("Grok Console runtime endpoint must be same-origin")
        return f"{self.base_url}{path}"

    def _emit(self, event: Any) -> None:
        if not isinstance(event, dict):
            return
        queue = self._stream_queues.get(str(event.get("requestId") or ""))
        if queue is None:
            return
        queue.put_nowait({key: value for key, value in event.items() if key != "requestId"})


def build_grok_console_request(command: dict[str, Any]) -> dict[str, Any]:
    _validate_semantic_command(command)
    payload = {"input": _input(command.get("messages"))}
    _copy_generation(payload, command)
    controls = command["controls"]
    if isinstance(controls.get("include"), list):
        payload["include"] = _copy_object(controls["include"])
    payload["model"] = str(command["model"])
    payload["stream"] = True
    payload["store"] = False
    for field in (
        "metadata",
        "previous_response_id",
        "service_tier",
        "prompt_cache_key",
        "background",
        "conversation",
        "provider_options",
    ):
        payload.pop(field, None)
    _normalize_limits(payload)
    _normalize_reasoning(payload, command)
    payload.setdefault("include", ["reasoning.encrypted_content"])
    payload["tools"] = _tools(command.get("tools"))
    return payload


def _validate_semantic_command(command: dict[str, Any]) -> None:
    reject_raw_request(command, "Grok Console")
    if not isinstance(command, dict) or command.get("schemaVersion") != 1:
        raise ValueError("Grok Console semantic command schema is unsupported")
    if not str(command.get("model") or "").strip():
        raise ValueError("Grok Console semantic command requires a model")
    if not isinstance(command.get("messages"), list):
        raise TypeError("Grok Console semantic command messages must be an array")
    for field in ("generation", "reasoning", "providerOptions", "controls"):
        if not isinstance(command.get(field), dict):
            raise TypeError(f"Grok Console semantic command {field} must be an object")


def _copy_object(value: dict[str, Any]) -> dict[str, Any]:
    return json.loads(json.dumps(value, ensure_ascii=True))


def _copy_generation(payload: dict[str, Any], command: dict[str, Any]) -> None:
    generation = command["generation"]
    controls = command["controls"]
    for field in (
        "temperature",
        "top_p",
        "max_tokens",
        "max_completion_tokens",
        "max_output_tokens",
        "parallel_tool_calls",
        "tool_choice",
    ):
        if field in generation:
            payload[field] = (
                _copy_object(generation[field])
                if isinstance(generation[field], (dict, list))
                else generation[field]
            )
        elif field in controls:
            payload[field] = (
                _copy_object(controls[field])
                if isinstance(controls[field], (dict, list))
                else controls[field]
            )
    if command["reasoning"]:
        payload["reasoning"] = _copy_object(command["reasoning"])


def _normalize_limits(payload: dict[str, Any]) -> None:
    if "max_output_tokens" not in payload:
        requested = payload.get("max_tokens", 1_000_000)
        try:
            requested = int(requested)
        except (TypeError, ValueError):
            requested = 1_000_000
        payload["max_output_tokens"] = min(max(1, requested), 1_000_000)
    payload.pop("max_tokens", None)
    payload.pop("max_completion_tokens", None)


def _normalize_reasoning(payload: dict[str, Any], command: dict[str, Any]) -> None:
    model = str(command.get("model") or "")
    if model not in _REASONING_MODELS:
        payload.pop("reasoning", None)
        return
    value = payload.get("reasoning")
    reasoning = _copy_object(value) if isinstance(value, dict) else {}
    if "effort" not in reasoning:
        controls = command["controls"]
        provider_options = command["providerOptions"]
        effort = (
            provider_options.get("reasoning_effort")
            or controls.get("reasoning_effort")
            or (
                command["reasoning"].get("effort")
                if isinstance(command["reasoning"], dict)
                else None
            )
        )
        if effort:
            reasoning["effort"] = str(effort)
        elif model in _REASONING_DEFAULT_EFFORTS:
            reasoning["effort"] = _REASONING_DEFAULT_EFFORTS[model]
    payload["reasoning"] = reasoning


def _input(messages: Any) -> list[dict[str, Any]]:
    if not isinstance(messages, list):
        raise TypeError("Grok Console messages must be an array")
    output: list[dict[str, Any]] = []
    for message in messages:
        if not isinstance(message, dict):
            continue
        role = str(message.get("role") or "user").lower()
        if role == "tool":
            output.append(
                {
                    "type": "function_call_output",
                    "call_id": str(message.get("tool_call_id") or ""),
                    "output": _text(message.get("content")),
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
                        "call_id": str(call.get("id") or ""),
                        "name": str(function.get("name") or ""),
                        "arguments": str(function.get("arguments") or "{}"),
                    }
                )
        normalized_role = "developer" if role == "system" else role
        content = xai_input_content(
            message.get("content", ""), "Grok Console", role=normalized_role
        )
        if content and not all(
            block.get("type") in {"input_text", "output_text"}
            and not str(block.get("text") or "").strip()
            for block in content
        ):
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
        if "description" in source:
            tool["description"] = source["description"]
        tool["parameters"] = source.get("parameters") or {"type": "object"}
        output.append(tool)
    return output


def _text(value: Any) -> str:
    if value is None:
        return ""
    return text_content(value, "Grok Console")


def _cookie_values(credential: dict[str, Any]) -> dict[str, str]:
    values = dict(session_cookies(credential))
    for field in ("cookies", "cookie", "cloudflare_cookies", "cf_cookies"):
        source = credential.get(field)
        if isinstance(source, dict):
            pairs = source.items()
        elif isinstance(source, str):
            pairs = re.findall(r"(?:^|;)\s*([^=;\s]+)=([^;]*)", source)
        else:
            continue
        for name, value in pairs:
            normalized_name = str(name).strip()
            normalized_value = str(value).strip()
            if _COOKIE_NAME.fullmatch(normalized_name) and normalized_value:
                values[normalized_name] = normalized_value
    return values
