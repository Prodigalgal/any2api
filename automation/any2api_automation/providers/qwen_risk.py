from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import logging
import queue
import re
import threading
from collections import OrderedDict
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any, Literal
from urllib.parse import urljoin, urlparse
from uuid import uuid4

from curl_cffi import requests as curl_requests
from fastapi import APIRouter, Depends, HTTPException
from patchright.async_api import async_playwright
from patchright.sync_api import Browser, BrowserContext, Page, sync_playwright
from pydantic import BaseModel, Field, PrivateAttr, model_validator

from ..browser_transport import manager as browser_session_manager
from ..config import settings as global_settings
from ..security import require_internal_token
from .qwen_fingerprint import (
    camoufox_launch_options,
    finalize_patchright_fingerprint,
    new_qwen_fingerprint,
    normalize_qwen_fingerprint,
    patchright_cdp_commands,
    patchright_client_hints,
    qwen_fingerprint_digest,
)
from .qwen_inference_challenge import QwenInferenceChallengeSolver
from .qwen_session import (
    browser_state_cookie_map,
    browser_state_digest,
    normalize_browser_state,
    playwright_storage_state,
)
from .qwen_settings import settings

logger = logging.getLogger(__name__)
QWEN_TIMEZONE = timezone(timedelta(hours=8))


def _browser_major(profile: str) -> str:
    match = re.fullmatch(r"chrome(\d{2,3})", profile.strip().lower())
    if not match:
        raise ValueError("Qwen risk browser profile must be chrome followed by a major version")
    return match.group(1)


def _client_hints(profile: str) -> dict[str, str]:
    major = _browser_major(profile)
    return {
        "sec-ch-ua": (f'"Not;A=Brand";v="99", "Chromium";v="{major}", "Google Chrome";v="{major}"'),
        "sec-ch-ua-mobile": "?0",
        "sec-ch-ua-platform": '"Windows"',
    }


def _fingerprint_script(profile: str) -> str:
    major = _browser_major(profile)
    return f"""
    (() => {{
      const brands = [
        {{brand: 'Not;A=Brand', version: '99'}},
        {{brand: 'Chromium', version: '{major}'}},
        {{brand: 'Google Chrome', version: '{major}'}}
      ];
      const userAgentData = {{
        brands,
        mobile: false,
        platform: 'Windows',
        getHighEntropyValues: async (hints) => ({{
          architecture: 'x86',
          bitness: '64',
          brands,
          fullVersionList: brands.map(value => ({{
            brand: value.brand,
            version: value.version + '.0.0.0'
          }})),
          mobile: false,
          model: '',
          platform: 'Windows',
          platformVersion: '10.0.0',
          uaFullVersion: '{major}.0.0.0',
          wow64: false
        }})
      }};
      for (const target of [Navigator.prototype, navigator]) {{
        try {{
          Object.defineProperty(target, 'platform', {{
            configurable: true,
            get: () => 'Win32'
          }});
          Object.defineProperty(target, 'userAgentData', {{
            configurable: true,
            get: () => userAgentData
          }});
        }} catch (_) {{}}
      }}
    }})();
    """


class RiskHeadersRequest(BaseModel):
    url: str
    method: str = "GET"
    body: str = ""


class NativeBrowserRequest(BaseModel):
    _browser_fingerprint_requires_persistence: bool = PrivateAttr(default=False)

    method: Literal["GET", "POST"] = "POST"
    path: str = Field(min_length=1, max_length=2048)
    body: str = Field(default="", max_length=2 << 20)
    bearer_token: str = Field(min_length=20, max_length=16_384)
    account_id: str = Field(default="", max_length=64)
    cookies: dict[str, str] = Field(default_factory=dict)
    browser_state: dict[str, Any] = Field(default_factory=dict)
    browser_fingerprint: dict[str, Any] = Field(default_factory=dict)
    transport_session_id: str = Field(default="", max_length=32)
    referer_path: str = Field(default="/", min_length=1, max_length=2048)
    timeout_seconds: int = Field(default=300, ge=1, le=300)

    @model_validator(mode="after")
    def validate_paths(self) -> NativeBrowserRequest:
        if not re.fullmatch(r"/api/v[12]/[A-Za-z0-9_./?=&%:-]+", self.path):
            raise ValueError("Qwen browser transport path is not allowed")
        if not re.fullmatch(r"/(?:c(?:/[A-Za-z0-9_-]+)?|)", self.referer_path):
            raise ValueError("Qwen browser transport referer is not allowed")
        if len(self.cookies) > 128:
            raise ValueError("Qwen browser transport received too many cookies")
        if self.account_id and not re.fullmatch(
            r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
            r"[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}",
            self.account_id,
        ):
            raise ValueError("Qwen browser transport received an invalid account id")
        if self.transport_session_id and not re.fullmatch(
            r"[0-9a-f]{32}", self.transport_session_id
        ):
            raise ValueError("Qwen browser transport received an invalid transport session id")
        if any(
            not re.fullmatch(r"[!#$%&'*+.^_`|~0-9A-Za-z-]{1,256}", name)
            or len(value) > 8_192
            or "\r" in value
            or "\n" in value
            for name, value in self.cookies.items()
        ):
            raise ValueError("Qwen browser transport received an invalid cookie")
        try:
            self.browser_state = normalize_browser_state(
                self.browser_state, settings().qwen_base_url
            )
            raw_fingerprint = self.browser_fingerprint
            normalized_fingerprint = normalize_qwen_fingerprint(raw_fingerprint)
            self._browser_fingerprint_requires_persistence = (
                bool(raw_fingerprint) and raw_fingerprint != normalized_fingerprint
            )
            self.browser_fingerprint = normalized_fingerprint
        except TypeError as error:
            raise ValueError(str(error)) from error
        return self


