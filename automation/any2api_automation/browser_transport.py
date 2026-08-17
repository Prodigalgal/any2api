from __future__ import annotations

import base64
import hashlib
import threading
import time
from collections.abc import Iterator
from contextlib import ExitStack, contextmanager, suppress
from dataclasses import dataclass, field
from typing import Any, Literal
from urllib.parse import urlparse
from uuid import uuid4

from curl_cffi.requests.exceptions import RequestException
from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field, model_validator

from .config import settings
from .lifecycle.browser_session import BrowserSession, BrowserSessionProfile
from .lifecycle.browser_websocket import BrowserWebSocket
from .lifecycle.clearance import refresh_clearance
from .lifecycle.proxy import proxy_lease, proxy_parameters
from .security import require_internal_token

_BLOCKED_HEADERS = frozenset(
    {
        "authorization",
        "cookie",
        "content-length",
        "host",
        "proxy-authorization",
        "transfer-encoding",
    }
)
_FINGERPRINT_HEADERS = frozenset(
    {
        "accept",
        "cache-control",
        "origin",
        "pragma",
        "priority",
        "referer",
        "sec-fetch-dest",
        "sec-fetch-mode",
        "sec-fetch-site",
        "upgrade-insecure-requests",
        "user-agent",
        "sec-ch-ua",
        "sec-ch-ua-mobile",
        "sec-ch-ua-platform",
    }
)
_REQUEST_FINGERPRINTS: dict[str, tuple[tuple[str, str], ...]] = {
    "none": (),
    "navigation": (
        (
            "Accept",
            (
                "text/html,application/xhtml+xml,application/xml;q=0.9,"
                "image/avif,image/webp,*/*;q=0.8"
            ),
        ),
        ("Cache-Control", "no-cache"),
        ("Pragma", "no-cache"),
        ("Sec-Fetch-Dest", "document"),
        ("Sec-Fetch-Mode", "navigate"),
        ("Sec-Fetch-Site", "same-origin"),
        ("Upgrade-Insecure-Requests", "1"),
        ("Priority", "u=0, i"),
    ),
    "same_origin_fetch": (
        ("Accept", "*/*"),
        ("Cache-Control", "no-cache"),
        ("Pragma", "no-cache"),
        ("Sec-Fetch-Dest", "empty"),
        ("Sec-Fetch-Mode", "cors"),
        ("Sec-Fetch-Site", "same-origin"),
        ("Priority", "u=1, i"),
    ),
}
_RETURNED_HEADERS = frozenset(
    {"cache-control", "content-type", "location", "retry-after", "x-request-id"}
)


class BrowserSessionOpenRequest(BaseModel):
    origin: str = Field(min_length=8, max_length=512)
    origins: list[str] = Field(default_factory=list, max_length=8)
    cookies: dict[str, str] = Field(default_factory=dict)
    cookie_domains: list[str] = Field(default_factory=list, max_length=8)
    user_agent: str = Field(default="", max_length=512)
    browser_profile: str = Field(default="chrome136", pattern=r"^(?:chrome|firefox)[0-9]{2,3}$")
    http_version: Literal["v1", "v2"] = "v2"
    proxy_pool: dict[str, Any] | None = None
    proxy_url: str = Field(default="", max_length=2048)
    dynamic_proxy: bool = False
    proxy_affinity_key: str = Field(default="", max_length=256)
    strict_proxy_affinity: bool = False
    proxy_node_offset: int = Field(default=0, ge=0, le=10_000)
    clearance_revision: str = Field(default="", max_length=128)
    bearer_token: str = Field(default="", max_length=16_384)
    ttl_seconds: int | None = Field(default=None, ge=30, le=900)


class BrowserRequest(BaseModel):
    method: Literal["GET", "POST", "PUT", "PATCH", "DELETE"]
    path: str = Field(min_length=1, max_length=2048)
    headers: dict[str, str] = Field(default_factory=dict)
    fingerprint_profile: Literal["none", "navigation", "same_origin_fetch"] = "none"
    json_body: Any | None = None
    body_base64: str = Field(default="", max_length=20 << 20)
    timeout_seconds: int = Field(default=90, ge=1, le=300)
    origin: str = Field(default="", max_length=512)
    referer_path: str = Field(default="/", max_length=2048)


