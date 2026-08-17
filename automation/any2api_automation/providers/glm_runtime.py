from __future__ import annotations

import json
import logging
import re
from collections.abc import Iterator
from contextlib import contextmanager
from copy import deepcopy
from typing import Any
from urllib.parse import urlparse

from patchright.sync_api import sync_playwright

from ..config import settings as core_settings
from ..lifecycle.browser import camoufox_config_from_options
from .glm_challenge import GlmAliyunChallenge
from .official_browser import (
    SCHEMA_VERSION,
    camoufox_launch_options,
    context_options,
)

logger = logging.getLogger("any2api_automation.providers.glm_runtime")

_CREATE_CHAT = r"""async input => {
  const runtime = window.__any2apiGlmOfficialRuntime;
  if (!runtime?.newChat) throw new Error('GLM official new-chat function is unavailable');
  const result = await runtime.newChat('default', input.chat, undefined);
  if (result?.err) throw new Error(String(result.err.detail || result.err.code || result.err));
  const chatId = String(result?.id || '');
  if (!chatId) throw new Error('GLM official new-chat function returned no chat id');
  history.replaceState(null, '', '/c/' + chatId);
  return chatId;
}"""

_START_COMPLETION = r"""async input => {
  const runtime = window.__any2apiGlmOfficialRuntime;
  if (!runtime?.requestContext || !runtime?.sign || !runtime?.completion) {
    throw new Error('GLM official completion runtime is unavailable');
  }
  const body = {...input.completion, chat_id: input.chatId};
  const requestContext = runtime.requestContext();
  const signed = runtime.sign(
    requestContext.sortedPayload,
    input.prompt,
    requestContext.timestamp
  );
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), input.timeoutMs);
  const response = await runtime.completion(
    localStorage.getItem('token') || '',
    body,
    location.origin + '/api/v2',
    controller,
    signed.signature,
    requestContext.urlParams + '&signature_timestamp=' + signed.timestamp,
    input.ticket
  );
  if (!(response instanceof Response)) {
    clearTimeout(timer);
    throw new Error(String(response?.detail || response?.message || response));
  }
  window.__any2apiGlmCompletionReader = response.body?.getReader() || null;
  window.__any2apiGlmCompletionTimer = timer;
  return {
    status: response.status,
    contentType: response.headers.get('content-type') || 'application/octet-stream'
  };
}"""

_READ_COMPLETION_CHUNK = r"""async () => {
  const reader = window.__any2apiGlmCompletionReader;
  if (!reader) return {done: true, body: ''};
  const result = await reader.read();
  if (result.done || !result.value) {
    clearTimeout(window.__any2apiGlmCompletionTimer);
    return {done: true, body: ''};
  }
  return {done: false, body: new TextDecoder().decode(result.value, {stream: true})};
}"""

_READ_RUNTIME_ASSET = r"""() => {
  const sourceUrl = [...document.scripts]
    .map(script => script.src)
    .find(value => /\/assets\/index-[A-Za-z0-9_-]+\.js(?:\?|$)/.test(value));
  if (!sourceUrl) throw new Error('GLM official runtime asset was not found');
  return sourceUrl;
}"""

_IMPORT_RUNTIME = r"""async input => {
  let source = input.source;
  const aliases = [
    `${input.names.new_chat} as __any2apiNewChat`,
    `${input.names.request_context} as __any2apiRequestContext`,
    `${input.names.sign} as __any2apiSign`,
    `${input.names.completion} as __any2apiCompletion`
  ].join(',');
  const pattern = /export\{([^}]*)\};?\s*$/;
  if (!pattern.test(source)) throw new Error('GLM official runtime export was not found');
  source = source.replace(pattern, (_all, current) => `export{${aliases},${current}};`);
  const moduleUrl = URL.createObjectURL(new Blob([source], {type: 'text/javascript'}));
  try {
    const module = await import(moduleUrl);
    window.__any2apiGlmOfficialRuntime = {
      newChat: module.__any2apiNewChat,
      requestContext: module.__any2apiRequestContext,
      sign: module.__any2apiSign,
      completion: module.__any2apiCompletion
    };
  } finally {
    URL.revokeObjectURL(moduleUrl);
  }
  return Object.values(window.__any2apiGlmOfficialRuntime)
    .every(value => typeof value === 'function');
}"""