@dataclass
class _Task:
    url: str
    method: str
    body: str
    event: threading.Event = field(default_factory=threading.Event)
    result: dict[str, str] | None = None
    error: BaseException | None = None


class QwenRiskHeaderProvider:
    """Owns Qwen's Baxia runtime on one browser thread."""

    def __init__(self) -> None:
        self._queue: queue.Queue[_Task] = queue.Queue()
        self._thread: threading.Thread | None = None
        self._thread_lock = threading.Lock()
        self._frontend_version = ""

    def get(self, url: str, method: str, body: str, timeout: float = 60.0) -> dict[str, str]:
        base_url = settings().qwen_base_url.rstrip("/")
        target = urljoin(f"{base_url}/", url)
        parsed = urlparse(target)
        expected = urlparse(base_url)
        if parsed.scheme != "https" or parsed.netloc != expected.netloc:
            raise ValueError("risk-header target must be an HTTPS Qwen URL")
        task = _Task(url=target, method=method.upper(), body=body)
        self._ensure_thread()
        self._queue.put(task)
        if not task.event.wait(timeout):
            raise TimeoutError("timed out while computing Qwen risk headers")
        if task.error is not None:
            raise RuntimeError(f"Qwen risk-header generation failed: {task.error}") from task.error
        return task.result or {}

    def status(self) -> dict[str, Any]:
        return {
            "ready": bool(self._thread and self._thread.is_alive()),
            "queue_depth": self._queue.qsize(),
            "mode": "per_request_browser",
        }

    def _ensure_thread(self) -> None:
        with self._thread_lock:
            if self._thread and self._thread.is_alive():
                return
            self._thread = threading.Thread(target=self._run, name="qwen-risk-headers", daemon=True)
            self._thread.start()

    def _run(self) -> None:
        try:
            with sync_playwright() as playwright:
                config = settings()
                browser = playwright.chromium.launch(headless=config.qwen_risk_headless)
                context = browser.new_context(
                    locale="zh-CN",
                    timezone_id="Asia/Shanghai",
                    viewport={"width": 1440, "height": 900},
                    user_agent=config.qwen_risk_user_agent,
                    extra_http_headers={
                        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
                        **_client_hints(config.qwen_risk_browser_profile),
                    },
                )
                fingerprint_script = _fingerprint_script(config.qwen_risk_browser_profile)
                context.add_init_script(fingerprint_script)
                page = context.new_page()
                page.add_init_script(fingerprint_script)
                cdp = context.new_cdp_session(page)
                cdp.send(
                    "Page.addScriptToEvaluateOnNewDocument",
                    {"source": fingerprint_script},
                )
                page.set_default_timeout(45_000)
                self._load(page)
                self._serve(page, context, browser)
        except Exception as exc:
            logger.exception("Qwen risk browser thread stopped")
            self._fail_pending(exc)

    def _load(self, page: Page) -> None:
        page.goto(settings().qwen_base_url, wait_until="domcontentloaded", timeout=60_000)
        page.wait_for_function(
            r"""() => performance.getEntriesByType("resource").some(
                entry => /\/sd\/baxia\/[\d.]+\/baxiaCommon\.js/.test(entry.name)
            )""",
            timeout=45_000,
        )
        page.wait_for_timeout(2_000)
        self._frontend_version = page.evaluate(
            r"""() => {
                for (const entry of performance.getEntriesByType("resource")) {
                    const match = entry.name.match(/qwen-chat-fe\/([^/]+)\/js\/main\.js/);
                    if (match) return match[1];
                }
                return "";
            }"""
        )
        if not self._frontend_version:
            raise RuntimeError("could not derive the current Qwen frontend version")

    def _serve(self, page: Page, context: BrowserContext, browser: Browser) -> None:
        try:
            while True:
                task = self._queue.get()
                try:
                    task.result = self._compute(page, task)
                except Exception as exc:  # noqa: BLE001 - task errors must cross the thread boundary
                    task.error = exc
                    try:
                        self._load(page)
                    except Exception:
                        logger.exception("Qwen risk browser reload failed")
                finally:
                    task.event.set()
        finally:
            context.close()
            browser.close()

    def _compute(self, page: Page, task: _Task) -> dict[str, str]:
        payload = json.dumps(
            {"url": task.url, "method": task.method, "body": task.body},
            ensure_ascii=True,
            separators=(",", ":"),
        )
        script = f"""(() => {{
            const request = {payload};
            const options = {{method: request.method, headers: {{"Content-Type": "application/json"}}}};
            if (request.method !== "GET" && request.method !== "HEAD") {{
                options.body = request.body || "{{}}";
            }}
            fetch(request.url, options).catch(() => {{}});
            if (document.currentScript) document.currentScript.remove();
        }})();"""
        for _ in range(3):
            page.route(task.url, lambda route: route.abort(), times=1)
            try:
                with page.expect_request(
                    lambda request: (
                        request.url == task.url and request.method.upper() == task.method
                    ),
                    timeout=20_000,
                ) as request_info:
                    page.add_script_tag(content=script)
                captured = {
                    key.lower(): value for key, value in request_info.value.all_headers().items()
                }
            finally:
                page.unroute(task.url)
            allowed = (
                "bx-ua",
                "bx-umidtoken",
                "bx-v",
                "version",
                "user-agent",
                "sec-ch-ua",
                "sec-ch-ua-mobile",
                "sec-ch-ua-platform",
            )
            result = {key: captured[key] for key in allowed if captured.get(key)}
            if result.get("bx-v"):
                expected = settings().qwen_risk_user_agent
                if result.get("user-agent") != expected:
                    raise RuntimeError("Qwen risk browser emitted an inconsistent User-Agent")
                result["version"] = self._frontend_version
                return result
            page.wait_for_timeout(1_500)
        raise RuntimeError("Baxia did not attach bx-v after three attempts")

    def _fail_pending(self, error: BaseException) -> None:
        while True:
            try:
                task = self._queue.get_nowait()
            except queue.Empty:
                return
            task.error = error
            task.event.set()


