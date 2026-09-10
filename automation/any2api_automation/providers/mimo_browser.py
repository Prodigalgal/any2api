from __future__ import annotations

import asyncio
import base64
import json
import logging
from collections.abc import AsyncIterator
from typing import Any
from urllib.parse import quote
from uuid import uuid4

from ..config import settings as core_settings
from .base import reject_raw_request
from .multimodal import (
    decode_inline_data_url,
    iter_media_blocks,
    media_source,
    text_content,
)
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


def _config_request(rule: RuntimeRule) -> str:
    del rule
    return r"""async request => {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), request.timeoutMs);
  try {
    const response = await fetch(request.url, {
      method: 'GET',
      credentials: 'include',
      headers: request.headers,
      signal: controller.signal
    });
    const body = await response.text();
    const encoded = new TextEncoder().encode(body);
    if (encoded.length > request.maximumBytes) {
      throw new Error('MiMo browser response exceeds the buffered byte limit');
    }
    let binary = '';
    for (let index = 0; index < encoded.length; index += 32768) {
      binary += String.fromCharCode(...encoded.subarray(index, index + 32768));
    }
    return {
      status: response.status,
      bodyBase64: btoa(binary),
      contentType: response.headers.get('content-type') || ''
    };
  } finally {
    clearTimeout(timeout);
  }
}"""