class BrowserWebSocketOpenRequest(BaseModel):
    path: str = Field(min_length=1, max_length=2048)
    origin: str = Field(default="", max_length=512)
    timeout_seconds: int = Field(default=90, ge=1, le=300)
    transport_mode: Literal["session", "browser"] = "session"


class BrowserWebSocketSendRequest(BaseModel):
    json_body: Any


class BrowserClearanceRefreshRequest(BaseModel):
    path: str = Field(default="/", min_length=1, max_length=2048)
    origin: str = Field(default="", max_length=512)
    timeout_seconds: int | None = Field(default=None, ge=30, le=300)


class BrowserClearanceApplyRequest(BaseModel):
    clearance_cookies: str = Field(default="", max_length=32768)
    cloudflare_cookies: str = Field(default="", max_length=32768)
    user_agent: str = Field(default="", max_length=512)
    browser_profile: str = Field(default="", max_length=64)
    clearance_refreshed_at: str = Field(default="", max_length=64)
    clearance_expires_at: str = Field(default="", max_length=64)

    @model_validator(mode="after")
    def require_cookies(self) -> BrowserClearanceApplyRequest:
        if not self.clearance_cookies.strip() and not self.cloudflare_cookies.strip():
            raise ValueError("clearance cookies are required")
        return self


@dataclass
class _Entry:
    stack: ExitStack
    browser: BrowserSession
    lock: threading.Lock
    ttl_seconds: int
    expires_at: float
    origins: tuple[str, ...]
    proxy_url: str
    binding_id: str
    websockets: dict[str, Any] = field(default_factory=dict)


class BrowserSessionManager:
    def __init__(self) -> None:
        self._entries: dict[str, _Entry] = {}
        self._lock = threading.Lock()

    def open(self, request: BrowserSessionOpenRequest) -> tuple[str, _Entry]:
        origin = _allowed_origin(request.origin)
        origins = tuple(
            dict.fromkeys([origin, *(_allowed_origin(value) for value in request.origins)])
        )
        domains = _cookie_domains(origins, request.cookie_domains)
        stack = ExitStack()
        try:
            proxy_payload: dict[str, Any] = {
                "proxy_url": request.proxy_url,
                "dynamic_proxy": request.dynamic_proxy,
                "proxy_affinity_key": request.proxy_affinity_key,
                "strict_proxy_affinity": request.strict_proxy_affinity,
                "proxy_node_offset": request.proxy_node_offset,
            }
            if request.proxy_pool is not None:
                proxy_payload["proxy_pool"] = request.proxy_pool
            proxy_url = stack.enter_context(
                proxy_lease(check_url=origin, **proxy_parameters(proxy_payload))
            )
            browser = stack.enter_context(
                BrowserSession(
                    origin=origin,
                    credential={"user_agent": request.user_agent},
                    proxy_url=proxy_url,
                    profile=BrowserSessionProfile(
                        impersonate=request.browser_profile,
                        http_version=request.http_version,
                    ),
                    cookie_domains=domains,
                    initial_cookies=request.cookies,
                    bearer_token=request.bearer_token,
                )
            )
            ttl = request.ttl_seconds or settings().browser_transport_session_ttl_seconds
            ttl = max(30, min(900, ttl))
            entry = _Entry(
                stack,
                browser,
                threading.Lock(),
                ttl,
                time.monotonic() + ttl,
                origins,
                proxy_url,
                _binding_id(
                    origin,
                    proxy_url,
                    browser.user_agent,
                    browser.profile.impersonate,
                    request.proxy_affinity_key,
                    request.clearance_revision,
                ),
            )
            session_id = uuid4().hex
            with self._lock:
                expired = self._expired_locked()
                self._entries[session_id] = entry
            _close_entries(expired)
            return session_id, entry
        except BaseException:
            stack.close()
            raise

    @contextmanager
    def lease(self, session_id: str) -> Iterator[_Entry]:
        entry = self._claim(session_id)
        try:
            yield entry
        finally:
            entry.lock.release()

    def close(self, session_id: str) -> dict[str, Any] | None:
        with self._lock:
            entry = self._entries.pop(session_id, None)
        if entry is None:
            return None
        with entry.lock:
            _close_websockets(entry)
            patch = entry.browser.credential_patch()
            entry.stack.close()
            return patch

    def close_all(self) -> None:
        with self._lock:
            entries = list(self._entries.values())
            self._entries.clear()
        _close_entries(entries)

    def _claim(self, session_id: str) -> _Entry:
        if not session_id or len(session_id) != 32:
            raise KeyError(session_id)
        with self._lock:
            expired = self._expired_locked()
            entry = self._entries.get(session_id)
            if entry is not None:
                entry.expires_at = time.monotonic() + entry.ttl_seconds
        _close_entries(expired)
        if entry is None:
            raise KeyError(session_id)
        entry.lock.acquire()
        return entry

    def _expired_locked(self) -> list[_Entry]:
        now = time.monotonic()
        expired_ids = [key for key, entry in self._entries.items() if entry.expires_at <= now]
        return [self._entries.pop(key) for key in expired_ids]