@dataclass
class _AccountBrowserSession:
    key: str
    context: Any
    page: Any
    frontend_version: str = ""
    input_digest: str = ""
    state_digest: str = ""
    cdp_session: Any | None = None
    backend: str = "patchright"
    browser_profile: str = ""
    user_agent: str = ""
    browser_fingerprint: dict[str, Any] = field(default_factory=dict)
    fingerprint_digest: str = ""
    proxy_url: str = ""
    proxy_binding_id: str = ""
    browser_manager: Any | None = None


class QwenNativeBrowserTransport:
    """Executes protected Qwen requests with one isolated context per account."""

    def __init__(self) -> None:
        self._lock = asyncio.Lock()
        self._request_lock = asyncio.Lock()
        self._playwright: Any | None = None
        self._browser: Any | None = None
        self._browser_profile = ""
        self._user_agent = ""
        self._challenge_solver = QwenInferenceChallengeSolver()
        self._sessions: OrderedDict[str, _AccountBrowserSession] = OrderedDict()

    async def fetch(self, request: NativeBrowserRequest) -> dict[str, Any]:
        async with (
            self._request_lock,
            self._transport_proxy_lease(request.transport_session_id) as proxy_binding,
        ):
            session = await self._session_for(request, proxy_binding)
            result, body = await self._evaluate(session, request)
            punish_url = _qwen_punish_url(body)
            if punish_url:
                try:
                    await self._recover_from_challenge(session, punish_url)
                except RuntimeError as error:
                    logger.warning(
                        "qwen_native_browser_challenge_unresolved error_type=%s",
                        type(error).__name__,
                    )
                    credential_patch = await self._credential_patch(session, request)
                    challenge_body = json.dumps(
                        {
                            "ret": ["FAIL_SYS_USER_VALIDATE"],
                            "message": "Qwen anti-bot challenge could not be cleared",
                        },
                        ensure_ascii=True,
                        separators=(",", ":"),
                    ).encode()
                    result = {
                        "status": 403,
                        "contentType": "application/json",
                        "requestId": str(uuid4()),
                        "retryAfter": "300",
                        "bodyBase64": base64.b64encode(challenge_body).decode(),
                    }
                    return self._response(request, result, challenge_body, credential_patch)
                result, body = await self._evaluate(session, request)
            credential_patch = await self._credential_patch(session, request)
            return self._response(request, result, body, credential_patch)

    async def _evaluate(
        self,
        session: _AccountBrowserSession,
        request: NativeBrowserRequest,
    ) -> tuple[dict[str, Any], bytes]:
        await self._prepare_authenticated_surface(session, request)
        maximum = global_settings().browser_transport_max_buffered_bytes
        payload = {
            "url": settings().qwen_base_url.rstrip("/") + request.path,
            "path": request.path,
            "method": request.method,
            "body": request.body,
            "bearerToken": request.bearer_token,
            "useBearer": True,
            "referrer": settings().qwen_base_url.rstrip("/") + request.referer_path,
            "timeoutMs": request.timeout_seconds * 1000,
            "version": session.frontend_version,
            "requestId": str(uuid4()),
            "maximumBytes": maximum,
            "timezone": datetime.now(QWEN_TIMEZONE).strftime("%a %b %d %Y %H:%M:%S GMT%z"),
            "browserProfile": session.browser_profile,
            "proxyUrl": session.proxy_url,
        }
        result, body = await self._fetch_in_main_world(session, payload)
        if len(body) > maximum:
            raise RuntimeError("Qwen browser response exceeds the buffered byte limit")
        return result, body

    async def _fetch_in_main_world(
        self,
        session: _AccountBrowserSession,
        payload: dict[str, Any],
    ) -> tuple[dict[str, Any], bytes]:
        encoded = base64.b64encode(
            json.dumps(payload, ensure_ascii=True, separators=(",", ":")).encode()
        ).decode()
        script = f"""(() => {{
          const bytes = Uint8Array.from(atob('{encoded}'), value => value.charCodeAt(0));
          const request = JSON.parse(new TextDecoder().decode(bytes));
          const headers = {{
            'Accept': request.path.includes('/chat/completions')
              ? 'application/json'
              : 'application/json, text/plain, */*',
            'Content-Type': 'application/json',
            'source': 'web',
            'Timezone': request.timezone,
            'X-Request-Id': request.requestId,
            'version': request.version
          }};
          if (request.useBearer) headers['Authorization'] = 'Bearer ' + request.bearerToken;
          if (request.path.includes('/chat/completions')) headers['X-Accel-Buffering'] = 'no';
          fetch(request.url, {{
            method: request.method,
            headers,
            body: request.method === 'GET' ? undefined : request.body,
            credentials: 'same-origin',
            cache: 'no-store',
            referrer: request.referrer
          }}).catch(() => {{}});
          if (document.currentScript) document.currentScript.remove();
        }})();"""
        target_url = str(payload["url"])
        method = str(payload["method"])
        request_id = str(payload["requestId"])
        probe_aborted = asyncio.Event()

        # Baxia only hooks the page's main world; abort that probe before the same-process
        # curl session sends the captured browser-shaped request and consumes its stream.
        async def abort_probe(route: Any) -> None:
            try:
                await route.abort()
            finally:
                probe_aborted.set()

        await session.page.route(target_url, abort_probe, times=1)
        try:
            async with session.page.expect_request(
                lambda request: (
                    request.url == target_url
                    and request.method == method
                    and request.headers.get("x-request-id") == request_id
                ),
                timeout=30_000,
            ) as request_info:
                await session.page.add_script_tag(content=script)
            browser_request = await request_info.value
            await asyncio.wait_for(probe_aborted.wait(), timeout=10)
        finally:
            await session.page.unroute(target_url)
        request_headers = await browser_request.all_headers()
        baxia_headers = {"bx-ua", "bx-umidtoken", "bx-v"}
        if not baxia_headers.issubset(request_headers):
            logger.warning(
                "qwen_native_browser_baxia_headers_missing path=%s missing=%s",
                payload["path"],
                sorted(baxia_headers.difference(request_headers)),
            )
        return await asyncio.to_thread(_send_qwen_request, payload, request_headers)

    async def _prepare_authenticated_surface(
        self,
        session: _AccountBrowserSession,
        request: NativeBrowserRequest,
    ) -> None:
        base_url = settings().qwen_base_url.rstrip("/")
        credential_cookies = [
            {"name": name, "value": value, "url": base_url}
            for name, value in request.cookies.items()
        ]
        if credential_cookies and not request.browser_state:
            await session.context.add_cookies(credential_cookies)
        logger.info(
            "qwen_native_browser_surface path=%s auth_mode=%s credential_cookies=%s",
            request.referer_path,
            "cookie" if request.cookies else "bearer_fallback",
            len(request.cookies),
        )
        await session.page.evaluate(
            "token => localStorage.setItem('token', token)", request.bearer_token
        )
        target = base_url + request.referer_path
        current = urlparse(session.page.url)
        desired = urlparse(target)
        if current.path != desired.path:
            await session.page.goto(target, wait_until="domcontentloaded", timeout=60_000)
            await session.page.wait_for_function(
                "() => localStorage.getItem('token') && document.readyState !== 'loading'",
                timeout=30_000,
            )
            await session.page.wait_for_timeout(750)
        await self._ensure_baxia_ready(session)

    async def _ensure_baxia_ready(self, session: _AccountBrowserSession) -> None:
        await session.page.evaluate(
            r"""() => {
              const baxia = window.__baxia__;
              if (window.baxiaCommon && baxia?.baxiaPromptInit && !window.baxiaInitialized) {
                const protectedPaths = [
                  '/api/chat/completions', '/api/chats/new', '/api/v2/chats',
                  '/api/v2/chat/completions', '/api/v2/files/getstsToken',
                  '/api/v2/files/getfilelink', '/api/v2/files/parse'
                ];
                window.baxiaCommon.init({
                  appendTo: 'header',
                  uabOptions: {location: 'sea'},
                  checkApiPath: path => protectedPaths.some(value => path.includes(value)),
                  showCallback: () => {},
                  hideCallback: () => {},
                  paramstype: ['uab', 'umid'],
                  autoSize: true
                });
                window.baxiaInitialized = true;
              }
            }"""
        )
        await session.page.wait_for_function(
            r"""() => {
              const module = window.__baxia__?.getFYModule;
              return Boolean(
                window.baxiaCommon && window.__baxia__?.baxiaPromptInit &&
                window.baxiaInitialized && module?.getUidToken?.call(module)
              );
            }""",
            timeout=30_000,
        )

    async def _credential_patch(
        self,
        session: _AccountBrowserSession,
        request: NativeBrowserRequest,
    ) -> dict[str, Any]:
        base_url = settings().qwen_base_url.rstrip("/")
        state = normalize_browser_state(
            await session.context.storage_state(indexed_db=True), base_url
        )
        digest = browser_state_digest(state, base_url)
        session.state_digest = digest
        if (
            digest == browser_state_digest(request.browser_state, base_url)
            and qwen_fingerprint_digest(request.browser_fingerprint) == session.fingerprint_digest
            and not request._browser_fingerprint_requires_persistence
        ):
            return {}
        cookies = browser_state_cookie_map(state, base_url)
        return {
            "browser_state": state,
            "cookies": cookies,
            "cookie": "; ".join(f"{name}={value}" for name, value in cookies.items()),
            "user_agent": session.user_agent or self._user_agent,
            "browser_profile": session.browser_profile or self._browser_profile,
            "browser_fingerprint": normalize_qwen_fingerprint(session.browser_fingerprint),
        }

    def _response(
        self,
        request: NativeBrowserRequest,
        result: dict[str, Any],
        body: bytes,
        credential_patch: dict[str, Any],
    ) -> dict[str, Any]:
        content_type = str(result.get("contentType") or "application/octet-stream")
        logger.info(
            "qwen_native_browser_response path=%s status=%s content_type=%s bytes=%s",
            request.path,
            result.get("status"),
            content_type,
            len(body),
        )
        if "/chat/completions" in request.path and b"data:" not in body:
            code = _qwen_failure_code(body) or "unknown"
            logger.warning("qwen_native_browser_unexpected_completion code=%s", code)
        return {
            "status": int(result.get("status") or 502),
            "content_type": content_type,
            "headers": {
                "x-request-id": str(result.get("requestId") or ""),
                "retry-after": str(result.get("retryAfter") or ""),
            },
            "body_base64": str(result.get("bodyBase64") or ""),
            "credential_patch": credential_patch,
            "transport_mode": "native_browser_buffered",
        }

    async def _recover_from_challenge(
        self,
        session: _AccountBrowserSession,
        punish_url: str,
    ) -> None:
        try:
            outcome = await self._challenge_solver.solve(session.page, punish_url)
            logger.info(
                "qwen_native_browser_challenge_cleared attempts=%s diagnostic=%s",
                outcome.attempts,
                outcome.diagnostic,
            )
        finally:
            await self._load_page_runtime(session)

    async def _session_for(
        self,
        request: NativeBrowserRequest,
        proxy_binding: tuple[str, str] | None = None,
    ) -> _AccountBrowserSession:
        account_key = (
            request.account_id or hashlib.sha256(request.bearer_token.encode()).hexdigest()
        )
        incoming_digest = browser_state_digest(request.browser_state, settings().qwen_base_url)
        session = self._sessions.get(account_key)
        fingerprint = request.browser_fingerprint or (
            session.browser_fingerprint
            if session is not None and session.browser_fingerprint
            else new_qwen_fingerprint("camoufox")
        )
        incoming_fingerprint_digest = qwen_fingerprint_digest(fingerprint)
        proxy_url, proxy_binding_id = (
            proxy_binding
            if proxy_binding is not None
            else await self._proxy_binding(request.transport_session_id)
        )
        is_closed = getattr(session.page, "is_closed", None) if session is not None else None
        page_closed = session is not None and callable(is_closed) and is_closed()
        if session is not None and page_closed:
            await self._close_session(session)
            self._sessions.pop(account_key, None)
            session = None
        known_digests = (
            {session.input_digest, session.state_digest} if session is not None else set()
        )
        if session is not None and incoming_digest and incoming_digest not in known_digests:
            await self._close_session(session)
            self._sessions.pop(account_key, None)
            session = None
        if session is not None and (
            session.fingerprint_digest != incoming_fingerprint_digest
            or session.proxy_binding_id != proxy_binding_id
        ):
            await self._close_session(session)
            self._sessions.pop(account_key, None)
            session = None
        if session is None:
            session = await self._new_session(
                account_key,
                request,
                incoming_digest,
                fingerprint,
                proxy_url,
                proxy_binding_id,
            )
            self._sessions[account_key] = session
        elif incoming_digest:
            session.input_digest = incoming_digest
        self._sessions.move_to_end(account_key)
        while len(self._sessions) > settings().qwen_risk_session_cache_size:
            _, expired = self._sessions.popitem(last=False)
            await self._close_session(expired)
        return session

    async def _new_session(
        self,
        account_key: str,
        request: NativeBrowserRequest,
        state_digest: str,
        fingerprint: dict[str, Any],
        proxy_url: str,
        proxy_binding_id: str,
    ) -> _AccountBrowserSession:
        base_url = settings().qwen_base_url.rstrip("/")
        normalized_fingerprint = normalize_qwen_fingerprint(fingerprint)
        backend = str(normalized_fingerprint["backend"])
        options: dict[str, Any] = {"service_workers": "block"}
        storage_state = playwright_storage_state(request.browser_state, base_url)
        if storage_state:
            options["storage_state"] = storage_state
        browser_manager = None
        browser = None
        if backend == "patchright":
            await self._ensure_ready()
            browser = self._browser
            viewport = normalized_fingerprint["viewport"]
            screen = normalized_fingerprint["screen"]
            options.update(
                {
                    "locale": str(normalized_fingerprint["locale"]),
                    "timezone_id": str(normalized_fingerprint["timezone_id"]),
                    "viewport": {
                        "width": int(viewport["width"]),
                        "height": int(viewport["height"]),
                    },
                    "screen": {
                        "width": int(screen["width"]),
                        "height": int(screen["height"]),
                    },
                    "device_scale_factor": float(normalized_fingerprint["device_scale_factor"]),
                    "color_scheme": str(normalized_fingerprint["color_scheme"]),
                    "user_agent": str(normalized_fingerprint["user_agent"]),
                    "extra_http_headers": {
                        "Accept-Language": str(normalized_fingerprint["accept_language"]),
                        **patchright_client_hints(normalized_fingerprint),
                    },
                }
            )
            if proxy_url:
                options["proxy"] = {"server": proxy_url}
        else:
            from camoufox.async_api import AsyncCamoufox

            prepared = await asyncio.to_thread(
                camoufox_launch_options,
                normalized_fingerprint,
                headless=settings().qwen_risk_headless,
                proxy_url=proxy_url,
            )
            browser_manager = AsyncCamoufox(from_options=prepared)
            browser = await browser_manager.__aenter__()
        if browser is None:
            raise RuntimeError("Qwen browser runtime is unavailable")
        context = None
        try:
            context = await browser.new_context(**options)
            credential_cookies = [
                {"name": name, "value": value, "url": base_url}
                for name, value in request.cookies.items()
            ]
            if credential_cookies and not request.browser_state:
                await context.add_cookies(credential_cookies)
            page = await context.new_page()
            cdp = None
            if backend == "patchright":
                cdp = await context.new_cdp_session(page)
                for method, parameters in patchright_cdp_commands(normalized_fingerprint):
                    await cdp.send(method, parameters)
            session = _AccountBrowserSession(
                account_key,
                context,
                page,
                input_digest=state_digest,
                state_digest=state_digest,
                cdp_session=cdp,
                backend=backend,
                browser_profile=str(normalized_fingerprint["browser_profile"]),
                user_agent=str(normalized_fingerprint["user_agent"]),
                browser_fingerprint=normalized_fingerprint,
                fingerprint_digest=qwen_fingerprint_digest(normalized_fingerprint),
                proxy_url=proxy_url,
                proxy_binding_id=proxy_binding_id,
                browser_manager=browser_manager,
            )
            await self._load_page_runtime(session)
            return session
        except Exception:
            if context is not None:
                await context.close()
            if browser_manager is not None:
                await browser_manager.__aexit__(None, None, None)
            raise

    async def _proxy_binding(self, transport_session_id: str) -> tuple[str, str]:
        if not transport_session_id:
            return "", ""

        def resolve() -> tuple[str, str]:
            with browser_session_manager.lease(transport_session_id) as entry:
                return entry.proxy_url, entry.binding_id

        try:
            return await asyncio.to_thread(resolve)
        except KeyError as error:
            raise RuntimeError("Qwen transport proxy session is unavailable") from error

    @asynccontextmanager
    async def _transport_proxy_lease(self, transport_session_id: str):
        if not transport_session_id:
            yield ("", "")
            return
        lease = browser_session_manager.lease(transport_session_id)
        try:
            entry = await asyncio.to_thread(lease.__enter__)
        except KeyError as error:
            raise RuntimeError("Qwen transport proxy session is unavailable") from error
        try:
            yield (entry.proxy_url, entry.binding_id)
        finally:
            await asyncio.to_thread(lease.__exit__, None, None, None)

    async def close(self) -> None:
        async with self._lock:
            await self._close_unlocked()

    async def _close_session(self, session: _AccountBrowserSession) -> None:
        try:
            await session.context.close()
        except Exception:
            logger.exception("Qwen account browser context cleanup failed")
        if session.browser_manager is not None:
            try:
                await session.browser_manager.__aexit__(None, None, None)
            except Exception:
                logger.exception("Qwen Camoufox runtime cleanup failed")

    async def _close_unlocked(self) -> None:
        sessions = list(self._sessions.values())
        self._sessions.clear()
        for session in sessions:
            await self._close_session(session)
        if self._browser is not None:
            try:
                await self._browser.close()
            except Exception:
                logger.exception("Qwen native browser cleanup failed")
        if self._playwright is not None:
            try:
                await self._playwright.stop()
            except Exception:
                logger.exception("Qwen native Playwright cleanup failed")
        self._playwright = None
        self._browser = None
        self._browser_profile = ""
        self._user_agent = ""

    async def _ensure_ready(self) -> None:
        if self._browser is not None and self._browser.is_connected():
            return
        async with self._lock:
            if self._browser is not None and self._browser.is_connected():
                return
            config = settings()
            try:
                if self._browser is not None or self._playwright is not None or self._sessions:
                    await self._close_unlocked()
                self._playwright = await async_playwright().start()
                self._browser = await self._playwright.chromium.launch(
                    headless=config.qwen_risk_headless
                )
                logger.info(
                    "qwen_native_browser_started engine_version=%s",
                    self._browser.version,
                )
            except Exception:
                await self._close_unlocked()
                raise

    async def _load_page_runtime(self, session: _AccountBrowserSession) -> None:
        config = settings()
        session.page.set_default_timeout(60_000)
        await session.page.goto(
            config.qwen_base_url,
            wait_until="domcontentloaded",
            timeout=60_000,
        )
        await session.page.wait_for_function(
            r"""() => performance.getEntriesByType('resource').some(
              entry => /\/sd\/baxia\/[\d.]+\/baxiaCommon\.js/.test(entry.name)
            )""",
            timeout=45_000,
        )
        await session.page.wait_for_timeout(2_000)
        session.frontend_version = await session.page.evaluate(
            r"""() => {
              for (const entry of performance.getEntriesByType('resource')) {
                const match = entry.name.match(/qwen-chat-fe\/([^/]+)\/js\/main\.js/);
                if (match) return match[1];
              }
              return '';
            }"""
        )
        if not session.frontend_version:
            raise RuntimeError("could not derive the current Qwen frontend version")
        runtime = await session.page.evaluate(
            """() => {
              const gl = document.createElement('canvas').getContext('webgl');
              const debug = gl?.getExtension('WEBGL_debug_renderer_info');
              return {
                userAgent: navigator.userAgent,
                platform: navigator.platform,
                device_memory: navigator.deviceMemory,
                webgl_vendor: debug ? gl.getParameter(debug.UNMASKED_VENDOR_WEBGL) : '',
                webgl_renderer: debug ? gl.getParameter(debug.UNMASKED_RENDERER_WEBGL) : ''
              };
            }"""
        )
        if str(runtime.get("userAgent") or "") != session.user_agent:
            raise RuntimeError("Qwen browser fingerprint User-Agent was not restored")
        if session.backend == "patchright":
            session.browser_fingerprint = finalize_patchright_fingerprint(
                session.browser_fingerprint, runtime
            )
            session.fingerprint_digest = qwen_fingerprint_digest(session.browser_fingerprint)