def _stream_request(rule: RuntimeRule) -> str:
    del rule
    return r"""async request => {
  const emit = event => window.__any2apiMimoEmit({requestId: request.requestId, ...event});
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), request.timeoutMs);
  try {
    const response = await fetch(request.url, {
      method: 'POST',
      credentials: 'include',
      headers: request.headers,
      body: JSON.stringify(request.body),
      signal: controller.signal
    });
    await emit({type: 'status', status: response.status,
      contentType: response.headers.get('content-type') || ''});
    if (!response.ok) {
      await emit({type: 'error', data: (await response.text()).slice(0, 16384)});
      return;
    }
    const reader = response.body?.getReader();
    if (!reader) throw new Error('MiMo browser response has no stream body');
    const decoder = new TextDecoder();
    let pending = '';
    const consume = async text => {
      pending += text;
      const frames = pending.split(/\r?\n\r?\n/);
      pending = frames.pop() || '';
      for (const frame of frames) {
        for (const line of frame.split(/\r?\n/)) {
          if (line.startsWith('data:') && line.slice(5).trim()) {
            await emit({type: 'data', data: line.slice(5).trim()});
          }
        }
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


_UPLOAD_MEDIA = r"""async input => {
  const decode = value => {
    const match = /^data:([^;]+);base64,(.+)$/s.exec(String(value || ''));
    if (!match) throw new Error('MiMo media must be an inline base64 data URL');
    const binary = atob(match[2]);
    const bytes = new Uint8Array(binary.length);
    for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
    if (!bytes.length || bytes.length > input.maximumBytes) {
      throw new Error('MiMo media exceeds the configured upload limit');
    }
    return {type: match[1], bytes};
  };
  const result = [];
  for (const source of input.images) {
    const decoded = decode(source.dataUrl);
    const filename = String(source.filename || ('upload-' + crypto.randomUUID() + '.bin'));
    const withPhase = path => path + (path.includes('?') ? '&' : '?') +
      'xiaomichatbot_ph=' + encodeURIComponent(input.phase);
    const infoResponse = await fetch(withPhase(input.uploadInfoPath), {
        method: 'POST',
        credentials: 'include',
        headers: {'Content-Type': 'application/json', 'x-timezone': input.timezone},
        body: JSON.stringify({fileName: filename})
      });
    const info = await infoResponse.json();
    const data = info?.data ?? info;
    if (!infoResponse.ok || (info?.code != null && Number(info.code) !== 0) ||
        !data.uploadUrl || !data.resourceUrl || !data.objectName) {
      throw new Error('MiMo media upload information was rejected status=' +
        infoResponse.status + ' code=' + String(info?.code ?? 'missing'));
    }
    const uploadResponse = await fetch(data.uploadUrl, {
      method: 'PUT',
      headers: {'Content-Type': 'application/octet-stream'},
      body: new Blob([decoded.bytes], {type: decoded.type})
    });
    if (!uploadResponse.ok) {
      throw new Error('MiMo object upload was rejected status=' + uploadResponse.status);
    }
    let parsed = null;
    let lastParseStatus = 0;
    let lastParseCode = 'missing';
    for (let attempt = 0; attempt < 5; attempt++) {
      const parseUrl = withPhase(input.parsePath) + '&fileUrl=' + encodeURIComponent(data.resourceUrl) +
        '&objectName=' + encodeURIComponent(data.objectName) +
        '&model=' + encodeURIComponent(input.model);
      const parseResponse = await fetch(parseUrl, {
        method: 'POST',
        credentials: 'include',
        headers: {'Content-Type': 'application/json', 'x-timezone': input.timezone},
        body: '{}'
      });
      const parseBody = await parseResponse.json();
      const parseData = parseBody?.data ?? parseBody;
      lastParseStatus = parseResponse.status;
      lastParseCode = String(parseBody?.code ?? 'missing');
      if (parseResponse.ok && (parseBody?.code == null || Number(parseBody.code) === 0) &&
          parseData?.id) {
        parsed = parseData;
        break;
      }
      await new Promise(resolve => setTimeout(resolve, 2000));
    }
    if (!parsed) {
      throw new Error('MiMo media parsing did not complete status=' + lastParseStatus +
        ' code=' + lastParseCode);
    }
    result.push({
      mediaType: 'image',
      fileUrl: data.resourceUrl,
      compressedVideoUrl: '',
      audioTrackUrl: '',
      name: filename,
      size: decoded.bytes.length,
      status: 'completed',
      objectName: data.objectName,
      tokenUsage: Number(parsed.tokenUsage || 0),
      url: String(parsed.id)
    });
  }
  return result;
}"""


class MimoOfficialBrowserTransport(OfficialBrowserRuntime):
    def __init__(self, base_url: str) -> None:
        super().__init__(
            "mimo",
            base_url,
            allowed_domain_suffixes=("xiaomimimo.com",),
            identity_fields=("user_id", "email"),
        )
        self._stream_queues: dict[str, asyncio.Queue[dict[str, Any]]] = {}

    async def request(
        self,
        credential: dict[str, Any],
        proxy_url: str,
        plan: RuntimePlan | None = None,
    ) -> dict[str, Any]:
        async with self.account_operation(credential):
            session, selection, reports = await self._select_session(
                credential, proxy_url, plan or default_runtime_plan()
            )
            try:
                result = await session.page.evaluate(
                    _config_request(selection.rules),
                    {
                        "url": self._endpoint(
                            selection,
                            selection.rules.endpoint_paths.get("models", "/open-apis/bot/config"),
                        ),
                        "headers": _headers(),
                        "timeoutMs": selection.rules.canary_timeout_seconds * 1000,
                        "maximumBytes": core_settings().browser_transport_max_buffered_bytes,
                    },
                )
            except Exception as error:
                logger.warning(
                    "mimo_official_browser_request_failed error_type=%s reason=%s",
                    type(error).__name__,
                    _error_reason(error),
                )
                raise
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
        async with self.account_operation(credential):
            session, selection, reports = await self._select_session(credential, proxy_url, plan)
            uploaded_media = await self._upload_media(
                session, credential, semantic_command, selection.rules
            )
            body = build_mimo_chat_request(semantic_command, uploaded_media=uploaded_media)
            for report in reports:
                yield {"type": "runtime_canary", **report}
            request_id = uuid4().hex
            queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue()
            self._stream_queues[request_id] = queue

            async def execute() -> None:
                try:
                    await session.page.evaluate(
                        _stream_request(selection.rules),
                        {
                            "requestId": request_id,
                            "url": self._endpoint(
                                selection,
                                _with_phase(
                                    selection.rules.endpoint_paths.get(
                                        "chat", "/open-apis/bot/chat"
                                    ),
                                    credential,
                                ),
                            ),
                            "headers": _headers(),
                            "body": body,
                            "timeoutMs": core_settings().registration_timeout_seconds * 1000,
                        },
                    )
                except Exception as error:  # noqa: BLE001 - normalized stream boundary
                    logger.warning(
                        "mimo_official_browser_stream_failed error_type=%s reason=%s",
                        type(error).__name__,
                        _error_reason(error),
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
            data_events = 0
            data_bytes = 0
            try:
                while True:
                    event = await queue.get()
                    if event.get("type") == "done":
                        break
                    if event.get("type") == "error":
                        pending_error = event
                        logger.warning(
                            "mimo_official_browser_stream_error status=%s error_bytes=%s",
                            status,
                            len(str(event.get("data") or "").encode("utf-8")),
                        )
                        continue
                    if event.get("type") == "status":
                        status = int(event.get("status") or 502)
                        logger.info(
                            "mimo_official_browser_stream_status status=%s content_type=%s",
                            status,
                            str(event.get("contentType") or "")[:120],
                        )
                    if (
                        event.get("type") == "data"
                        and str(event.get("data") or "").strip() == "[DONE]"
                    ):
                        terminal_data = event
                        continue
                    if event.get("type") == "data" and str(event.get("data") or ""):
                        data_seen = True
                        data_events += 1
                        data_bytes += len(str(event.get("data") or "").encode("utf-8"))
                    yield event
                logger.info(
                    "mimo_official_browser_stream_complete status=%s data_events=%s "
                    "data_bytes=%s error=%s",
                    status,
                    data_events,
                    data_bytes,
                    pending_error is not None,
                )
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
                self._stream_queues.pop(request_id, None)
                if not task.done():
                    task.cancel()
                await asyncio.gather(task, return_exceptions=True)

    async def _upload_media(
        self,
        session: OfficialBrowserSession,
        credential: dict[str, Any],
        command: dict[str, Any],
        rule: RuntimeRule,
    ) -> list[dict[str, Any]]:
        images = _mimo_media_sources(command.get("messages"))
        if not images:
            return []
        phase = str(credential.get("xiaomichatbot_ph") or "").strip()
        if not phase:
            raise ValueError("MiMo media upload requires xiaomichatbot_ph")
        result = await session.page.evaluate(
            _UPLOAD_MEDIA,
            {
                "images": images,
                "phase": phase,
                "model": str(command.get("model") or ""),
                "timezone": "Asia/Shanghai",
                "uploadInfoPath": rule.endpoint_paths.get(
                    "uploadInfo", "/open-apis/resource/genUploadInfo"
                ),
                "parsePath": rule.endpoint_paths.get("parse", "/open-apis/resource/parse"),
                "maximumBytes": 25 * 1024 * 1024,
            },
        )
        if not isinstance(result, list):
            raise TypeError("MiMo media upload returned an invalid result")
        if len(result) != len(images) or any(not isinstance(item, dict) for item in result):
            raise RuntimeError("MiMo media upload returned an incomplete result")
        return list(result)

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

    def _emit(self, event: Any) -> None:
        if not isinstance(event, dict):
            return
        queue = self._stream_queues.get(str(event.get("requestId") or ""))
        if queue is None:
            return
        queue.put_nowait({key: value for key, value in event.items() if key != "requestId"})

    def _endpoint(self, selection: RuntimeRuleSelection, path: str) -> str:
        del selection
        return f"{self.base_url}{path}"


def build_mimo_chat_request(
    command: dict[str, Any], *, uploaded_media: list[dict[str, Any]] | None = None
) -> dict[str, Any]:
    _validate_semantic_command(command)
    media_blocks = list(iter_media_blocks(command["messages"], "MiMo"))
    unsupported_media = sorted({kind for _, kind, _ in media_blocks if kind != "image"})
    if unsupported_media:
        raise ValueError(
            "MiMo browser upload does not support media types: " + ", ".join(unsupported_media)
        )
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
    if uploaded_media is None:
        uploaded_media = command.get("uploadedMedia") or []
    if not isinstance(uploaded_media, list):
        raise TypeError("MiMo uploadedMedia must be an array")
    if len(uploaded_media) != len(media_blocks):
        if media_blocks:
            raise ValueError(
                "MiMo image input must be uploaded in the same account browser session"
            )
        if uploaded_media:
            raise ValueError("MiMo uploadedMedia contains files without source content")
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


def _mimo_media_sources(messages: Any) -> list[dict[str, str]]:
    media_blocks = list(iter_media_blocks(messages, "MiMo"))
    sources: list[dict[str, str]] = []
    extensions = {
        "jpeg": "jpg",
        "jpg": "jpg",
        "png": "png",
        "webp": "webp",
        "gif": "gif",
        "avif": "avif",
    }
    for _, kind, part in media_blocks:
        if kind != "image":
            raise ValueError(f"MiMo browser upload does not support {kind} input")
        source = media_source(part)
        mime, _ = decode_inline_data_url(
            source,
            "MiMo image",
            max_bytes=25 * 1024 * 1024,
            expected_prefix="image/",
        )
        extension = extensions.get(mime.removeprefix("image/"), "bin")
        sources.append(
            {
                "dataUrl": source,
                "filename": f"upload-{uuid4().hex}.{extension}",
            }
        )
    return sources


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
            "uploadInfo": "/open-apis/resource/genUploadInfo",
            "parse": "/open-apis/resource/parse",
        },
    )
    return RuntimePlan(RuntimeRuleSelection("mimo", 1, rule), None, "", "")


def _headers() -> dict[str, str]:
    return {
        "Accept": "*/*",
        "Content-Type": "application/json",
        "x-timezone": "Asia/Shanghai",
    }


def _with_phase(path: str, credential: dict[str, Any]) -> str:
    phase = str(credential.get("xiaomichatbot_ph") or "").strip()
    if not phase:
        raise ValueError("MiMo browser request requires xiaomichatbot_ph")
    separator = "&" if "?" in path else "?"
    return f"{path}{separator}xiaomichatbot_ph={quote(phase, safe='')}"


def _validate_semantic_command(command: dict[str, Any]) -> None:
    reject_raw_request(command, "MiMo")
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
    return text_content(value, "MiMo", allow_media=True)


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


def _error_reason(error: BaseException) -> str:
    value = " ".join(str(error).split())
    if not value:
        return "<empty>"
    return value[:200]
