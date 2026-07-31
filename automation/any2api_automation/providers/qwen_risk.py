from __future__ import annotations

import json
import logging
import queue
import threading
from dataclasses import dataclass, field
from typing import Any
from urllib.parse import urljoin, urlparse

from fastapi import APIRouter, Depends, HTTPException
from patchright.sync_api import Browser, BrowserContext, Page, sync_playwright
from pydantic import BaseModel

from ..security import require_internal_token
from .qwen_settings import settings

logger = logging.getLogger(__name__)


class RiskHeadersRequest(BaseModel):
    url: str
    method: str = "GET"
    body: str = ""


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
                browser = playwright.chromium.launch(headless=settings().qwen_risk_headless)
                context = browser.new_context(
                    locale="zh-CN",
                    timezone_id="Asia/Shanghai",
                    viewport={"width": 1440, "height": 900},
                )
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


provider = QwenRiskHeaderProvider()
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
