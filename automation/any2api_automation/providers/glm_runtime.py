from __future__ import annotations

import base64
import logging
import threading
from copy import deepcopy
from dataclasses import dataclass, field
from queue import Empty, Queue
from typing import Any
from urllib.parse import urlparse
from uuid import uuid4

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from ..browser_transport import BrowserRequest, _relative_path
from ..browser_transport import manager as browser_session_manager
from ..lifecycle.browser import (
    BrowserContextProfile,
    BrowserLaunchProfile,
    close_browser_context,
    launch_browser,
)
from ..security import require_internal_token
from .glm_challenge import GlmAliyunChallenge
from .glm_settings import settings


class GlmCaptchaRequest(BaseModel):
    timeout_seconds: int = Field(default=120, ge=30, le=240)


@dataclass(frozen=True)
class _Ready:
    diagnostic: str


@dataclass(frozen=True)
class _StreamMetadata:
    status: int
    content_type: str


@dataclass(frozen=True)
class _Failure:
    stage: str
    error_type: str


class _BoundFlowError(RuntimeError):
    pass


@dataclass
class _Flow:
    id: str
    session_id: str
    timeout_seconds: int
    command: Queue[BrowserRequest] = field(default_factory=lambda: Queue(maxsize=1))
    ready: Queue[object] = field(default_factory=lambda: Queue(maxsize=1))
    metadata: Queue[object] = field(default_factory=lambda: Queue(maxsize=1))
    chunks: Queue[object] = field(default_factory=lambda: Queue(maxsize=32))
    cancel: threading.Event = field(default_factory=threading.Event)
    thread: threading.Thread | None = None


_END = object()
logger = logging.getLogger("any2api_automation.providers.glm_runtime")


class _FlowManager:
    def __init__(self) -> None:
        self._flows: dict[str, _Flow] = {}
        self._lock = threading.Lock()

    def prepare(self, session_id: str, timeout_seconds: int) -> _Flow:
        flow = _Flow(uuid4().hex, session_id, timeout_seconds)
        with self._lock:
            self._flows[flow.id] = flow
        flow.thread = threading.Thread(
            target=self._run,
            args=(flow,),
            name=f"glm-browser-flow-{flow.id[:8]}",
            daemon=True,
        )
        flow.thread.start()
        try:
            result = flow.ready.get(timeout=timeout_seconds + 120)
        except Empty as error:
            flow.cancel.set()
            self.discard(flow.id)
            raise TimeoutError("GLM bound captcha flow did not become ready") from error
        if isinstance(result, _Failure):
            self.discard(flow.id)
            if result.error_type == "KeyError":
                raise KeyError(session_id)
            raise _BoundFlowError(
                f"GLM bound captcha flow failed ({result.stage}:{result.error_type})"
            )
        return flow

    def claim(self, session_id: str, flow_id: str) -> _Flow:
        with self._lock:
            flow = self._flows.pop(flow_id, None)
        if flow is None or flow.session_id != session_id:
            if flow is not None:
                flow.cancel.set()
            raise KeyError(flow_id)
        return flow

    def discard(self, flow_id: str) -> None:
        with self._lock:
            self._flows.pop(flow_id, None)

    def _run(self, flow: _Flow) -> None:
        ready = False
        metadata = False
        stage = "browser_setup"
        try:
            with (
                browser_session_manager.lease(flow.session_id) as entry,
                launch_browser(
                    "camoufox",
                    "patchright",
                    headless=True,
                    proxy_url=entry.proxy_url,
                    profile=BrowserLaunchProfile(humanize=True, camoufox_os="windows"),
                ) as launched,
            ):
                backend, browser = launched
                profile = BrowserContextProfile(
                    locale="en-US",
                    timezone_id="Asia/Tokyo",
                    viewport_width=1440,
                    viewport_height=900,
                    accept_language="en-US,en;q=0.9",
                    patchright_user_agent=entry.browser.user_agent,
                )
                context = browser.new_context(**profile.options(backend))
                page = None
                try:
                    context.set_default_timeout(flow.timeout_seconds * 1000)
                    cookies = entry.browser.browser_cookies()
                    if cookies:
                        context.add_cookies(cookies)
                    page = context.new_page()
                    stage = "navigation"
                    page.goto(
                        settings().glm_base_url,
                        wait_until="domcontentloaded",
                        timeout=90_000,
                    )
                    page.wait_for_timeout(2_000)
                    stage = "captcha"
                    challenge = GlmAliyunChallenge.for_chat()
                    ticket = challenge.solve(page, timeout_seconds=flow.timeout_seconds)
                    flow.ready.put(_Ready(challenge.last_diagnostic))
                    ready = True
                    if flow.cancel.is_set():
                        return
                    stage = "await_completion"
                    try:
                        request = flow.command.get(timeout=60)
                    except Empty as error:
                        raise TimeoutError("GLM bound captcha flow was not consumed") from error
                    stage = "completion_start"
                    stream = _start_completion(page, request, ticket)
                    flow.metadata.put(stream)
                    metadata = True
                    stage = "completion_stream"
                    while not flow.cancel.is_set():
                        chunk = page.evaluate(_READ_COMPLETION_CHUNK)
                        if not isinstance(chunk, dict) or bool(chunk.get("done")):
                            break
                        encoded = str(chunk.get("body") or "")
                        if encoded:
                            flow.chunks.put(base64.b64decode(encoded, validate=True))
                finally:
                    close_browser_context(
                        context,
                        page if page is not None else context,
                        label="GLM runtime context cleanup",
                    )
        except Exception as error:  # noqa: BLE001 - failure crosses the worker-thread boundary
            failure = _Failure(stage, type(error).__name__)
            logger.warning(
                "GLM bound browser flow failed stage=%s error_type=%s",
                stage,
                failure.error_type,
            )
            if not ready:
                flow.ready.put(failure)
            elif not metadata:
                flow.metadata.put(failure)
            else:
                flow.chunks.put(failure)
        finally:
            flow.chunks.put(_END)
            self.discard(flow.id)