manager = BrowserSessionManager()
router = APIRouter(
    prefix="/internal/v1/browser-sessions",
    dependencies=[Depends(require_internal_token)],
)


@router.post("")
def open_session(request: BrowserSessionOpenRequest) -> dict[str, Any]:
    try:
        session_id, entry = manager.open(request)
        return {
            "session_id": session_id,
            "user_agent": entry.browser.user_agent,
            "browser_profile": entry.browser.profile.impersonate,
            "binding_id": entry.binding_id,
            "expires_in_seconds": entry.ttl_seconds,
        }
    except (OSError, RuntimeError, TypeError, ValueError) as error:
        raise HTTPException(status_code=400, detail=str(error)) from error


@router.post("/{session_id}/request")
def buffered_request(session_id: str, request: BrowserRequest) -> dict[str, Any]:
    try:
        with manager.lease(session_id) as entry:
            response = _request(entry, request, stream=False)
            body = response.content
            limit = settings().browser_transport_max_buffered_bytes
            if len(body) > limit:
                raise RuntimeError("browser transport response exceeds the buffered byte limit")
            return {
                "status": response.status_code,
                "content_type": response.headers.get("content-type", "application/octet-stream"),
                "headers": _response_headers(response.headers),
                "body_base64": base64.b64encode(body).decode(),
            }
    except KeyError as error:
        raise HTTPException(status_code=404, detail="browser session not found") from error
    except RequestException as error:
        raise HTTPException(
            status_code=502,
            detail=f"browser transport request failed ({type(error).__name__})",
        ) from error
    except (OSError, RuntimeError, TypeError, ValueError) as error:
        raise HTTPException(status_code=400, detail=str(error)) from error


@router.post("/{session_id}/stream", response_class=StreamingResponse)
def stream_request(session_id: str, request: BrowserRequest) -> StreamingResponse:
    try:
        lease = manager.lease(session_id)
        entry = lease.__enter__()
        response = _request(entry, request, stream=True)
    except KeyError as error:
        raise HTTPException(status_code=404, detail="browser session not found") from error
    except RequestException as error:
        if "lease" in locals():
            lease.__exit__(type(error), error, error.__traceback__)
        raise HTTPException(
            status_code=502,
            detail=f"browser transport request failed ({type(error).__name__})",
        ) from error
    except (OSError, RuntimeError, TypeError, ValueError) as error:
        if "lease" in locals():
            lease.__exit__(type(error), error, error.__traceback__)
        raise HTTPException(status_code=400, detail=str(error)) from error

    def chunks() -> Iterator[bytes]:
        try:
            yield from response.iter_content(chunk_size=8192)
        finally:
            response.close()
            lease.__exit__(None, None, None)

    return StreamingResponse(
        chunks(),
        status_code=response.status_code,
        media_type=response.headers.get("content-type", "application/octet-stream"),
        headers=_response_headers(response.headers),
    )


@router.delete("/{session_id}")
def close_session(session_id: str) -> dict[str, Any]:
    patch = manager.close(session_id)
    return {"closed": patch is not None, "context_patch": patch}


@router.post("/{session_id}/clearance/refresh")
def refresh_session_clearance(
    session_id: str,
    request: BrowserClearanceRefreshRequest,
) -> dict[str, Any]:
    try:
        with manager.lease(session_id) as entry:
            origin = _request_origin(entry, request.origin)
            target_url = origin + _relative_path(request.path)
            patch = refresh_clearance(
                browser=entry.browser,
                proxy_url=entry.proxy_url,
                target_url=target_url,
                timeout_seconds=request.timeout_seconds,
            )
            return {"binding_id": entry.binding_id, "context_patch": patch}
    except KeyError as error:
        raise HTTPException(status_code=404, detail="browser session not found") from error
    except (OSError, RuntimeError, TypeError, ValueError) as error:
        raise HTTPException(status_code=502, detail=str(error)) from error