def _send_qwen_request(
    payload: dict[str, Any],
    browser_headers: dict[str, str],
) -> tuple[dict[str, Any], bytes]:
    maximum = int(payload["maximumBytes"])
    headers = {
        name: value
        for name, value in browser_headers.items()
        if name.lower() not in {"content-length", "host", "connection"}
    }
    with curl_requests.Session(
        impersonate=str(payload["browserProfile"]),
        http_version="v2",
        default_headers=False,
    ) as client:
        proxy_url = str(payload.get("proxyUrl") or "")
        response = client.request(
            str(payload["method"]),
            str(payload["url"]),
            headers=headers,
            data=None if payload["method"] == "GET" else str(payload["body"]),
            timeout=int(payload["timeoutMs"]) / 1000,
            allow_redirects=False,
            stream=True,
            proxy=proxy_url or None,
        )
        try:
            body_buffer = bytearray()
            event_stream = "text/event-stream" in response.headers.get("content-type", "")
            for chunk in response.iter_content(chunk_size=8192):
                if not chunk:
                    continue
                body_buffer.extend(chunk)
                if len(body_buffer) > maximum:
                    raise RuntimeError("Qwen browser response exceeds the buffered byte limit")
                if event_stream and _qwen_sse_finished(bytes(body_buffer[-65_536:])):
                    break
            body = bytes(body_buffer)
            result = {
                "status": response.status_code,
                "contentType": response.headers.get("content-type", "application/octet-stream"),
                "requestId": response.headers.get("x-request-id", str(payload["requestId"])),
                "retryAfter": response.headers.get("retry-after", ""),
                "bodyBase64": base64.b64encode(body).decode(),
            }
            return result, body
        finally:
            response.close()


