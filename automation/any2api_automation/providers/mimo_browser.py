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

logger = logging.getLogger("any2api_automation.providers.mimo_browser")

_LOCATE_BRIDGE = r"""() => {
  const cached = window.__any2apiMimoOfficialBridge;
  if (cached?.chat?.completions && cached?.config?.getConfig) return cached;
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
      if (!source.includes('/open-apis/bot/chat') || !source.includes('genUploadInfo')) {
        continue;
      }
      let exports;
      try { exports = runtime(id); } catch (_) { continue; }
      try {
        const values = Object.values(exports || {});
        const chat = values.find(value => value && typeof value === 'object' &&
          typeof value.completions === 'function');
        const config = values.find(value => value && typeof value === 'object' &&
          typeof value.getConfig === 'function');
        const media = values.find(value => value && typeof value === 'object' &&
          typeof value.upload === 'function' && typeof value.upload2Parse === 'function');
        if (chat && config) {
          window.__any2apiMimoOfficialBridge = {chat, config, media, diagnosticModuleId: id};
          return window.__any2apiMimoOfficialBridge;
        }
      } catch (_) { continue; }
    }
  }
  throw new Error('MiMo official request bridge was not found');
}"""

_CONFIG_REQUEST = rf"""async request => {{
  const locate = {_LOCATE_BRIDGE};
  const bridge = locate();
  const result = await bridge.config.getConfig();
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

_STREAM_REQUEST = rf"""async request => {{
  const locate = {_LOCATE_BRIDGE};
  const bridge = locate();
  const emit = event => window.__any2apiMimoEmit({{requestId: request.requestId, ...event}});
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), request.timeoutMs);
  try {{
    const result = await bridge.chat.completions(request.body, controller);
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
    ) -> dict[str, Any]:
        async with self.lock:
            session = await self.session_for(credential, proxy_url)
            result = await session.page.evaluate(
                _CONFIG_REQUEST,
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
            }

    async def stream(
        self,
        credential: dict[str, Any],
        body: str,
        proxy_url: str,
    ) -> AsyncIterator[dict[str, Any]]:
        parsed_body = json.loads(body)
        if not isinstance(parsed_body, dict):
            raise TypeError("MiMo completion body must be an object")
        async with self.lock:
            session = await self.session_for(credential, proxy_url)
            request_id = uuid4().hex
            queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue()
            self._active_stream_id = request_id
            self._active_stream_queue = queue

            async def execute() -> None:
                try:
                    await session.page.evaluate(
                        _STREAM_REQUEST,
                        {
                            "requestId": request_id,
                            "body": parsed_body,
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
                            "data": (
                                "official browser stream failed "
                                f"({type(error).__name__})"
                            ),
                        }
                    )
                finally:
                    await queue.put({"type": "done"})

            task = asyncio.create_task(execute())
            pending_error: dict[str, Any] | None = None
            try:
                while True:
                    event = await queue.get()
                    if event.get("type") == "done":
                        break
                    if event.get("type") == "error":
                        pending_error = event
                        continue
                    yield event
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

    async def wait_until_ready(self, page: Any) -> None:
        await page.wait_for_function(
            """() => Object.getOwnPropertyNames(window)
              .some(name => name.startsWith('rspackChunk'))""",
            timeout=60_000,
        )
        deadline = asyncio.get_running_loop().time() + 60
        last_error: Exception | None = None
        while asyncio.get_running_loop().time() < deadline:
            try:
                await page.evaluate(_LOCATE_BRIDGE)
                return
            except Exception as error:
                if "official request bridge was not found" not in str(error):
                    raise
                last_error = error
                await page.wait_for_timeout(1_000)
        raise RuntimeError("MiMo official request bridge did not load in time") from last_error

    def _emit(self, event: Any) -> None:
        if not isinstance(event, dict) or event.get("requestId") != self._active_stream_id:
            return
        queue = self._active_stream_queue
        if queue is None:
            return
        queue.put_nowait(
            {key: value for key, value in event.items() if key != "requestId"}
        )


def official_bridge_script() -> str:
    return _LOCATE_BRIDGE