@router.put("/{session_id}/clearance")
def apply_session_clearance(
    session_id: str,
    request: BrowserClearanceApplyRequest,
) -> dict[str, Any]:
    try:
        with manager.lease(session_id) as entry:
            patch = entry.browser.apply_clearance_context(request.model_dump())
            return {"binding_id": entry.binding_id, "context_patch": patch}
    except KeyError as error:
        raise HTTPException(status_code=404, detail="browser session not found") from error
    except (RuntimeError, TypeError, ValueError) as error:
        raise HTTPException(status_code=400, detail=str(error)) from error


@router.post("/{session_id}/websockets")
def open_websocket(session_id: str, request: BrowserWebSocketOpenRequest) -> dict[str, Any]:
    try:
        with manager.lease(session_id) as entry:
            target = _websocket_url(entry, request.origin, request.path)
            headers = {
                "Origin": entry.browser.origin,
                "Cache-Control": "no-cache",
                "Pragma": "no-cache",
            }
            if request.transport_mode == "browser":
                websocket = BrowserWebSocket(
                    browser=entry.browser,
                    proxy_url=entry.proxy_url,
                    target_url=target,
                    timeout_seconds=request.timeout_seconds,
                )
            else:
                websocket = entry.browser.client.ws_connect(
                    target, headers=headers, timeout=request.timeout_seconds
                )
            websocket_id = uuid4().hex
            entry.websockets[websocket_id] = websocket
            return {"websocket_id": websocket_id}
    except KeyError as error:
        raise HTTPException(status_code=404, detail="browser session not found") from error
    except Exception as error:
        raise HTTPException(
            status_code=502,
            detail=f"browser WebSocket open failed ({type(error).__name__})",
        ) from error


@router.post("/{session_id}/websockets/{websocket_id}/send")
def send_websocket(
    session_id: str,
    websocket_id: str,
    request: BrowserWebSocketSendRequest,
) -> dict[str, Any]:
    try:
        with manager.lease(session_id) as entry:
            websocket = _websocket(entry, websocket_id)
            websocket.send_json(request.json_body)
            return {"sent": True}
    except KeyError as error:
        raise HTTPException(status_code=404, detail="browser WebSocket not found") from error
    except Exception as error:
        raise HTTPException(
            status_code=502,
            detail=f"browser WebSocket send failed ({type(error).__name__})",
        ) from error


@router.post("/{session_id}/websockets/{websocket_id}/receive")
def receive_websocket(session_id: str, websocket_id: str) -> dict[str, Any]:
    try:
        with manager.lease(session_id) as entry:
            websocket = _websocket(entry, websocket_id)
            body, flags = websocket.recv()
            limit = settings().browser_transport_max_buffered_bytes
            if len(body) > limit:
                raise RuntimeError("browser WebSocket frame exceeds the buffered byte limit")
            return {
                "body_base64": base64.b64encode(body).decode(),
                "flags": int(flags),
            }
    except KeyError as error:
        raise HTTPException(status_code=404, detail="browser WebSocket not found") from error
    except Exception as error:
        raise HTTPException(
            status_code=502,
            detail=f"browser WebSocket receive failed ({type(error).__name__})",
        ) from error


@router.delete("/{session_id}/websockets/{websocket_id}")
def close_websocket(session_id: str, websocket_id: str) -> dict[str, Any]:
    try:
        with manager.lease(session_id) as entry:
            websocket = entry.websockets.pop(websocket_id, None)
            if websocket is not None:
                websocket.close()
            return {"closed": websocket is not None}
    except KeyError as error:
        raise HTTPException(status_code=404, detail="browser session not found") from error


def _request(entry: _Entry, request: BrowserRequest, *, stream: bool):
    path = _relative_path(request.path)
    origin = _request_origin(entry, request.origin)
    headers = _request_headers(entry, request, origin)
    kwargs: dict[str, Any] = {
        "headers": headers,
        "timeout": request.timeout_seconds,
        "stream": stream,
    }
    if request.json_body is not None:
        kwargs["json"] = request.json_body
    if request.body_base64:
        if request.json_body is not None:
            raise ValueError("browser request cannot contain both JSON and binary bodies")
        try:
            kwargs["data"] = base64.b64decode(request.body_base64, validate=True)
        except ValueError as error:
            raise ValueError("browser request binary body is not valid base64") from error
    return entry.browser.client.request(request.method, f"{origin}{path}", **kwargs)