def _qwen_sse_finished(body: bytes) -> bool:
    for raw_line in body.decode("utf-8", errors="replace").splitlines():
        line = raw_line.lstrip()
        if not line.startswith("data:"):
            continue
        data = line[5:].strip()
        if data == "[DONE]":
            return True
        try:
            payload = json.loads(data)
        except json.JSONDecodeError:
            continue
        if not isinstance(payload, dict):
            continue
        if payload.get("response.completed"):
            return True
        choice = _first_qwen_choice(payload)
        delta = choice.get("delta") or choice.get("message") or {}
        phase = str(delta.get("phase") or "") if isinstance(delta, dict) else ""
        status = str(delta.get("status") or "") if isinstance(delta, dict) else ""
        if choice.get("finish_reason") or str(payload.get("status") or "") in {
            "completed",
            "finished",
        }:
            return True
        if status == "finished" and phase not in {"thinking", "thinking_summary"}:
            return True
    return False


def _first_qwen_choice(payload: dict[str, Any]) -> dict[str, Any]:
    for candidate in (
        payload.get("choices"),
        (payload.get("data") or {}).get("choices")
        if isinstance(payload.get("data"), dict)
        else None,
        (payload.get("output") or {}).get("choices")
        if isinstance(payload.get("output"), dict)
        else None,
    ):
        if isinstance(candidate, list) and candidate and isinstance(candidate[0], dict):
            return candidate[0]
    return {}


