from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import logging
import os
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from contextvars import ContextVar
from copy import deepcopy
from dataclasses import dataclass
from typing import Any
from urllib.parse import urlparse
from uuid import uuid4

from patchright.async_api import async_playwright

from ..config import settings as core_settings
from ..lifecycle.browser import camoufox_config_from_options
from ..session_pool import AccountSessionPool, SessionSlot
from .runtime_rules import RuntimePlan

logger = logging.getLogger("any2api_automation.providers.minmax_browser")
_SCHEMA_VERSION = 1

_LOCATE_BRIDGE = r"""() => {
  const cached = window.__any2apiMinmaxOfficialBridge;
  if (typeof cached === 'function') return cached;
  const chunkNames = Object.keys(window).filter(name => name.startsWith('webpackChunk'));
  for (const chunkName of chunkNames) {
    const chunks = window[chunkName];
    if (!Array.isArray(chunks)) continue;
    let runtime;
    chunks.push([['any2api-' + Date.now()], {}, require => { runtime = require; }]);
    if (!runtime?.m) continue;
    for (const [id, factory] of Object.entries(runtime.m)) {
      const source = String(factory);
      if (!source.includes('x-signature') || !source.includes('hasSearchParamsPath')) continue;
      let exports;
      try { exports = runtime(id); } catch (_) { continue; }
      for (const candidate of Object.values(exports || {})) {
        if (typeof candidate !== 'function') continue;
        const candidateSource = String(candidate);
        if (candidateSource.includes('return fetch(')) {
          window.__any2apiMinmaxOfficialBridge = candidate;
          return candidate;
        }
      }
    }
  }
  throw new Error('MinMax official request bridge was not found');
}"""

_BUFFERED_REQUEST = rf"""async request => {{
  const locate = {_LOCATE_BRIDGE};
  const bridge = locate();
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), request.timeoutMs);
  try {{
    const init = {{method: request.method, signal: controller.signal}};
    if (!['GET', 'HEAD'].includes(request.method)) init.body = request.body;
    const response = await bridge(request.path, init, {{stream: request.stream}});
    const bytes = new Uint8Array(await response.arrayBuffer());
    if (bytes.length > request.maximumBytes) {{
      throw new Error('MinMax browser response exceeds the buffered byte limit');
    }}
    let binary = '';
    for (let index = 0; index < bytes.length; index += 32768) {{
      binary += String.fromCharCode(...bytes.subarray(index, index + 32768));
    }}
    return {{
      status: response.status,
      contentType: response.headers.get('content-type') || 'application/octet-stream',
      bodyBase64: btoa(binary)
    }};
  }} finally {{
    clearTimeout(timeout);
  }}
}}"""

_STREAM_REQUEST = rf"""async request => {{
  const locate = {_LOCATE_BRIDGE};
  const bridge = locate();
  const emit = event => window.__any2apiMinmaxEmit({{requestId: request.requestId, ...event}});
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), request.timeoutMs);
  try {{
    const response = await bridge(request.path, {{
      method: request.method,
      body: request.body,
      signal: controller.signal
    }}, {{stream: true}});
    await emit({{type: 'status', status: response.status}});
    if (!response.ok) {{
      await emit({{type: 'error', data: (await response.text()).slice(0, 16384)}});
      return;
    }}
    const reader = response.body?.getReader();
    if (!reader) throw new Error('MinMax official response has no stream body');
    const decoder = new TextDecoder();
    let pending = '';
    while (true) {{
      const {{done, value}} = await reader.read();
      if (done) break;
      pending += decoder.decode(value, {{stream: true}});
      const lines = pending.split(/\r?\n/);
      pending = lines.pop() || '';
      for (const line of lines) {{
        if (line.startsWith('data:') && line.slice(5).trim()) {{
          await emit({{type: 'data', data: line.slice(5).trim()}});
        }}
      }}
    }}
    pending += decoder.decode();
    if (pending.startsWith('data:') && pending.slice(5).trim()) {{
      await emit({{type: 'data', data: pending.slice(5).trim()}});
    }}
  }} finally {{
    clearTimeout(timeout);
  }}
}}"""