flow_manager = _FlowManager()


router = APIRouter(
    prefix="/internal/v1/providers/glm/browser-sessions",
    dependencies=[Depends(require_internal_token)],
)


@router.post("/{session_id}/captcha")
def solve_chat_captcha(session_id: str, request: GlmCaptchaRequest) -> dict[str, object]:
    try:
        flow = flow_manager.prepare(session_id, request.timeout_seconds)
        return {"ok": True, "flow_id": flow.id}
    except KeyError as error:
        raise HTTPException(status_code=404, detail="browser session does not exist") from error
    except (OSError, RuntimeError, TimeoutError, TypeError, ValueError) as error:
        raise HTTPException(
            status_code=502,
            detail=f"GLM bound captcha flow failed ({type(error).__name__})",
        ) from error


@router.post("/{session_id}/captcha/flows/{flow_id}/stream", response_class=StreamingResponse)
def stream_chat_completion(
    session_id: str,
    flow_id: str,
    request: BrowserRequest,
) -> StreamingResponse:
    flow: _Flow | None = None
    try:
        flow = flow_manager.claim(session_id, flow_id)
        _completion_request(request, "validation-ticket")
        flow.command.put(request)
        result = flow.metadata.get(timeout=request.timeout_seconds + 30)
        if isinstance(result, _Failure):
            raise _BoundFlowError(
                f"GLM bound completion failed ({result.stage}:{result.error_type})"
            )
        if not isinstance(result, _StreamMetadata):
            raise TypeError("GLM bound completion returned invalid metadata")
    except Empty as error:
        if flow is not None:
            flow.cancel.set()
        raise HTTPException(
            status_code=504,
            detail="GLM bound completion metadata timed out",
        ) from error
    except KeyError as error:
        raise HTTPException(status_code=404, detail="GLM captcha flow does not exist") from error
    except (OSError, RuntimeError, TimeoutError, TypeError, ValueError) as error:
        if flow is not None:
            flow.cancel.set()
        raise HTTPException(
            status_code=502,
            detail=f"GLM bound completion failed ({type(error).__name__})",
        ) from error

    def chunks():
        try:
            while True:
                item = flow.chunks.get()
                if item is _END:
                    break
                if isinstance(item, _Failure):
                    raise _BoundFlowError(
                        f"GLM bound completion failed ({item.stage}:{item.error_type})"
                    )
                if isinstance(item, bytes):
                    yield item
        finally:
            flow.cancel.set()
            if flow.thread is not None:
                flow.thread.join(timeout=5)

    return StreamingResponse(
        chunks(),
        status_code=result.status,
        media_type=result.content_type,
    )