def _qwen_failure_code(body: bytes) -> str:
    try:
        payload = json.loads(body)
        values = payload.get("ret") if isinstance(payload, dict) else None
        return str(values[0]) if isinstance(values, list) and values else ""
    except (UnicodeDecodeError, json.JSONDecodeError, TypeError):
        return ""


def _qwen_punish_url(body: bytes) -> str:
    if _qwen_failure_code(body) != "FAIL_SYS_USER_VALIDATE":
        return ""
    try:
        payload = json.loads(body)
        value = str((payload.get("data") or {}).get("url") or "").strip()
        target = urlparse(value)
        expected = urlparse(settings().qwen_base_url)
        valid_port = target.port in {None, 443}
        if (
            target.scheme == "https"
            and target.hostname == expected.hostname
            and valid_port
            and "/punish" in target.path
            and target.query
        ):
            return value
    except (UnicodeDecodeError, json.JSONDecodeError, TypeError, ValueError):
        return ""
    return ""


provider = QwenRiskHeaderProvider()
native_transport = QwenNativeBrowserTransport()
router = APIRouter(
    prefix="/internal/v1/providers/qwen",
    dependencies=[Depends(require_internal_token)],
)


@router.post("/risk-headers")
def risk_headers(request: RiskHeadersRequest) -> dict[str, Any]:
    try:
        return {
            "ok": True,
            "headers": provider.get(request.url, request.method, request.body),
            "mode": "per_request_browser",
        }
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc


@router.post("/browser-fetch")
async def browser_fetch(request: NativeBrowserRequest) -> dict[str, Any]:
    try:
        return await native_transport.fetch(request)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        logger.exception("Qwen native browser fetch failed")
        raise HTTPException(status_code=502, detail="Qwen native browser fetch failed") from exc