_UPLOAD_MEDIA = r"""async input => {
  const locate = () => {
    const cached = window.__any2apiMinmaxOfficialBridge;
    if (typeof cached === 'function') return cached;
    throw new Error('MinMax official request bridge was not found');
  };
  const bridge = locate();
  const readJson = async response => {
    const text = await response.text();
    let body;
    try { body = JSON.parse(text); } catch (_) { body = {}; }
    if (!response.ok) throw new Error('MinMax media request was rejected');
    return body;
  };
  const sign = async (secret, value) => {
    const key = await crypto.subtle.importKey(
      'raw', new TextEncoder().encode(secret), {name: 'HMAC', hash: 'SHA-1'},
      false, ['sign']
    );
    const bytes = new Uint8Array(await crypto.subtle.sign(
      'HMAC', key, new TextEncoder().encode(value)
    ));
    let binary = '';
    for (const byte of bytes) binary += String.fromCharCode(byte);
    return btoa(binary);
  };
  const encodedPath = value => value.split('/').map(encodeURIComponent).join('/');
  const objectUrl = (endpoint, bucket, objectName) => {
    const parsed = new URL(endpoint);
    const originalHost = parsed.hostname;
    if (!originalHost.startsWith(bucket + '.')) parsed.hostname = bucket + '.' + originalHost;
    const prefix = parsed.pathname.replace(/\/+$/, '');
    parsed.pathname = prefix + '/' + encodedPath(objectName);
    return parsed.toString();
  };
  const output = [];
  for (const source of input.images) {
    const match = /^data:([^;]+);base64,(.+)$/s.exec(String(source.data_url || ''));
    if (!match) throw new Error('MinMax media must be an inline base64 data URL');
    const binary = atob(match[2]);
    const bytes = new Uint8Array(binary.length);
    for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
    if (!bytes.length || bytes.length > input.maximumBytes) {
      throw new Error('MinMax media exceeds the configured upload limit');
    }
    const policyResponse = await bridge(input.policyPath, {
      method: 'GET',
      credentials: 'include'
    }, {stream: false});
    const policyBody = await readJson(policyResponse);
    const policy = policyBody?.data || {};
    const endpoint = String(policy.endpoint || '').trim();
    const accessKeyId = String(policy.accessKeyId || '').trim();
    const accessKeySecret = String(policy.accessKeySecret || '').trim();
    const securityToken = String(policy.securityToken || '').trim();
    const bucketName = String(policy.bucketName || '').trim();
    const dir = String(policy.dir || '').replace(/\/+$/, '');
    if (!endpoint || !accessKeyId || !accessKeySecret || !securityToken ||
        !bucketName || !dir) throw new Error('MinMax upload policy is incomplete');
    const filename = String(source.file_name || ('upload-' + crypto.randomUUID() + '.bin'));
    const extensionIndex = filename.lastIndexOf('.');
    const extension = extensionIndex > 0 ? filename.slice(extensionIndex) : '.bin';
    const objectName = dir + '/' + crypto.randomUUID().replaceAll('-', '') + extension;
    const contentType = String(source.mime_type || match[1]);
    const ossDate = new Date().toISOString().replace(/\.\d{3}Z$/, 'Z');
    const canonicalHeaders = 'x-oss-date:' + ossDate + '\n' +
      'x-oss-security-token:' + securityToken + '\n';
    const resource = '/' + bucketName + '/' + objectName;
    const stringToSign = 'PUT\n\n' + contentType + '\n\n' +
      canonicalHeaders + resource;
    const signature = await sign(accessKeySecret, stringToSign);
    const uploadResponse = await fetch(objectUrl(endpoint, bucketName, objectName), {
      method: 'PUT',
      headers: {
        'Authorization': 'OSS ' + accessKeyId + ':' + signature,
        'Content-Type': contentType,
        'x-oss-date': ossDate,
        'x-oss-security-token': securityToken
      },
      body: bytes
    });
    if (!uploadResponse.ok) throw new Error('MinMax object upload was rejected');
    const callbackBody = {
      fileName: objectName.slice(objectName.lastIndexOf('/') + 1),
      originFileName: filename,
      dir,
      endpoint,
      bucketName,
      size: String(bytes.length),
      mimeType: contentType,
      fileMd5: String(source.file_md5 || '')
    };
    const callbackResponse = await bridge(input.callbackPath, {
      method: 'POST',
      credentials: 'include',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(callbackBody)
    }, {stream: false});
    const callbackBodyResult = await readJson(callbackResponse);
    const ossPath = String(callbackBodyResult?.data?.ossPath || '').trim();
    if (!ossPath) throw new Error('MinMax media callback returned no ossPath');
    output.push({
      type: 'image',
      file_path: filename,
      file_name: filename,
      mime_type: contentType,
      data_url: ossPath
    });
  }
  return output;
}"""