def _start_completion(page: Any, request: BrowserRequest, ticket: str) -> _StreamMetadata:
    payload = _completion_request(request, ticket)
    result = page.evaluate(_START_COMPLETION_STREAM, payload)
    if not isinstance(result, dict):
        raise TypeError("GLM bound completion returned invalid response metadata")
    status = int(result.get("status") or 502)
    content_type = str(result.get("content_type") or "application/octet-stream")
    return _StreamMetadata(status, content_type)


def _completion_request(request: BrowserRequest, ticket: str) -> dict[str, object]:
    path = _relative_path(request.path)
    if request.method != "POST" or urlparse(path).path != "/api/v2/chat/completions":
        raise ValueError("GLM bound completion requires the provider completion path")
    if not isinstance(request.json_body, dict):
        raise TypeError("GLM bound completion requires a JSON object body")
    allowed_headers = {"content-type", "x-fe-version", "x-region", "x-signature"}
    if any(name.lower() not in allowed_headers for name in request.headers):
        raise ValueError("GLM bound completion contains an unsupported header")
    referer_path = _relative_path(request.referer_path)
    if not urlparse(referer_path).path.startswith("/c/"):
        raise ValueError("GLM bound completion requires a chat referer")
    body = deepcopy(request.json_body)
    body["captcha_verify_param"] = ticket
    return {
        "method": request.method,
        "path": path,
        "headers": request.headers,
        "body": body,
        "referer_path": referer_path,
    }


_START_COMPLETION_STREAM = r"""
async input => {
  history.replaceState(null, '', input.referer_path);
  const target = new URL(input.path, location.origin);
  const ua = navigator.userAgent;
  const language = navigator.language || 'en-US';
  const browserName = /Firefox\//.test(ua) ? 'firefox' :
    /Edg\//.test(ua) ? 'edge' : /Chrome\//.test(ua) ? 'chrome' : 'unknown';
  const osName = navigator.userAgentData?.platform || navigator.platform || 'Unknown';
  const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  const values = {
    user_agent: ua,
    language,
    languages: [...(navigator.languages || [language])].join(','),
    timezone,
    cookie_enabled: String(navigator.cookieEnabled),
    screen_width: String(screen.width),
    screen_height: String(screen.height),
    screen_resolution: `${screen.width}x${screen.height}`,
    viewport_width: String(innerWidth),
    viewport_height: String(innerHeight),
    viewport_size: `${innerWidth}x${innerHeight}`,
    color_depth: String(screen.colorDepth),
    pixel_ratio: String(devicePixelRatio),
    current_url: location.href,
    pathname: location.pathname,
    search: location.search,
    hash: location.hash,
    host: location.host,
    hostname: location.hostname,
    protocol: location.protocol,
    title: document.title,
    timezone_offset: String(new Date().getTimezoneOffset()),
    is_mobile: String(/Mobi|Android/i.test(ua)),
    is_touch: String(navigator.maxTouchPoints > 0),
    max_touch_points: String(navigator.maxTouchPoints || 0),
    browser_name: browserName,
    os_name: osName
  };
  for (const [name, value] of Object.entries(values)) target.searchParams.set(name, value);
  const path = `${target.pathname}?${target.searchParams.toString()}`;
  const response = await fetch(path, {
    method: input.method,
    headers: input.headers,
    body: JSON.stringify(input.body),
    credentials: 'include'
  });
  window.__any2apiGlmCompletionReader = response.body?.getReader() || null;
  return {
    status: response.status,
    content_type: response.headers.get('content-type') || 'application/octet-stream',
    fingerprint_aligned: true
  };
}
"""


_READ_COMPLETION_CHUNK = """
async () => {
  const reader = window.__any2apiGlmCompletionReader;
  if (!reader) return {done: true, body: ''};
  const result = await reader.read();
  if (result.done || !result.value) return {done: true, body: ''};
  let binary = '';
  for (let offset = 0; offset < result.value.length; offset += 32768) {
    binary += String.fromCharCode(...result.value.subarray(offset, offset + 32768));
  }
  return {done: false, body: btoa(binary)};
}
"""