class GlmOfficialBrowserTransport:
    def __init__(self, base_url: str) -> None:
        self.base_url = base_url.rstrip("/")

    def stream(
        self,
        credential: dict[str, Any],
        command: dict[str, Any],
        proxy_url: str,
        *,
        timeout_seconds: int,
    ) -> Iterator[dict[str, Any]]:
        _validate_command(command)
        execution = _execution_context(credential)
        backend = str(
            execution.get("backend")
            or credential.get("registration_backend")
            or "camoufox"
        )
        with _launch_browser(backend, execution, proxy_url) as launched:
            actual_backend, browser, camoufox_config = launched
            options = context_options(execution, actual_backend)
            storage_state = _storage_state(execution)
            if storage_state:
                options["storage_state"] = storage_state
            context = browser.new_context(**options)
            page = None
            try:
                token = _required(credential, "token", "access_token", "jwt")
                _add_credential_cookies(context, credential)
                page = context.new_page()
                page.set_default_timeout(timeout_seconds * 1000)
                challenge = GlmAliyunChallenge.for_chat()
                challenge.arm_official(page)
                page.goto(
                    self.base_url,
                    wait_until="domcontentloaded",
                    timeout=90_000,
                )
                page.evaluate(
                    "token => localStorage.setItem('token', token)",
                    token,
                )
                _load_official_runtime(page)
                chat_id = page.evaluate(_CREATE_CHAT, {"chat": command["chat"]})
                ticket = challenge.solve(page, timeout_seconds=timeout_seconds)
                metadata = page.evaluate(
                    _START_COMPLETION,
                    {
                        "chatId": chat_id,
                        "completion": command["completion"],
                        "prompt": command["prompt"],
                        "ticket": ticket,
                        "timeoutMs": timeout_seconds * 1000,
                    },
                )
                status = int(metadata.get("status") or 502)
                yield {"type": "status", "status": status}
                while True:
                    chunk = page.evaluate(_READ_COMPLETION_CHUNK)
                    if not isinstance(chunk, dict) or bool(chunk.get("done")):
                        break
                    body = str(chunk.get("body") or "")
                    if body:
                        yield {"type": "data", "data": body}
                yield {
                    "type": "credential_patch",
                    "data": _credential_patch(
                        context,
                        page,
                        actual_backend,
                        camoufox_config,
                    ),
                }
            except Exception as error:  # noqa: BLE001 - provider stream boundary
                logger.warning(
                    "glm_official_browser_stream_failed error_type=%s",
                    type(error).__name__,
                )
                if page is not None:
                    try:
                        yield {
                            "type": "credential_patch",
                            "data": _credential_patch(
                                context,
                                page,
                                actual_backend,
                                camoufox_config,
                            ),
                        }
                    except Exception as patch_error:  # noqa: BLE001 - preserve root failure
                        logger.warning(
                            "glm_official_browser_state_capture_failed error_type=%s",
                            type(patch_error).__name__,
                        )
                yield {
                    "type": "error",
                    "data": f"official browser stream failed ({type(error).__name__})",
                }
            finally:
                if page is not None:
                    try:
                        page.close()
                    except Exception:  # noqa: BLE001,S110 - browser teardown continues
                        pass
                context.close()


@contextmanager
def _launch_browser(
    backend: str,
    execution: dict[str, Any],
    proxy_url: str,
) -> Iterator[tuple[str, Any, dict[str, Any] | None]]:
    if backend == "camoufox":
        from camoufox.sync_api import Camoufox

        exact = execution.get("camoufox_config")
        prepared = camoufox_launch_options(
            exact if isinstance(exact, dict) else {},
            proxy_url,
        )
        manager = Camoufox(from_options=prepared)
        browser = manager.__enter__()
        try:
            yield "camoufox", browser, camoufox_config_from_options(prepared)
        finally:
            manager.__exit__(None, None, None)
        return
    playwright = sync_playwright().start()
    options: dict[str, Any] = {"headless": core_settings().registration_headless}
    if proxy_url:
        options["proxy"] = {"server": proxy_url}
    browser = playwright.chromium.launch(**options)
    try:
        yield "patchright", browser, None
    finally:
        browser.close()
        playwright.stop()


def _load_official_runtime(page: Any) -> None:
    source_url = str(page.evaluate(_READ_RUNTIME_ASSET) or "")
    response = page.context.request.get(source_url, timeout=60_000)
    if not response.ok:
        raise RuntimeError(
            f"GLM official runtime asset returned HTTP {response.status}"
        )
    source = response.text()
    names = discover_runtime_names(source)
    if not bool(
        page.evaluate(
            _IMPORT_RUNTIME,
            {"source": source, "names": names},
        )
    ):
        raise RuntimeError("GLM official runtime import was incomplete")


def discover_runtime_names(source: str) -> dict[str, str]:
    new_chat = _assignment_before(
        source,
        "/chats/new",
        r"(?:const|let|var)?\s*([A-Za-z_$][\w$]*)=async\(",
        2_000,
    )
    completion = _assignment_before(
        source,
        "X-Signature",
        r"(?:const|let|var)?\s*([A-Za-z_$][\w$]*)=async\(",
        4_000,
    )
    request_context = _assignment_before(
        source,
        "sortedPayload",
        r"(?:const|let|var)?\s*([A-Za-z_$][\w$]*)=\(\)=>\{",
        12_000,
    )
    sign_marker = source.find("5*60*1e3", source.find("sortedPayload"))
    if sign_marker < 0:
        raise RuntimeError("GLM official signature bucket marker was not found")
    sign = _assignment_before_index(
        source,
        sign_marker,
        r"(?:const|let|var)?\s*([A-Za-z_$][\w$]*)=\([A-Za-z_$][\w$]*,"
        r"[A-Za-z_$][\w$]*,[A-Za-z_$][\w$]*\)=>\{",
        12_000,
    )
    values = {
        "new_chat": new_chat,
        "request_context": request_context,
        "sign": sign,
        "completion": completion,
    }
    if len(set(values.values())) != len(values):
        raise RuntimeError("GLM official runtime discovery returned ambiguous functions")
    return values