@dataclass
class _Session:
    key: str
    browser: Any
    context: Any
    page: Any
    backend: str
    state_digest: str
    input_digest: str
    proxy_url: str
    browser_manager: Any | None = None
    playwright: Any | None = None
    camoufox_config: dict[str, Any] | None = None
    active_stream_id: str = ""
    active_stream_queue: asyncio.Queue[dict[str, Any]] | None = None


class MinmaxOfficialBrowserTransport:
    """Runs MinMax requests through the current official page module."""

    def __init__(self, base_url: str) -> None:
        self._base_url = base_url.rstrip("/")
        self._sessions: AccountSessionPool[_Session] = AccountSessionPool(
            core_settings().official_browser_session_pool_size, self._close_session
        )
        self._operation: ContextVar[SessionSlot[_Session]] = ContextVar("minmax_browser_operation")

    @asynccontextmanager
    async def _account_operation(self, credential: dict[str, Any]):
        async with self._sessions.borrow(_account_key(credential)) as slot:
            token = self._operation.set(slot)
            try:
                yield
            finally:
                self._operation.reset(token)

    async def request(
        self,
        credential: dict[str, Any],
        method: str,
        path: str,
        body: str,
        proxy_url: str,
    ) -> dict[str, Any]:
        async with self._account_operation(credential):
            session = await self._session_for(credential, proxy_url)
            await self._inject_context(session, credential)
            result = await session.page.evaluate(
                _BUFFERED_REQUEST,
                {
                    "method": method,
                    "path": path,
                    "body": body,
                    "stream": False,
                    "timeoutMs": 120_000,
                    "maximumBytes": core_settings().browser_transport_max_buffered_bytes,
                },
            )
            if not isinstance(result, dict):
                raise TypeError("MinMax official browser returned an invalid response")
            try:
                raw_body = base64.b64decode(str(result.get("bodyBase64") or ""), validate=True)
            except ValueError as error:
                raise RuntimeError(
                    "MinMax official browser returned invalid body encoding"
                ) from error
            patch = await self._credential_patch(session, credential)
            return {
                "status": int(result.get("status") or 502),
                "body": raw_body.decode("utf-8", errors="replace"),
                "credential_patch": patch,
                "transport_mode": "official_browser_runtime",
            }

    async def upload_media(
        self,
        credential: dict[str, Any],
        attachments: list[dict[str, Any]],
        plan: RuntimePlan,
        proxy_url: str,
        maximum_bytes: int,
    ) -> list[dict[str, Any]]:
        if not attachments:
            return []
        async with self._account_operation(credential):
            session = await self._session_for(credential, proxy_url)
            await self._inject_context(session, credential)
            result = await session.page.evaluate(
                _UPLOAD_MEDIA,
                {
                    "images": attachments,
                    "policyPath": str(
                        plan.active.rules.endpoint_paths.get(
                            "filesPolicy", "/v1/api/files/request_policy"
                        )
                    ),
                    "callbackPath": str(
                        plan.active.rules.endpoint_paths.get(
                            "filesCallback", "/v1/api/files/policy_callback"
                        )
                    ),
                    "maximumBytes": maximum_bytes,
                },
            )
            if not isinstance(result, list):
                raise TypeError("MinMax media upload returned an invalid result")
            if len(result) != len(attachments) or any(
                not isinstance(item, dict) for item in result
            ):
                raise RuntimeError("MinMax media upload returned an incomplete result")
            return list(result)

    async def stream(
        self,
        credential: dict[str, Any],
        method: str,
        path: str,
        body: str,
        proxy_url: str,
    ) -> AsyncIterator[dict[str, Any]]:
        async with self._account_operation(credential):
            session = await self._session_for(credential, proxy_url)
            await self._inject_context(session, credential)
            request_id = uuid4().hex
            queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue()
            session.active_stream_id = request_id
            session.active_stream_queue = queue

            async def execute() -> None:
                try:
                    await session.page.evaluate(
                        _STREAM_REQUEST,
                        {
                            "requestId": request_id,
                            "method": method,
                            "path": path,
                            "body": body,
                            "timeoutMs": core_settings().registration_timeout_seconds * 1000,
                        },
                    )
                except Exception as error:  # noqa: BLE001 - normalized into stream protocol
                    logger.warning(
                        "minmax_official_browser_stream_failed error_type=%s",
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
            try:
                while True:
                    event = await queue.get()
                    if event.get("type") == "done":
                        break
                    if event.get("type") == "error":
                        pending_error = event
                        continue
                    yield event
                patch = await self._credential_patch(session, credential)
                if patch:
                    yield {"type": "credential_patch", "data": patch}
                if pending_error is not None:
                    yield pending_error
            finally:
                session.active_stream_id = ""
                session.active_stream_queue = None
                if not task.done():
                    task.cancel()
                await asyncio.gather(task, return_exceptions=True)

    async def close(self) -> None:
        await self._sessions.close()

    async def _session_for(self, credential: dict[str, Any], proxy_url: str) -> _Session:
        key = _account_key(credential)
        incoming_digest = _execution_context_digest(credential)
        slot = self._operation.get()
        if slot.key != key:
            raise ValueError("browser operation account mismatch")
        current = slot.value
        if current is not None:
            closed = (
                current.page.is_closed()
                if callable(getattr(current.page, "is_closed", None))
                else False
            )
            accepted_digests = {current.input_digest, current.state_digest}
            if (
                closed
                or current.key != key
                or current.proxy_url != proxy_url
                or (incoming_digest and incoming_digest not in accepted_digests)
            ):
                slot.value = None
                await self._close_session(current)
                current = None
        if current is None:
            current = await self._new_session(key, credential, proxy_url, incoming_digest)
            slot.value = current
        elif incoming_digest:
            current.input_digest = incoming_digest
        return current

    async def _new_session(
        self,
        key: str,
        credential: dict[str, Any],
        proxy_url: str,
        state_digest: str,
    ) -> _Session:
        execution = _execution_context(credential)
        backend = str(
            execution.get("backend") or credential.get("registration_backend") or "camoufox"
        )
        storage_state = _minmax_storage_state(execution)
        browser_manager = None
        playwright = None
        browser = None
        camoufox_config = None
        if backend == "camoufox":
            from camoufox.async_api import AsyncCamoufox

            exact_config = execution.get("camoufox_config")
            prepared = await asyncio.to_thread(
                _camoufox_launch_options,
                exact_config if isinstance(exact_config, dict) else {},
                proxy_url,
            )
            camoufox_config = camoufox_config_from_options(prepared)
            browser_manager = AsyncCamoufox(from_options=prepared)
            browser = await browser_manager.__aenter__()
        else:
            backend = "patchright"
            playwright = await async_playwright().start()
            options: dict[str, Any] = {"headless": core_settings().registration_headless}
            if proxy_url:
                options["proxy"] = {"server": proxy_url}
            browser = await playwright.chromium.launch(**options)
        context = None
        try:
            context_options = _context_options(execution, backend)
            if storage_state:
                context_options["storage_state"] = storage_state
            context = await browser.new_context(**context_options)
            if backend == "patchright":
                await context.add_init_script(
                    script=_patchright_fingerprint_script(execution.get("runtime_fingerprint"))
                )
            page = await context.new_page()
            page.on("request", _log_upstream_request)
            page.on("response", _log_upstream_response)
            session = _Session(
                key=key,
                browser=browser,
                context=context,
                page=page,
                backend=backend,
                state_digest=state_digest,
                input_digest=state_digest,
                proxy_url=proxy_url,
                browser_manager=browser_manager,
                playwright=playwright,
                camoufox_config=camoufox_config,
            )
            await page.expose_binding(
                "__any2apiMinmaxEmit",
                lambda _source, event: self._emit(session, event),
            )
            await page.goto(self._base_url, wait_until="domcontentloaded", timeout=90_000)
            await page.wait_for_function(
                "() => Object.keys(window).some(name => name.startsWith('webpackChunk'))",
                timeout=60_000,
            )
            await self._wait_for_bridge(page)
            logger.info(
                "minmax_official_browser_session lifecycle=created backend=%s proxy_bound=%s",
                backend,
                bool(proxy_url),
            )
            return session
        except BaseException:
            if context is not None:
                await context.close()
            if browser_manager is not None:
                await browser_manager.__aexit__(None, None, None)
            elif browser is not None:
                await browser.close()
            if playwright is not None:
                await playwright.stop()
            raise

    async def _wait_for_bridge(self, page: Any) -> None:
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
        raise RuntimeError("MinMax official request bridge did not load in time") from last_error

    async def _inject_context(self, session: _Session, credential: dict[str, Any]) -> None:
        token = str(credential.get("token") or credential.get("access_token") or "").strip()
        if not token:
            raise ValueError("MinMax browser context requires token")
        await session.page.evaluate("token => localStorage.setItem('token', token)", token)
        stored = await session.page.evaluate("() => localStorage.getItem('token') || ''")
        if stored != token:
            raise RuntimeError("MinMax browser context injection did not persist the account token")

    async def _credential_patch(
        self, session: _Session, credential: dict[str, Any]
    ) -> dict[str, Any]:
        try:
            state = await session.context.storage_state(indexed_db=True)
        except TypeError:
            state = await session.context.storage_state()
        execution = deepcopy(_execution_context(credential))
        execution.update(
            {
                "schema_version": _SCHEMA_VERSION,
                "backend": session.backend,
                "storage_state": _filter_storage_state(state),
                "runtime_fingerprint": await _runtime_fingerprint(session.page),
            }
        )
        if session.backend == "camoufox" and session.camoufox_config:
            execution["camoufox_config"] = deepcopy(session.camoufox_config)
        digest = _digest(execution)
        session.state_digest = digest
        if digest == _execution_context_digest(credential):
            return {}
        return {"browser_execution_context": execution}

    def _emit(self, session: _Session, event: Any) -> None:
        if not isinstance(event, dict) or event.get("requestId") != session.active_stream_id:
            return
        queue = session.active_stream_queue
        if queue is None:
            return
        queue.put_nowait({key: value for key, value in event.items() if key != "requestId"})

    async def _close_session(self, session: _Session) -> None:
        try:
            await session.context.close()
        finally:
            if session.browser_manager is not None:
                await session.browser_manager.__aexit__(None, None, None)
            else:
                await session.browser.close()
            if session.playwright is not None:
                await session.playwright.stop()


def _execution_context(credential: dict[str, Any]) -> dict[str, Any]:
    value = credential.get("browser_execution_context")
    if value is None:
        return {}
    if not isinstance(value, dict):
        raise TypeError("MinMax browser execution context must be an object")
    if value.get("schema_version") != _SCHEMA_VERSION:
        raise ValueError("MinMax browser execution context schema is unsupported")
    return deepcopy(value)


def _account_key(credential: dict[str, Any]) -> str:
    identity = str(credential.get("user_id") or credential.get("email") or "").strip().lower()
    if not identity:
        raise ValueError("MinMax browser transport requires a stable account identity")
    return hashlib.sha256(identity.encode()).hexdigest()


def _execution_context_digest(credential: dict[str, Any]) -> str:
    value = _execution_context(credential)
    return _digest(value) if value else ""


def _digest(value: dict[str, Any]) -> str:
    return hashlib.sha256(
        json.dumps(value, sort_keys=True, ensure_ascii=True, separators=(",", ":")).encode()
    ).hexdigest()


def _minmax_storage_state(execution: dict[str, Any]) -> dict[str, Any] | None:
    state = execution.get("storage_state")
    if state is None:
        return None
    if not isinstance(state, dict):
        raise TypeError("MinMax browser storage state must be an object")
    return _filter_storage_state(state)


def _filter_storage_state(state: dict[str, Any]) -> dict[str, Any]:
    cookies = state.get("cookies", [])
    origins = state.get("origins", [])
    if not isinstance(cookies, list) or not isinstance(origins, list):
        raise TypeError("MinMax browser storage state collections must be arrays")
    filtered_cookies = []
    for cookie in cookies:
        if not isinstance(cookie, dict):
            continue
        domain = str(cookie.get("domain") or "").lower().lstrip(".")
        if domain == "minimax.io" or domain.endswith(".minimax.io"):
            filtered_cookies.append(deepcopy(cookie))
    filtered_origins = []
    for origin in origins:
        if not isinstance(origin, dict):
            continue
        parsed = urlparse(str(origin.get("origin") or ""))
        host = (parsed.hostname or "").lower()
        if parsed.scheme == "https" and (host == "minimax.io" or host.endswith(".minimax.io")):
            filtered_origins.append(deepcopy(origin))
    return {"cookies": filtered_cookies, "origins": filtered_origins}


def _camoufox_launch_options(config: dict[str, Any], proxy_url: str) -> dict[str, Any]:
    from camoufox.utils import get_env_vars, get_target_os, launch_options

    if config:
        prepared = launch_options(
            config=deepcopy(config),
            headless=core_settings().registration_headless,
            geoip=False,
            proxy=None,
            env={**os.environ, "MOZ_DISABLE_CONTENT_SANDBOX": "1"},
            firefox_user_prefs={"security.sandbox.content.level": 0},
            i_know_what_im_doing=True,
        )
        prepared_env = dict(prepared.get("env") or {})
        for name in tuple(prepared_env):
            if name.startswith("CAMOU_CONFIG_"):
                prepared_env.pop(name)
        prepared_env.update(get_env_vars(config, get_target_os(config)))
        prepared["env"] = prepared_env
    else:
        prepared = launch_options(
            os="windows",
            headless=core_settings().registration_headless,
            humanize=False,
            geoip=bool(proxy_url),
            proxy={"server": proxy_url} if proxy_url else None,
            env={**os.environ, "MOZ_DISABLE_CONTENT_SANDBOX": "1"},
            firefox_user_prefs={"security.sandbox.content.level": 0},
        )
    if proxy_url:
        prepared["proxy"] = {"server": proxy_url}
    return prepared


def _context_options(execution: dict[str, Any], backend: str) -> dict[str, Any]:
    options: dict[str, Any] = {"ignore_https_errors": True}
    if backend == "camoufox":
        return options
    runtime = execution.get("runtime_fingerprint")
    if not isinstance(runtime, dict):
        return options
    screen = runtime.get("screen") if isinstance(runtime.get("screen"), dict) else {}
    width = int(screen.get("width") or 1440)
    height = int(screen.get("height") or 900)
    options.update(
        {
            "user_agent": str(runtime.get("user_agent") or core_settings().provider_user_agent),
            "locale": str(runtime.get("language") or "en-US"),
            "timezone_id": str(runtime.get("timezone_id") or "UTC"),
            "viewport": {"width": width, "height": height},
            "screen": {"width": width, "height": height},
        }
    )
    return options


def _upstream_request_target(value: str) -> tuple[str, str] | None:
    parsed = urlparse(value)
    host = (parsed.hostname or "").lower()
    if host not in {"agent.minimax.io", "agent-stream.minimax.io"}:
        return None
    if not parsed.path.startswith(("/archon/", "/v1/", "/api/v0/")):
        return None
    return host, parsed.path


def _log_upstream_request(request: Any) -> None:
    target = _upstream_request_target(str(request.url))
    if target is None:
        return
    host, path = target
    logger.info(
        "minmax_official_browser_upstream_request method=%s host=%s path=%s",
        str(request.method or "GET").upper(),
        host,
        path,
    )


def _log_upstream_response(response: Any) -> None:
    target = _upstream_request_target(str(response.url))
    if target is None:
        return
    host, path = target
    content_type = str(response.headers.get("content-type") or "")[:120]
    logger.info(
        "minmax_official_browser_upstream_response host=%s path=%s status=%s content_type=%s",
        host,
        path,
        int(response.status),
        content_type,
    )


def _patchright_fingerprint_script(value: Any) -> str:
    runtime = value if isinstance(value, dict) else {}
    payload = json.dumps(runtime, ensure_ascii=True, separators=(",", ":"))
    return rf"""(() => {{
      const value = {payload};
      const define = (target, name, current) => {{
        if (current === undefined || current === null || current === '') return;
        try {{ Object.defineProperty(target, name, {{get: () => current, configurable: true}}); }}
        catch (_) {{}}
      }};
      define(Navigator.prototype, 'platform', value.platform);
      define(Navigator.prototype, 'language', value.language);
      define(Navigator.prototype, 'languages', value.languages);
      define(Navigator.prototype, 'hardwareConcurrency', value.hardware_concurrency);
      define(Navigator.prototype, 'deviceMemory', value.device_memory);
      const original = WebGLRenderingContext.prototype.getParameter;
      WebGLRenderingContext.prototype.getParameter = function(parameter) {{
        if (parameter === 37445 && value.webgl_vendor) return value.webgl_vendor;
        if (parameter === 37446 && value.webgl_renderer) return value.webgl_renderer;
        return original.call(this, parameter);
      }};
    }})()"""


async def _runtime_fingerprint(page: Any) -> dict[str, Any]:
    value = await page.evaluate(
        r"""() => {
          const gl = document.createElement('canvas').getContext('webgl');
          const debug = gl?.getExtension('WEBGL_debug_renderer_info');
          return {
            user_agent: navigator.userAgent,
            platform: navigator.platform || '',
            language: navigator.language || '',
            languages: Array.from(navigator.languages || []),
            hardware_concurrency: navigator.hardwareConcurrency || null,
            device_memory: navigator.deviceMemory || null,
            timezone_id: Intl.DateTimeFormat().resolvedOptions().timeZone || '',
            timezone_offset_minutes: new Date().getTimezoneOffset(),
            color_scheme: matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light',
            screen: {
              width: screen.width,
              height: screen.height,
              avail_width: screen.availWidth,
              avail_height: screen.availHeight,
              color_depth: screen.colorDepth,
              pixel_ratio: devicePixelRatio
            },
            webgl_vendor: debug ? gl.getParameter(debug.UNMASKED_VENDOR_WEBGL) : '',
            webgl_renderer: debug ? gl.getParameter(debug.UNMASKED_RENDERER_WEBGL) : ''
          };
        }"""
    )
    return value if isinstance(value, dict) else {}


def official_bridge_script() -> str:
    return _LOCATE_BRIDGE
