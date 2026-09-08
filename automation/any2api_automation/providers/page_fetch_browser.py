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
from .official_browser import OfficialBrowserRuntime, OfficialBrowserSession
from .runtime_rules import (
    RuntimePlan,
    RuntimeRule,
    RuntimeRuleDiscoveryError,
    RuntimeRuleSelection,
    runtime_canary,
    successful_canary,
)

_COOKIE_NAME = re.compile(r"^[!#$%&'*+\-.^_`|~0-9A-Za-z]{1,128}$")


def _buffered_request_script() -> str:
    return r"""async request => {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), request.timeoutMs);
  try {
    const response = await fetch(request.url, {
      method: request.method,
      credentials: 'include',
      headers: request.headers,
      body: request.body === '' ? undefined : request.body,
      signal: controller.signal
    });
    const body = await response.text();
    if (body.length > request.maximumBytes) {
      throw new Error('official browser response exceeds the buffered byte limit');
    }
    return {
      status: response.status,
      body,
      contentType: response.headers.get('content-type') || ''
    };
  } finally {
    clearTimeout(timeout);
  }
}"""


def _stream_request_script(binding_name: str) -> str:
    binding = json.dumps(binding_name)
    return rf"""async request => {{
  const emit = event => window[{binding}]({{requestId: request.requestId, ...event}});
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), request.timeoutMs);
  try {{
    const response = await fetch(request.url, {{
      method: request.method,
      credentials: 'include',
      headers: request.headers,
      body: request.body === '' ? undefined : request.body,
      signal: controller.signal
    }});
    await emit({{type: 'status', status: response.status}});
    if (!response.ok) {{
      await emit({{type: 'error', data: (await response.text()).slice(0, 16384)}});
      return;
    }}
    const reader = response.body?.getReader();
    if (!reader) throw new Error('official browser response has no stream body');
    const decoder = new TextDecoder();
    let pending = '';
    const consume = async text => {{
      pending += text;
      const frames = pending.split(/\r?\n\r?\n/);
      pending = frames.pop() || '';
      for (const frame of frames) {{
        const data = frame.split(/\r?\n/)
          .filter(line => line.startsWith('data:'))
          .map(line => line.slice(5).trimStart())
          .join('\n');
        if (data) await emit({{type: 'data', data}});
      }}
    }};
    while (true) {{
      const {{done, value}} = await reader.read();
      if (done) break;
      await consume(decoder.decode(value, {{stream: true}}));
    }}
    await consume(decoder.decode());
    if (pending.trim()) await consume('\n\n');
  }} finally {{
    clearTimeout(timeout);
  }}
}}"""