def _assignment_before(
    source: str,
    marker: str,
    pattern: str,
    window: int,
) -> str:
    index = source.find(marker)
    if index < 0:
        raise RuntimeError(f"GLM official runtime marker was not found: {marker}")
    return _assignment_before_index(source, index, pattern, window)


def _assignment_before_index(
    source: str,
    index: int,
    pattern: str,
    window: int,
) -> str:
    prefix = source[max(0, index - window) : index]
    matches = list(re.finditer(pattern, prefix))
    if not matches:
        raise RuntimeError("GLM official runtime function was not found")
    return matches[-1].group(1)


def _validate_command(command: dict[str, Any]) -> None:
    if not isinstance(command.get("chat"), dict):
        raise TypeError("GLM transport command requires a chat object")
    if not isinstance(command.get("completion"), dict):
        raise TypeError("GLM transport command requires a completion object")
    if not str(command.get("prompt") or "").strip():
        raise ValueError("GLM transport command requires a signature prompt")


def _execution_context(credential: dict[str, Any]) -> dict[str, Any]:
    value = credential.get("browser_execution_context")
    if value is None:
        return {}
    if not isinstance(value, dict):
        raise TypeError("GLM browser execution context must be an object")
    if value.get("schema_version") != SCHEMA_VERSION:
        raise ValueError("GLM browser execution context schema is unsupported")
    return deepcopy(value)


def _storage_state(execution: dict[str, Any]) -> dict[str, Any] | None:
    value = execution.get("storage_state")
    if value is None:
        return None
    if not isinstance(value, dict):
        raise TypeError("GLM browser storage state must be an object")
    return _filter_storage_state(value)


def _filter_storage_state(state: dict[str, Any]) -> dict[str, Any]:
    cookies = state.get("cookies", [])
    origins = state.get("origins", [])
    if not isinstance(cookies, list) or not isinstance(origins, list):
        raise TypeError("GLM browser storage state collections must be arrays")
    filtered_cookies = [
        deepcopy(cookie)
        for cookie in cookies
        if isinstance(cookie, dict)
        and _allowed_host(str(cookie.get("domain") or ""))
    ]
    filtered_origins = []
    for origin in origins:
        if not isinstance(origin, dict):
            continue
        parsed = urlparse(str(origin.get("origin") or ""))
        if parsed.scheme == "https" and _allowed_host(parsed.hostname or ""):
            filtered_origins.append(deepcopy(origin))
    return {"cookies": filtered_cookies, "origins": filtered_origins}


def _allowed_host(value: str) -> bool:
    host = value.strip().lower().lstrip(".")
    return host == "z.ai" or host.endswith(".z.ai")


def _add_credential_cookies(context: Any, credential: dict[str, Any]) -> None:
    values = credential.get("cookies")
    if not isinstance(values, dict):
        return
    cookies = [
        {
            "name": str(name),
            "value": str(value),
            "domain": ".z.ai",
            "path": "/",
            "secure": True,
            "sameSite": "Lax",
        }
        for name, value in values.items()
        if re.fullmatch(r"[!#$%&'*+\-.^_`|~0-9A-Za-z]{1,128}", str(name))
        and str(value)
    ]
    if cookies:
        context.add_cookies(cookies)


def _credential_patch(
    context: Any,
    page: Any,
    backend: str,
    camoufox_config: dict[str, Any] | None,
) -> dict[str, Any]:
    try:
        storage = context.storage_state(indexed_db=True)
    except TypeError:
        storage = context.storage_state()
    runtime = page.evaluate(
        r"""() => ({
          user_agent: navigator.userAgent,
          platform: navigator.platform || '',
          language: navigator.language || '',
          languages: Array.from(navigator.languages || []),
          hardware_concurrency: navigator.hardwareConcurrency || null,
          device_memory: navigator.deviceMemory || null,
          timezone_id: Intl.DateTimeFormat().resolvedOptions().timeZone || '',
          timezone_offset_minutes: new Date().getTimezoneOffset(),
          screen: {
            width: screen.width,
            height: screen.height,
            avail_width: screen.availWidth,
            avail_height: screen.availHeight,
            color_depth: screen.colorDepth,
            pixel_ratio: devicePixelRatio
          }
        })"""
    )
    execution = {
        "schema_version": SCHEMA_VERSION,
        "backend": backend,
        "storage_state": _filter_storage_state(storage),
        "runtime_fingerprint": runtime,
    }
    if backend == "camoufox" and camoufox_config:
        execution["camoufox_config"] = deepcopy(camoufox_config)
    encoded = json.dumps(execution, ensure_ascii=True, separators=(",", ":")).encode()
    if len(encoded) > 2_000_000:
        raise RuntimeError("GLM browser execution context exceeds the credential limit")
    return {"browser_execution_context": execution}


def _required(source: dict[str, Any], *fields: str) -> str:
    for field in fields:
        value = str(source.get(field) or "").strip()
        if value:
            return value
    raise ValueError("GLM credential is incomplete")
