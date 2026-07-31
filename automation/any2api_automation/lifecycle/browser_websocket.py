from __future__ import annotations

import base64
import queue
import threading
import time
from concurrent.futures import Future
from dataclasses import dataclass
from typing import Any

from .browser import BrowserContextProfile, BrowserLaunchProfile, launch_browser
from .browser_session import BrowserSession


@dataclass(frozen=True)
class _Command:
    name: str
    payload: Any
    result: Future[Any]


class BrowserWebSocket:
    """Thread-owned browser WebSocket used when libcurl cannot reproduce the handshake."""

    def __init__(
        self,
        *,
        browser: BrowserSession,
        proxy_url: str,
        target_url: str,
        timeout_seconds: int,
    ) -> None:
        self._browser = browser
        self._proxy_url = proxy_url
        self._target_url = target_url
        self._timeout_seconds = max(30, min(300, int(timeout_seconds)))
        self._commands: queue.Queue[_Command] = queue.Queue()
        self._frames: queue.Queue[tuple[bytes, int]] = queue.Queue()
        self._ready: Future[None] = Future()
        self._closed = threading.Event()
        self._thread = threading.Thread(
            target=self._run,
            name="any2api-browser-websocket",
            daemon=True,
        )
        self._thread.start()
        self._ready.result(timeout=self._timeout_seconds + 30)

    def send_json(self, value: Any) -> None:
        self._call("send", value, self._timeout_seconds)

    def recv(self) -> tuple[bytes, int]:
        return self._call("receive", None, self._timeout_seconds + 10)

    def close(self) -> None:
        if self._closed.is_set():
            return
        try:
            self._call("close", None, 15)
        finally:
            self._closed.set()
            self._thread.join(timeout=20)

    def _call(self, name: str, payload: Any, timeout: int) -> Any:
        if self._closed.is_set() and name != "close":
            raise RuntimeError("browser WebSocket is closed")
        result: Future[Any] = Future()
        self._commands.put(_Command(name, payload, result))
        return result.result(timeout=timeout)

    def _run(self) -> None:
        try:
            self._browser_loop()
        except Exception as error:  # noqa: BLE001 - propagate worker startup failures
            if not self._ready.done():
                self._ready.set_exception(error)
            self._fail_pending(error)
        finally:
            self._closed.set()

    def _browser_loop(self) -> None:
        launch = BrowserLaunchProfile(
            headless=True,
            humanize=False,
            camoufox_os="windows",
            block_webrtc=True,
            launch_timeout_ms=120_000,
        )
        context_profile = BrowserContextProfile(
            locale="en-US",
            timezone_id="Asia/Tokyo" if self._proxy_url else "UTC",
            viewport_width=1365,
            viewport_height=900,
            accept_language="en-US,en;q=0.9",
            patchright_user_agent=self._browser.user_agent,
        )
        with launch_browser(
            "camoufox",
            "patchright",
            headless=True,
            proxy_url=self._proxy_url,
            profile=launch,
        ) as (backend, runtime):
            context = runtime.new_context(**context_profile.options(backend))
            context.set_default_timeout(self._timeout_seconds * 1000)
            try:
                cookies = self._browser.browser_cookies()
                if cookies:
                    context.add_cookies(cookies)
                page = context.new_page()
                page.expose_binding("__any2apiWebSocketFrame", self._on_frame)
                page.expose_binding("__any2apiWebSocketState", self._on_state)
                page.goto(
                    self._browser.origin + "/",
                    wait_until="domcontentloaded",
                    timeout=self._timeout_seconds * 1000,
                )
                page.evaluate(_OPEN_WEBSOCKET, {"url": self._target_url})
                page.wait_for_function(
                    "() => ['open','error','closed'].includes(window.__any2apiWsState)",
                    timeout=self._timeout_seconds * 1000,
                )
                if page.evaluate("() => window.__any2apiWsState") != "open":
                    raise RuntimeError("browser WebSocket handshake failed")
                self._ready.set_result(None)
                self._command_loop(page)
            finally:
                context.close()

    def _command_loop(self, page: Any) -> None:
        while True:
            command = self._commands.get()
            try:
                if command.name == "send":
                    page.evaluate(
                        "value => window.__any2apiWs.send(JSON.stringify(value))",
                        command.payload,
                    )
                    command.result.set_result(None)
                    continue
                if command.name == "receive":
                    command.result.set_result(self._receive_frame(page))
                    continue
                if command.name == "close":
                    try:
                        page.evaluate("() => window.__any2apiWs?.close(1000)")
                        page.wait_for_timeout(100)
                    finally:
                        command.result.set_result(None)
                    return
                raise ValueError("unsupported browser WebSocket command")
            except Exception as error:  # noqa: BLE001 - return command failures to caller
                command.result.set_exception(error)

    def _receive_frame(self, page: Any) -> tuple[bytes, int]:
        deadline = time.monotonic() + self._timeout_seconds
        while time.monotonic() < deadline:
            try:
                return self._frames.get_nowait()
            except queue.Empty:
                page.wait_for_timeout(50)
                if page.evaluate("() => window.__any2apiWsState") in {"error", "closed"}:
                    raise RuntimeError("browser WebSocket closed before the next frame")
        raise TimeoutError("browser WebSocket receive timed out")

    def _on_frame(self, _source: Any, payload: dict[str, Any]) -> None:
        if payload.get("kind") == "text":
            self._frames.put((str(payload.get("data") or "").encode(), 1))
            return
        try:
            body = base64.b64decode(str(payload.get("data") or ""), validate=True)
        except ValueError:
            return
        self._frames.put((body, 2))

    def _on_state(self, _source: Any, value: str) -> None:
        del value

    def _fail_pending(self, error: BaseException) -> None:
        while True:
            try:
                command = self._commands.get_nowait()
            except queue.Empty:
                return
            if not command.result.done():
                command.result.set_exception(error)


_OPEN_WEBSOCKET = """
({url}) => {
  window.__any2apiWsState = 'connecting';
  const socket = new WebSocket(url);
  socket.binaryType = 'arraybuffer';
  window.__any2apiWs = socket;
  socket.onopen = () => {
    window.__any2apiWsState = 'open';
    window.__any2apiWebSocketState('open');
  };
  socket.onerror = () => {
    window.__any2apiWsState = 'error';
    window.__any2apiWebSocketState('error');
  };
  socket.onclose = () => {
    window.__any2apiWsState = 'closed';
    window.__any2apiWebSocketState('closed');
  };
  socket.onmessage = async event => {
    if (typeof event.data === 'string') {
      await window.__any2apiWebSocketFrame({kind: 'text', data: event.data});
      return;
    }
    const buffer = event.data instanceof Blob
      ? await event.data.arrayBuffer()
      : event.data;
    const bytes = new Uint8Array(buffer);
    let binary = '';
    for (let offset = 0; offset < bytes.length; offset += 0x8000) {
      binary += String.fromCharCode(...bytes.subarray(offset, offset + 0x8000));
    }
    await window.__any2apiWebSocketFrame({kind: 'binary', data: btoa(binary)});
  };
}
"""