class PageFetchBrowserRuntime(OfficialBrowserRuntime):
    """Executes provider requests with fetch from an isolated Camoufox page."""

    def __init__(
        self,
        provider_id: str,
        base_url: str,
        *,
        allowed_domain_suffixes: tuple[str, ...],
        identity_fields: tuple[str, ...],
        cookie_fields: tuple[str, ...] = (),
        require_cookie: bool = False,
        page_url: str | None = None,
    ) -> None:
        super().__init__(
            provider_id,
            base_url,
            allowed_domain_suffixes=allowed_domain_suffixes,
            identity_fields=identity_fields,
            require_build_assets=False,
            page_url=page_url,
        )
        self.cookie_fields = cookie_fields
        self.require_cookie = require_cookie
        self._stream_queues: dict[str, asyncio.Queue[dict[str, Any]]] = {}
        self._binding_name = f"__any2api_{provider_id.replace('-', '_')}_emit"
        self._logger = logging.getLogger(f"any2api_automation.providers.{provider_id}_page_fetch")

    async def request(
        self,
        credential: dict[str, Any],
        proxy_url: str,
        plan: RuntimePlan,
        *,
        method: str,
        path: str = "",
        endpoint_key: str | None = None,
        headers: dict[str, str] | None = None,
        body: str = "",
        timeout_ms: int | None = None,
    ) -> dict[str, Any]:
        method = _method(method)
        async with self.account_operation(credential):
            session, selection, reports = await self._select_session(credential, proxy_url, plan)
            target_path = _resolve_target_path(path, endpoint_key, selection.rules.endpoint_paths)
            response = await session.page.evaluate(
                _buffered_request_script(),
                {
                    "url": self._endpoint(selection, target_path),
                    "method": method,
                    "headers": _headers(headers),
                    "body": body,
                    "timeoutMs": timeout_ms or selection.rules.canary_timeout_seconds * 1000,
                    "maximumBytes": core_settings().browser_transport_max_buffered_bytes,
                },
            )
            if not isinstance(response, dict):
                raise TypeError(f"{self.provider_id} official browser returned invalid response")
            return {
                "status": int(response.get("status") or 502),
                "body": str(response.get("body") or ""),
                "content_type": str(response.get("contentType") or ""),
                "credential_patch": await self.credential_patch(session, credential),
                "transport_mode": "camoufox_browser_runtime",
                "runtime_reports": reports,
            }

    async def stream(
        self,
        credential: dict[str, Any],
        proxy_url: str,
        plan: RuntimePlan,
        *,
        method: str,
        path: str = "",
        endpoint_key: str | None = None,
        headers: dict[str, str] | None = None,
        body: str = "",
        timeout_ms: int | None = None,
    ) -> AsyncIterator[dict[str, Any]]:
        method = _method(method)
        async with self.account_operation(credential):
            session, selection, reports = await self._select_session(credential, proxy_url, plan)
            target_path = _resolve_target_path(path, endpoint_key, selection.rules.endpoint_paths)
            for report in reports:
                yield {"type": "runtime_canary", **report}

            request_id = uuid4().hex
            queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue()
            self._stream_queues[request_id] = queue

            async def execute() -> None:
                try:
                    await session.page.evaluate(
                        _stream_request_script(self._binding_name),
                        {
                            "requestId": request_id,
                            "url": self._endpoint(selection, target_path),
                            "method": method,
                            "headers": _headers(headers),
                            "body": body,
                            "timeoutMs": timeout_ms
                            or selection.rules.canary_timeout_seconds * 1000,
                        },
                    )
                except Exception as error:  # noqa: BLE001 - stream boundary
                    self._logger.warning(
                        "official_browser_stream_failed error_type=%s",
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
        values = _credential_cookies(credential, self.cookie_fields)
        if not values and self.require_cookie:
            raise ValueError(f"{self.provider_id} official browser requires an account cookie")
        if not values:
            return
        domains = tuple(f".{suffix}" for suffix in self.allowed_domain_suffixes)
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
            for domain in domains
        ]
        await context.add_cookies(cookies)

    async def configure_page(
        self,
        session: OfficialBrowserSession,
        credential: dict[str, Any],
    ) -> None:
        del credential
        await session.page.expose_binding(
            self._binding_name,
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

    def _endpoint(self, selection: RuntimeRuleSelection, path: str) -> str:
        del selection
        return f"{self.base_url}{path}"

    def _emit(self, event: Any) -> None:
        if not isinstance(event, dict):
            return
        queue = self._stream_queues.get(str(event.get("requestId") or ""))
        if queue is None:
            return
        queue.put_nowait({key: value for key, value in event.items() if key != "requestId"})


def _method(value: str) -> str:
    normalized = str(value or "").strip().upper()
    if normalized not in {"GET", "POST", "PUT", "PATCH", "DELETE"}:
        raise ValueError("official browser method is not allowlisted")
    return normalized


def _path(value: str) -> str:
    normalized = str(value or "").strip()
    if (
        not normalized.startswith("/")
        or "//" in normalized[1:]
        or "://" in normalized
        or "\\" in normalized
        or "/../" in f"{normalized}/"
    ):
        raise ValueError("official browser endpoint must be a same-origin path")
    return normalized


def _resolve_target_path(
    fallback_path: str,
    endpoint_key: str | None,
    endpoint_paths: dict[str, str],
) -> str:
    if endpoint_key:
        configured_path = endpoint_paths.get(endpoint_key)
        if configured_path:
            return _path(configured_path)
    return _path(fallback_path)


def _headers(value: dict[str, str] | None) -> dict[str, str]:
    if value is None:
        return {}
    if not isinstance(value, dict):
        raise TypeError("official browser headers must be an object")
    output: dict[str, str] = {}
    for name, raw_value in value.items():
        normalized_name = str(name).strip()
        normalized_value = str(raw_value)
        if not normalized_name or "\r" in normalized_name or "\n" in normalized_name:
            raise ValueError("official browser header name is invalid")
        if "\r" in normalized_value or "\n" in normalized_value:
            raise ValueError("official browser header value is invalid")
        output[normalized_name] = normalized_value
    return output


def _credential_cookies(
    credential: dict[str, Any],
    cookie_fields: tuple[str, ...],
) -> dict[str, str]:
    values: dict[str, str] = {}
    for field in (
        "cookies",
        "cookie",
        "session_cookies",
        "cloudflare_cookies",
        "cf_cookies",
        *cookie_fields,
    ):
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
        for name, raw_value in pairs:
            normalized_name = str(name).strip()
            normalized_value = str(raw_value).strip()
            if _COOKIE_NAME.fullmatch(normalized_name) and normalized_value:
                values[normalized_name] = normalized_value
    for field in cookie_fields:
        raw_value = str(credential.get(field) or "").strip()
        if raw_value and _COOKIE_NAME.fullmatch(field):
            values[field] = raw_value
    return values