def _allowed_origin(value: str) -> str:
    parsed = urlparse(value.strip())
    if (
        parsed.scheme.lower() != "https"
        or not parsed.hostname
        or parsed.username
        or parsed.password
    ):
        raise ValueError("browser transport origin must be an HTTPS origin")
    if parsed.path not in {"", "/"} or parsed.query or parsed.fragment:
        raise ValueError("browser transport origin cannot contain a path, query, or fragment")
    origin = f"https://{parsed.hostname.lower()}"
    if parsed.port and parsed.port != 443:
        origin += f":{parsed.port}"
    allowed = {
        item.strip().rstrip("/").lower()
        for item in settings().browser_transport_allowed_origins.split(",")
        if item.strip()
    }
    if origin.lower() not in allowed:
        raise ValueError("browser transport origin is not allowlisted")
    return origin


def _cookie_domains(origins: tuple[str, ...], values: list[str]) -> tuple[str, ...]:
    hostnames = tuple(urlparse(origin).hostname or "" for origin in origins)
    domains = values or list(hostnames)
    normalized: list[str] = []
    for value in domains:
        domain = value.strip().lower().lstrip(".")
        if not any(domain == hostname or hostname.endswith("." + domain) for hostname in hostnames):
            raise ValueError("browser cookie domain must contain a declared session origin")
        normalized.append("." + domain)
    return tuple(dict.fromkeys(normalized))


def _request_origin(entry: _Entry, value: str) -> str:
    if not value.strip():
        return entry.browser.origin
    origin = _allowed_origin(value)
    if origin not in entry.origins:
        raise ValueError("browser request origin was not declared when the session opened")
    return origin


def _relative_path(value: str) -> str:
    parsed = urlparse(value)
    if not value.startswith("/") or value.startswith("//") or parsed.scheme or parsed.netloc:
        raise ValueError("browser transport path must be relative to the session origin")
    if "\\" in value or any(part == ".." for part in parsed.path.split("/")):
        raise ValueError("browser transport path is invalid")
    return value


def _websocket_url(entry: _Entry, value: str, path: str) -> str:
    origin = _request_origin(entry, value)
    return "wss://" + urlparse(origin).netloc + _relative_path(path)


def _binding_id(
    origin: str,
    proxy_url: str,
    user_agent: str,
    browser_profile: str,
    affinity_key: str,
    clearance_revision: str,
) -> str:
    material = (
        f"{origin}\0{proxy_url}\0{user_agent}\0{browser_profile}\0"
        f"{affinity_key.strip()}\0{clearance_revision.strip()}"
    ).encode()
    return hashlib.sha256(material).hexdigest()


def _websocket(entry: _Entry, websocket_id: str) -> Any:
    if not websocket_id or len(websocket_id) != 32:
        raise KeyError(websocket_id)
    websocket = entry.websockets.get(websocket_id)
    if websocket is None:
        raise KeyError(websocket_id)
    return websocket


def _request_headers(entry: _Entry, request: BrowserRequest, origin: str) -> dict[str, str]:
    headers = dict(_REQUEST_FINGERPRINTS[request.fingerprint_profile])
    if request.fingerprint_profile == "same_origin_fetch":
        headers["Origin"] = origin
        headers["Referer"] = origin + _relative_path(request.referer_path)
    for name, value in request.headers.items():
        normalized = name.strip().lower()
        if normalized in _BLOCKED_HEADERS or not normalized or len(normalized) > 128:
            raise ValueError("browser transport request contains a blocked header")
        if request.fingerprint_profile != "none" and normalized in _FINGERPRINT_HEADERS:
            raise ValueError("browser transport request cannot override fingerprint headers")
        if len(value) > 8192 or "\r" in value or "\n" in value:
            raise ValueError("browser transport request header is invalid")
        headers[name] = value
    return headers


def _response_headers(values: Any) -> dict[str, str]:
    return {
        name.lower(): str(value)
        for name, value in values.items()
        if name.lower() in _RETURNED_HEADERS
    }


def _close_entries(entries: list[_Entry]) -> None:
    for entry in entries:
        with entry.lock:
            _close_websockets(entry)
            entry.stack.close()


def _close_websockets(entry: _Entry) -> None:
    for websocket in entry.websockets.values():
        with suppress(Exception):
            websocket.close()
    entry.websockets.clear()
