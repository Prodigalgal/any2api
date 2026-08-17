from __future__ import annotations

import hashlib
import json
import logging
import re
from collections.abc import Iterator
from contextlib import contextmanager
from copy import deepcopy
from datetime import UTC, datetime
from typing import Any
from urllib.parse import urlparse
from uuid import uuid4

from patchright.sync_api import sync_playwright

from ..config import settings as core_settings
from ..lifecycle.browser import camoufox_config_from_options
from .glm_challenge import GlmAliyunChallenge
from .official_browser import (
    SCHEMA_VERSION,
    camoufox_launch_options,
    context_options,
)
from .runtime_rules import (
    RuntimePlan,
    RuntimeRule,
    RuntimeRuleDiscoveryError,
    RuntimeRuleSelection,
    build_id,
    runtime_canary,
    successful_canary,
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
    location.origin + input.apiBase,
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

_READ_RUNTIME_ASSET = r"""markers => {
  const sourceUrl = [...document.scripts]
    .map(script => script.src)
    .find(value => value && markers.some(marker => value.includes(marker)));
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
        semantic_command: dict[str, Any],
        proxy_url: str,
        plan: RuntimePlan,
        *,
        timeout_seconds: int,
    ) -> Iterator[dict[str, Any]]:
        _validate_semantic_command(semantic_command)
        command = build_glm_command(
            semantic_command,
            _required(credential, "email", "user_id"),
        )
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
                selection, runtime_build_id, reports = _select_runtime(
                    page, plan
                )
                for report in reports:
                    yield {"type": "runtime_canary", **report}
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
                        "apiBase": selection.rules.endpoint_paths.get(
                            "apiBase", "/api/v2"
                        ),
                    },
                )
                status = int(metadata.get("status") or 502)
                yield {"type": "status", "status": status}
                data_seen = False
                while True:
                    chunk = page.evaluate(_READ_COMPLETION_CHUNK)
                    if not isinstance(chunk, dict) or bool(chunk.get("done")):
                        break
                    body = str(chunk.get("body") or "")
                    if body:
                        data_seen = True
                        yield {"type": "data", "data": body}
                if data_seen and 200 <= status < 300:
                    success_report = successful_canary(
                        plan, selection, runtime_build_id
                    )
                    if success_report is not None:
                        yield {"type": "runtime_canary", **success_report}
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


def _load_official_runtime(
    page: Any,
    selection: RuntimeRuleSelection,
) -> str:
    try:
        source_url = str(
            page.evaluate(
                _READ_RUNTIME_ASSET,
                list(selection.rules.build_asset_markers),
            )
            or ""
        )
    except Exception as error:
        raise RuntimeRuleDiscoveryError(
            f"GLM runtime asset discovery failed ({type(error).__name__})"
        ) from error
    response = page.context.request.get(
        source_url,
        timeout=selection.rules.canary_timeout_seconds * 1000,
    )
    if not response.ok:
        raise RuntimeError(
            f"GLM official runtime asset returned HTTP {response.status}"
        )
    source = response.text()
    runtime_build_id = build_id(
        [
            source_url.split("?", 1)[0].split("#", 1)[0],
            hashlib.sha256(source.encode()).hexdigest(),
        ]
    )
    try:
        names = discover_runtime_names(source, selection.rules)
        if not bool(
            page.evaluate(
                _IMPORT_RUNTIME,
                {"source": source, "names": names},
            )
        ):
            raise RuntimeError("GLM official runtime import was incomplete")
    except Exception as error:
        raise RuntimeRuleDiscoveryError(
            f"GLM runtime import failed ({type(error).__name__})"
        ) from error
    return runtime_build_id


def discover_runtime_names(
    source: str,
    rule: RuntimeRule | None = None,
) -> dict[str, str]:
    current = rule or default_runtime_rule()
    new_chat = _assignment_before_any(
        source,
        current.discovery_markers.get("newChat", ()),
        r"(?:const|let|var)?\s*([A-Za-z_$][\w$]*)=async\(",
        2_000,
    )
    completion = _assignment_before_any(
        source,
        current.discovery_markers.get("completion", ()),
        r"(?:const|let|var)?\s*([A-Za-z_$][\w$]*)=async\(",
        4_000,
    )
    request_context = _assignment_before_any(
        source,
        current.discovery_markers.get("requestContext", ()),
        r"(?:const|let|var)?\s*([A-Za-z_$][\w$]*)=\(\)=>\{",
        12_000,
    )
    context_markers = current.discovery_markers.get("requestContext", ())
    context_index = _first_marker_index(source, context_markers)
    sign_marker = _first_marker_index(
        source,
        current.discovery_markers.get("sign", ()),
        context_index,
    )
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


def _assignment_before_any(
    source: str,
    markers: tuple[str, ...],
    pattern: str,
    window: int,
) -> str:
    index = _first_marker_index(source, markers)
    if index < 0:
        raise RuntimeError("GLM official runtime marker was not found")
    return _assignment_before_index(source, index, pattern, window)


def _first_marker_index(
    source: str,
    markers: tuple[str, ...],
    start: int = 0,
) -> int:
    indexes = [source.find(marker, max(0, start)) for marker in markers]
    present = [index for index in indexes if index >= 0]
    return min(present) if present else -1


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


def _select_runtime(
    page: Any,
    plan: RuntimePlan,
) -> tuple[RuntimeRuleSelection, str, list[dict[str, Any]]]:
    reports: list[dict[str, Any]] = []
    if plan.candidate is not None:
        try:
            return (
                plan.candidate,
                _load_official_runtime(page, plan.candidate),
                reports,
            )
        except RuntimeRuleDiscoveryError as error:
            reports.append(runtime_canary(plan.candidate, "", "FAILED", str(error)))
    return plan.active, _load_official_runtime(page, plan.active), reports


def build_glm_command(
    command: dict[str, Any],
    email: str,
    timestamp_ms: int | None = None,
) -> dict[str, Any]:
    _validate_semantic_command(command)
    timestamp = (
        timestamp_ms
        if timestamp_ms is not None
        else round(datetime.now(UTC).timestamp() * 1000)
    )
    user_message_id = str(uuid4())
    prompt = _last_user_prompt(command["messages"])
    model = str(command["model"])
    effort = _reasoning_effort(command)
    thinking = _thinking_enabled(command, effort)
    web_search = _boolean_option(command, "web_search", False)
    message = {
        "id": user_message_id,
        "parentId": None,
        "role": "user",
        "content": prompt,
        "timestamp": timestamp // 1000,
        "childrenIds": [],
        "models": [model],
    }
    chat = {
        "id": "",
        "title": "New Chat",
        "params": {},
        "history": {
            "messages": {user_message_id: message},
            "currentId": user_message_id,
        },
        "tags": [],
        "flags": [],
        "features": [
            {"server": "tool_selector_h", "status": "hidden", "type": "tool_selector"}
        ],
        "mcp_servers": [],
        "enable_thinking": thinking,
        "reasoning_effort": effort,
        "auto_web_search": web_search,
        "message_version": 1,
        "extra": {},
        "timestamp": timestamp,
        "type": "default",
        "models": [model],
    }
    completion = {
        "stream": True,
        "model": model,
        "messages": _canonical_messages(command),
        "signature_prompt": prompt,
        "params": _generation_params(command),
        "extra": {},
        "features": {
            "image_generation": False,
            "web_search": False,
            "auto_web_search": web_search,
            "preview_mode": _boolean_option(command, "preview_mode", True),
            "flags": [],
            "vlm_tools_enable": False,
            "vlm_web_search_enable": False,
            "vlm_website_mode": False,
            "enable_thinking": thinking,
            "reasoning_effort": effort,
        },
        "variables": _variables(email, timestamp),
        "chat_id": "",
        "id": str(uuid4()),
        "current_user_message_id": user_message_id,
        "current_user_message_parent_id": None,
        "background_tasks": {"title_generation": True, "tags_generation": True},
    }
    return {"chat": chat, "completion": completion, "prompt": prompt}


def _validate_semantic_command(command: dict[str, Any]) -> None:
    if not isinstance(command, dict) or command.get("schemaVersion") != 1:
        raise ValueError("GLM semantic command schema is unsupported")
    if not str(command.get("model") or "").strip():
        raise ValueError("GLM semantic command requires a model")
    if not isinstance(command.get("messages"), list):
        raise TypeError("GLM semantic command messages must be an array")
    for name in ("generation", "reasoning", "providerOptions", "controls"):
        if not isinstance(command.get(name), dict):
            raise TypeError(f"GLM semantic command {name} must be an object")


def _last_user_prompt(messages: list[Any]) -> str:
    candidates = [
        _content(message.get("content"))
        for message in messages
        if isinstance(message, dict) and _role(message.get("role")) == "user"
    ]
    candidates = [value for value in candidates if value]
    if not candidates:
        raise ValueError("GLM request requires a user message")
    return candidates[-1]


def _canonical_messages(command: dict[str, Any]) -> list[dict[str, str]]:
    output: list[dict[str, str]] = []
    tools = command.get("tools") or []
    if tools:
        output.append(
            {
                "role": "system",
                "content": "Available function tools:\n"
                + json.dumps(tools, ensure_ascii=True, separators=(",", ":")),
            }
        )
    for source in command["messages"]:
        if not isinstance(source, dict):
            continue
        role = _role(source.get("role"))
        content = _content(source.get("content"))
        if role == "assistant" and isinstance(source.get("tool_calls"), list):
            content += "\n" + json.dumps(source["tool_calls"], separators=(",", ":"))
        output.append({"role": role, "content": content})
    return output


def _generation_params(command: dict[str, Any]) -> dict[str, float | int]:
    generation = command["generation"]
    output: dict[str, float | int] = {}
    for field in ("temperature", "top_p"):
        value = generation.get(field)
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            output[field] = float(value)
    for source in ("max_completion_tokens", "max_output_tokens", "max_tokens"):
        value = generation.get(source)
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            output["max_tokens"] = int(value)
            break
    return output


def _reasoning_effort(command: dict[str, Any]) -> str:
    options = command["providerOptions"]
    value = options.get("reasoning_effort") or command["reasoning"].get("effort")
    value = value or command["controls"].get("reasoning_effort") or "max"
    normalized = str(value).strip().lower()
    return normalized or "max"


def _thinking_enabled(command: dict[str, Any], effort: str) -> bool:
    value = command["providerOptions"].get("enable_thinking")
    return value if isinstance(value, bool) else effort not in {"none", "minimal", "low"}


def _boolean_option(command: dict[str, Any], name: str, fallback: bool) -> bool:
    value = command["providerOptions"].get(name, command["controls"].get(name))
    return value if isinstance(value, bool) else fallback


def _variables(email: str, timestamp_ms: int) -> dict[str, str]:
    current = datetime.fromtimestamp(timestamp_ms / 1000).astimezone()
    return {
        "{{USER_NAME}}": email,
        "{{USER_LOCATION}}": "Unknown",
        "{{CURRENT_DATETIME}}": current.strftime("%Y-%m-%d %H:%M:%S"),
        "{{CURRENT_DATE}}": current.date().isoformat(),
        "{{CURRENT_TIME}}": current.time().replace(microsecond=0).isoformat(),
        "{{CURRENT_WEEKDAY}}": current.strftime("%A").upper(),
        "{{CURRENT_TIMEZONE}}": str(current.tzinfo),
        "{{USER_LANGUAGE}}": "en-US",
    }


def _role(value: Any) -> str:
    role = str(value or "user").lower()
    if role == "developer":
        return "system"
    return role if role in {"assistant", "system", "tool"} else "user"


def _content(value: Any) -> str:
    if isinstance(value, str):
        return value
    if not isinstance(value, list):
        return ""
    return "\n".join(
        str(part if isinstance(part, str) else part.get("text") or "")
        for part in value
        if isinstance(part, (str, dict))
    )


def default_runtime_rule() -> RuntimeRule:
    return RuntimeRule(
        schema_version=1,
        session_max_age_seconds=900,
        canary_timeout_seconds=60,
        build_asset_markers=("/assets/index-",),
        discovery_markers={
            "newChat": ("/chats/new",),
            "completion": ("X-Signature",),
            "requestContext": ("sortedPayload",),
            "sign": ("5*60*1e3",),
        },
        capabilities={},
        endpoint_paths={"chat": "/api/v2/chat/completions", "apiBase": "/api/v2"},
    )


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
