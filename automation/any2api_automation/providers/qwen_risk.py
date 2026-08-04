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
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any, Literal
from urllib.parse import urljoin, urlparse
from uuid import uuid4

from fastapi import APIRouter, Depends, HTTPException
from patchright.async_api import TimeoutError as AsyncPlaywrightTimeoutError
from patchright.async_api import async_playwright
from patchright.sync_api import Browser, BrowserContext, Page, sync_playwright
from pydantic import BaseModel, Field, model_validator

from ..config import settings as global_settings
from ..security import require_internal_token
from .qwen_inference_challenge import QwenInferenceChallengeSolver
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
      Object.defineProperty(Navigator.prototype, 'platform', {{get: () => 'Win32'}});
      Object.defineProperty(Navigator.prototype, 'userAgentData', {{
        get: () => userAgentData
      }});
    }})();
    """


class RiskHeadersRequest(BaseModel):
    url: str
    method: str = "GET"
    body: str = ""


class NativeBrowserRequest(BaseModel):
    method: Literal["GET", "POST"] = "POST"
    path: str = Field(min_length=1, max_length=2048)
    body: str = Field(default="", max_length=2 << 20)
    bearer_token: str = Field(min_length=20, max_length=16_384)
    cookies: dict[str, str] = Field(default_factory=dict)
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
        if any(
            not re.fullmatch(r"[!#$%&'*+.^_`|~0-9A-Za-z-]{1,256}", name)
            or len(value) > 8_192
            or "\r" in value
            or "\n" in value
            for name, value in self.cookies.items()
        ):
            raise ValueError("Qwen browser transport received an invalid cookie")
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
                context.add_init_script(_fingerprint_script(config.qwen_risk_browser_profile))
                page = context.new_page()
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


class QwenNativeBrowserTransport:
    """Executes authenticated Qwen fetches in the real browser network stack."""

    def __init__(self) -> None:
        self._lock = asyncio.Lock()
        self._request_lock = asyncio.Lock()
        self._playwright: Any | None = None
        self._browser: Any | None = None
        self._context: Any | None = None
        self._page: Any | None = None
        self._frontend_version = ""
        self._challenge_solver = QwenInferenceChallengeSolver()
        self._active_account_key = ""
        self._cookie_jars: OrderedDict[str, list[dict[str, Any]]] = OrderedDict()

    async def fetch(self, request: NativeBrowserRequest) -> dict[str, Any]:
        await self._ensure_ready()
        async with self._request_lock:
            result, body = await self._evaluate(request)
            punish_url = _qwen_punish_url(body)
            if punish_url:
                await self._recover_from_challenge(punish_url)
                result, body = await self._evaluate(request)
            return self._response(request, result, body)

    async def _evaluate(self, request: NativeBrowserRequest) -> tuple[dict[str, Any], bytes]:
        await self._prepare_authenticated_surface(request)
        maximum = global_settings().browser_transport_max_buffered_bytes
        payload = {
            "url": settings().qwen_base_url.rstrip("/") + request.path,
            "path": request.path,
            "method": request.method,
            "body": request.body,
            "bearerToken": request.bearer_token,
            "useBearer": not bool(request.cookies),
            "referrer": settings().qwen_base_url.rstrip("/") + request.referer_path,
            "timeoutMs": request.timeout_seconds * 1000,
            "version": self._frontend_version,
            "requestId": str(uuid4()),
            "timezone": datetime.now(QWEN_TIMEZONE).strftime("%a %b %d %Y %H:%M:%S GMT%z"),
        }
        result, body = await self._fetch_in_main_world(payload)
        if len(body) > maximum:
            raise RuntimeError("Qwen browser response exceeds the buffered byte limit")
        return result, body

    async def _fetch_in_main_world(
        self,
        payload: dict[str, Any],
    ) -> tuple[dict[str, Any], bytes]:
        encoded = base64.b64encode(
            json.dumps(payload, ensure_ascii=True, separators=(",", ":")).encode()
        ).decode()
        marker_id = f"qwen-fetch-{payload['requestId']}"
        script = f"""(() => {{
          const bytes = Uint8Array.from(atob('{encoded}'), value => value.charCodeAt(0));
          const request = JSON.parse(new TextDecoder().decode(bytes));
          const controller = new AbortController();
          const timeout = setTimeout(() => controller.abort(), request.timeoutMs);
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
            referrer: request.referrer,
            signal: controller.signal
          }}).catch(error => {{
            const marker = document.createElement('meta');
            marker.id = '{marker_id}';
            marker.dataset.error = error?.name || 'FetchError';
            document.head.appendChild(marker);
          }}).finally(() => clearTimeout(timeout));
          if (document.currentScript) document.currentScript.remove();
        }})();"""
        # A script tag runs in Qwen's main world, where Baxia wraps fetch and adds bx-* headers.
        target_url = str(payload["url"])
        method = str(payload["method"])
        request_id = str(payload["requestId"])
        try:
            async with self._page.expect_response(
                lambda response: (
                    response.url == target_url
                    and response.request.method == method
                    and response.request.headers.get("x-request-id") == request_id
                ),
                timeout=int(payload["timeoutMs"]) + 5_000,
            ) as response_info:
                await self._page.add_script_tag(content=script)
            response = await response_info.value
        except AsyncPlaywrightTimeoutError as error:
            marker = self._page.locator(f"#{marker_id}")
            failure = await marker.get_attribute("data-error") if await marker.count() else None
            raise RuntimeError(
                f"Qwen main-world fetch failed: {failure or 'response timeout'}"
            ) from error
        finally:
            try:
                await self._page.evaluate(
                    "markerId => document.getElementById(markerId)?.remove()", marker_id
                )
            except Exception:
                logger.debug("Qwen main-world fetch marker cleanup skipped", exc_info=True)
        body = await response.body()
        headers = await response.all_headers()
        request_headers = await response.request.all_headers()
        baxia_headers = {"bx-ua", "bx-umidtoken", "bx-v"}
        if not baxia_headers.issubset(request_headers):
            logger.warning(
                "qwen_native_browser_baxia_headers_missing path=%s missing=%s",
                payload["path"],
                sorted(baxia_headers.difference(request_headers)),
            )
        return {
            "status": response.status,
            "contentType": headers.get("content-type", "application/octet-stream"),
            "requestId": headers.get("x-request-id", request_id),
            "retryAfter": headers.get("retry-after", ""),
            "bodyBase64": base64.b64encode(body).decode(),
        }, body

    async def _prepare_authenticated_surface(self, request: NativeBrowserRequest) -> None:
        base_url = settings().qwen_base_url.rstrip("/")
        await self._activate_account(request)
        logger.info(
            "qwen_native_browser_surface path=%s auth_mode=%s credential_cookies=%s",
            request.referer_path,
            "cookie" if request.cookies else "bearer_fallback",
            len(request.cookies),
        )
        await self._page.evaluate(
            "token => localStorage.setItem('token', token)",
            request.bearer_token,
        )
        target = base_url + request.referer_path
        current = urlparse(self._page.url)
        desired = urlparse(target)
        if current.path != desired.path:
            await self._page.goto(target, wait_until="domcontentloaded", timeout=60_000)
            await self._page.wait_for_function(
                "() => localStorage.getItem('token') && document.readyState !== 'loading'",
                timeout=30_000,
            )
            await self._page.wait_for_timeout(750)
        await self._ensure_baxia_ready()

    async def _activate_account(self, request: NativeBrowserRequest) -> None:
        account_key = hashlib.sha256(request.bearer_token.encode()).hexdigest()
        base_url = settings().qwen_base_url.rstrip("/")
        credential_cookies = [
            {"name": name, "value": value, "url": base_url}
            for name, value in request.cookies.items()
        ]
        if self._active_account_key == account_key:
            if credential_cookies:
                await self._context.add_cookies(credential_cookies)
            return
        if self._active_account_key:
            self._cookie_jars[self._active_account_key] = await self._context.cookies()
            self._cookie_jars.move_to_end(self._active_account_key)
        await self._context.clear_cookies()
        jar = self._cookie_jars.pop(account_key, [])
        if jar:
            await self._context.add_cookies(jar)
        if credential_cookies:
            await self._context.add_cookies(credential_cookies)
        self._cookie_jars[account_key] = []
        self._active_account_key = account_key
        while len(self._cookie_jars) > 64:
            self._cookie_jars.popitem(last=False)

    async def _ensure_baxia_ready(self) -> None:
        await self._page.evaluate(
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
        await self._page.wait_for_function(
            r"""() => {
              const module = window.__baxia__?.getFYModule;
              return Boolean(
                window.baxiaCommon && window.__baxia__?.baxiaPromptInit &&
                window.baxiaInitialized && module?.getUidToken?.call(module)
              );
            }""",
            timeout=30_000,
        )

    def _response(
        self,
        request: NativeBrowserRequest,
        result: dict[str, Any],
        body: bytes,
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
            "transport_mode": "native_browser_buffered",
        }

    async def _recover_from_challenge(self, punish_url: str) -> None:
        try:
            outcome = await self._challenge_solver.solve(self._page, punish_url)
            logger.info(
                "qwen_native_browser_challenge_cleared attempts=%s diagnostic=%s",
                outcome.attempts,
                outcome.diagnostic,
            )
        finally:
            await self._load_page_runtime()

    async def close(self) -> None:
        async with self._lock:
            await self._close_unlocked()

    async def _close_unlocked(self) -> None:
        for value in (self._context, self._browser):
            if value is not None:
                try:
                    await value.close()
                except Exception:
                    logger.exception("Qwen native browser cleanup failed")
        if self._playwright is not None:
            try:
                await self._playwright.stop()
            except Exception:
                logger.exception("Qwen native Playwright cleanup failed")
        self._playwright = None
        self._browser = None
        self._context = None
        self._page = None
        self._frontend_version = ""
        self._active_account_key = ""
        self._cookie_jars.clear()

    async def _ensure_ready(self) -> None:
        if self._page is not None and not self._page.is_closed():
            return
        async with self._lock:
            if self._page is not None and not self._page.is_closed():
                return
            config = settings()
            try:
                self._playwright = await async_playwright().start()
                self._browser = await self._playwright.chromium.launch(
                    headless=config.qwen_risk_headless
                )
                browser_version = str(self._browser.version)
                browser_major = browser_version.split(".", 1)[0]
                browser_profile = f"chrome{browser_major}"
                user_agent = re.sub(
                    r"Chrome/[\d.]+",
                    f"Chrome/{browser_version}",
                    config.qwen_risk_user_agent,
                )
                self._context = await self._browser.new_context(
                    locale="zh-CN",
                    timezone_id="Asia/Shanghai",
                    viewport={"width": 1440, "height": 900},
                    user_agent=user_agent,
                    extra_http_headers={
                        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
                        **_client_hints(browser_profile),
                    },
                )
                await self._context.add_init_script(_fingerprint_script(browser_profile))
                self._page = await self._context.new_page()
                logger.info(
                    "qwen_native_browser_started profile=%s user_agent=%s",
                    browser_profile,
                    user_agent,
                )
                await self._load_page_runtime()
            except Exception:
                await self._close_unlocked()
                raise

    async def _load_page_runtime(self) -> None:
        config = settings()
        self._page.set_default_timeout(60_000)
        await self._page.goto(
            config.qwen_base_url,
            wait_until="domcontentloaded",
            timeout=60_000,
        )
        await self._page.wait_for_function(
            r"""() => performance.getEntriesByType('resource').some(
              entry => /\/sd\/baxia\/[\d.]+\/baxiaCommon\.js/.test(entry.name)
            )""",
            timeout=45_000,
        )
        await self._page.wait_for_timeout(2_000)
        self._frontend_version = await self._page.evaluate(
            r"""() => {
              for (const entry of performance.getEntriesByType('resource')) {
                const match = entry.name.match(/qwen-chat-fe\/([^/]+)\/js\/main\.js/);
                if (match) return match[1];
              }
              return '';
            }"""
        )
        if not self._frontend_version:
            raise RuntimeError("could not derive the current Qwen frontend version")


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
