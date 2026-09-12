from __future__ import annotations

import asyncio
import queue
import re
import threading
from collections.abc import AsyncIterator, Iterable, Mapping
from http.cookies import CookieError, SimpleCookie
from typing import Any
from urllib.parse import SplitResult, urlsplit, urlunsplit

from curl_cffi.requests import Session as CurlSession

from ..config import settings as core_settings
from .actions import (
    ActionBinding,
    ProviderAction,
    legacy_inference_action_bindings,
    lifecycle_action_bindings,
)
from .base import API_TRANSPORT

_COOKIE_NAME = re.compile(r"^[!#$%&'*+\-.^_`|~0-9A-Za-z]{1,128}$")
_HEADER_NAME = re.compile(r"^[!#$%&'*+\-.^_`|~0-9A-Za-z]{1,128}$")
_SAFE_PROFILE = re.compile(r"^[a-z][a-z0-9._-]{0,31}$", re.IGNORECASE)
_DEFAULT_PROFILE = "chrome146"
_MAX_SSE_LINE_BYTES = 2 * 1024 * 1024
_QUEUE_STOP = object()


class ApiTransportError(RuntimeError):
    """A bounded direct HTTP failure, kept separate from provider protocol errors."""

    def __init__(self, message: str, *, status: int = 502) -> None:
        super().__init__(message)
        self.status = status


class ApiActionError(RuntimeError):
    """An upstream status preserved across the internal Action boundary."""

    def __init__(self, message: str, *, status: int = 502, body: str = "") -> None:
        super().__init__(message)
        self.status = status
        self.body = " ".join(str(body or "").split())[:16_384]


def require_api_success(result: Mapping[str, Any], operation: str) -> None:
    """Fail with the bounded upstream status before a provider parses the body."""

    status = _result_status(result)
    if status < 200 or status >= 300:
        raise ApiActionError(
            f"{operation} returned HTTP {status}",
            status=status,
            body=str(result.get("body") or ""),
        )


def _result_status(result: Mapping[str, Any]) -> int:
    try:
        return int(result.get("status") or 502)
    except (TypeError, ValueError):
        return 502


def allowlisted_base_url(
    value: str | None,
    default: str,
    host_suffixes: tuple[str, ...],
) -> str:
    candidate = str(value or default).strip().rstrip("/")
    parsed = urlsplit(candidate)
    host = (parsed.hostname or "").lower().rstrip(".")
    allowed = any(host == suffix or host.endswith("." + suffix) for suffix in host_suffixes)
    if (
        parsed.scheme != "https"
        or not host
        or not allowed
        or parsed.username
        or parsed.password
        or parsed.port is not None
        or parsed.path not in {"", "/"}
        or parsed.query
        or parsed.fragment
    ):
        raise ValueError("API base URL is not allowlisted")
    return f"https://{host}"


def same_origin_path(value: str, *, allow_empty: bool = False) -> str:
    normalized = str(value or "").strip()
    if not normalized and allow_empty:
        return ""
    parsed = urlsplit(normalized)
    path = parsed.path
    segments = path.split("/")
    if (
        not path.startswith("/")
        or not path
        or parsed.scheme
        or parsed.netloc
        or "\\" in normalized
        or "\x00" in normalized
        or any(segment in {".", ".."} for segment in segments)
    ):
        raise ValueError("API endpoint must be a same-origin path")
    return urlunsplit(SplitResult("", "", path, parsed.query, ""))


def credential_cookie_map(
    credential: Mapping[str, Any],
    *cookie_fields: str,
) -> dict[str, str]:
    values: dict[str, str] = {}
    fields = (
        "cookies",
        "cookie",
        "session_cookies",
        "cloudflare_cookies",
        "cf_cookies",
        *cookie_fields,
    )
    for field in fields:
        source = credential.get(field)
        if isinstance(source, Mapping):
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


def cookie_header(values: Mapping[str, str]) -> str:
    return "; ".join(f"{name}={value}" for name, value in values.items())


def token_from_credential(credential: Mapping[str, Any], *names: str) -> str:
    fields = names or ("token", "access_token", "jwt")
    for name in fields:
        value = str(credential.get(name) or "").strip()
        if value:
            return value
    return ""


def api_headers(
    base_url: str,
    credential: Mapping[str, Any],
    *,
    extra: Mapping[str, Any] | None = None,
    accept: str = "application/json, */*",
    content_type: str | None = "application/json",
    bearer_names: tuple[str, ...] = ("token", "access_token", "jwt"),
    cookie_fields: tuple[str, ...] = (),
) -> dict[str, str]:
    headers = {
        "Accept": accept,
        "Origin": base_url,
        "Referer": base_url.rstrip("/") + "/",
        "User-Agent": str(credential.get("user_agent") or core_settings().provider_user_agent),
    }
    if content_type:
        headers["Content-Type"] = content_type
    token = token_from_credential(credential, *bearer_names) if bearer_names else ""
    if token:
        headers["Authorization"] = f"Bearer {token}"
    cookies = credential_cookie_map(credential, *cookie_fields)
    if cookies:
        headers["Cookie"] = cookie_header(cookies)
    if extra:
        headers.update(_safe_headers(extra))
    return headers


def merge_allowed_headers(
    headers: Mapping[str, Any],
    allowed_names: Iterable[str],
) -> dict[str, str]:
    allowed = {str(name).lower() for name in allowed_names}
    return {
        str(name): str(value)
        for name, value in headers.items()
        if str(name).lower() in allowed and str(value).strip()
    }


def merge_credential_patch(
    credential: dict[str, Any],
    result: Mapping[str, Any],
) -> None:
    """Apply only the bounded cookie patch produced by this transport."""

    patch = result.get("credential_patch")
    if not isinstance(patch, Mapping):
        return
    incoming = patch.get("cookies")
    if not isinstance(incoming, Mapping):
        return
    current = credential.get("cookies")
    merged = dict(current) if isinstance(current, Mapping) else {}
    for name, value in incoming.items():
        normalized_name = str(name).strip()
        normalized_value = str(value).strip()
        if (
            _COOKIE_NAME.fullmatch(normalized_name)
            and normalized_value
            and "\r" not in normalized_value
            and "\n" not in normalized_value
        ):
            merged[normalized_name] = normalized_value
            if normalized_name in credential and isinstance(credential.get(normalized_name), str):
                credential[normalized_name] = normalized_value
    if merged:
        credential["cookies"] = merged


def api_request_sync(
    base_url: str,
    method: str,
    path: str,
    *,
    headers: Mapping[str, Any] | None = None,
    body: str | bytes = "",
    proxy_url: str = "",
    timeout_seconds: float | None = None,
    impersonate: str | None = None,
    max_response_bytes: int | None = None,
) -> dict[str, Any]:
    normalized_method = _method(method)
    url = _url(base_url, path)
    request_headers = _safe_headers(headers or {})
    request_body = body if normalized_method not in {"GET", "HEAD"} else None
    with (
        CurlSession(impersonate=_impersonation(impersonate)) as client,
        client.stream(
            normalized_method,
            url,
            data=request_body,
            headers=request_headers,
            proxy=proxy_url or None,
            timeout=timeout_seconds or core_settings().registration_timeout_seconds,
        ) as response,
    ):
        content = _read_response(response, max_response_bytes=max_response_bytes)
        return _response(response, content)


def api_multipart_request_sync(
    base_url: str,
    method: str,
    path: str,
    *,
    file_field: str,
    filename: str,
    content: bytes,
    mime_type: str,
    headers: Mapping[str, Any] | None = None,
    form: Mapping[str, Any] | None = None,
    proxy_url: str = "",
    timeout_seconds: float | None = None,
    impersonate: str | None = None,
    max_response_bytes: int | None = None,
) -> dict[str, Any]:
    normalized_method = _method(method)
    if normalized_method not in {"POST", "PUT"}:
        raise ValueError("API multipart method is not supported")
    url = _url(base_url, path)
    request_headers = _safe_headers(headers or {})
    request_headers.pop("Content-Type", None)
    request_headers.pop("content-type", None)
    files = {file_field: (filename, content, mime_type)}
    with (
        CurlSession(impersonate=_impersonation(impersonate)) as client,
        client.stream(
            normalized_method,
            url,
            data=dict(form or {}),
            files=files,
            headers=request_headers,
            proxy=proxy_url or None,
            timeout=timeout_seconds or core_settings().registration_timeout_seconds,
        ) as response,
    ):
        return _response(response, _read_response(response, max_response_bytes=max_response_bytes))


def api_provider_put_sync(
    url: str,
    content: bytes,
    *,
    headers: Mapping[str, Any] | None = None,
    proxy_url: str = "",
    timeout_seconds: float | None = None,
) -> dict[str, Any]:
    parsed = _provider_issued_url(url)
    request_headers = _safe_headers(headers or {})
    with (
        CurlSession(impersonate=_DEFAULT_PROFILE) as client,
        client.stream(
            "PUT",
            urlunsplit(parsed),
            data=content,
            headers=request_headers,
            proxy=proxy_url or None,
            timeout=timeout_seconds or core_settings().registration_timeout_seconds,
        ) as response,
    ):
        # Object-storage upload responses are not part of the public contract. Read only
        # the bounded diagnostic body so a failed upload cannot accumulate indefinitely.
        return _response(response, _read_response(response, max_response_bytes=64 * 1024))


async def api_stream(
    base_url: str,
    method: str,
    path: str,
    *,
    headers: Mapping[str, Any] | None = None,
    body: str | bytes = "",
    proxy_url: str = "",
    timeout_seconds: float | None = None,
    impersonate: str | None = None,
    max_response_bytes: int | None = None,
    include_unprefixed_lines: bool = False,
) -> AsyncIterator[dict[str, Any]]:
    """Read SSE with a bounded cross-thread queue and cancellation-aware teardown."""

    events: queue.Queue[dict[str, Any] | object] = queue.Queue(maxsize=64)
    stop = threading.Event()
    response_holder: list[Any] = [None]

    def publish(event: dict[str, Any] | object) -> bool:
        while not stop.is_set():
            try:
                events.put(event, timeout=0.2)
                return True
            except queue.Full:
                continue
        return False

    def worker() -> None:
        try:
            normalized_method = _method(method)
            url = _url(base_url, path)
            request_headers = _safe_headers(headers or {})
            request_body = body if normalized_method not in {"GET", "HEAD"} else None
            with (
                CurlSession(impersonate=_impersonation(impersonate)) as client,
                client.stream(
                    normalized_method,
                    url,
                    data=request_body,
                    headers=request_headers,
                    proxy=proxy_url or None,
                    timeout=timeout_seconds or core_settings().registration_timeout_seconds,
                ) as response,
            ):
                response_holder[0] = response
                if not publish(
                    {
                        "type": "status",
                        "status": int(response.status_code),
                        "content_type": str(response.headers.get("content-type") or ""),
                    }
                ):
                    return
                credential_patch = credential_patch_from_response(response)
                if credential_patch and not publish(
                    {"type": "credential_patch", "data": credential_patch}
                ):
                    return
                if response.status_code < 200 or response.status_code >= 300:
                    body_bytes = _read_response(response, max_response_bytes=max_response_bytes)
                    publish({"type": "error", "data": _decode(body_bytes)[:16_384]})
                    return
                for raw_line in response.iter_lines():
                    line_bytes = (
                        raw_line
                        if isinstance(raw_line, bytes)
                        else str(raw_line).encode("utf-8", "replace")
                    )
                    line_limit = min(
                        max_response_bytes or core_settings().browser_transport_max_buffered_bytes,
                        _MAX_SSE_LINE_BYTES,
                    )
                    if len(line_bytes) > line_limit:
                        raise ApiTransportError("API SSE event exceeds the configured byte limit")
                    line = _decode(line_bytes)
                    value = ""
                    if line.startswith("data:"):
                        value = line[5:].strip()
                    elif (
                        include_unprefixed_lines
                        and line.strip()
                        and not line.startswith((":", "event:", "id:", "retry:"))
                    ):
                        value = line.strip()
                    if value and not publish({"type": "data", "data": value}):
                        return
        except Exception as error:  # noqa: BLE001 - normalized at the API channel boundary
            publish(
                {
                    "type": "error",
                    "data": f"API channel failed ({type(error).__name__})",
                }
            )
        finally:
            publish(_QUEUE_STOP)

    task = asyncio.create_task(asyncio.to_thread(worker))
    try:
        while True:
            try:
                event = await asyncio.to_thread(events.get, True, 0.2)
            except queue.Empty:
                if task.done() and events.empty():
                    break
                continue
            if event is _QUEUE_STOP:
                break
            if isinstance(event, dict):
                yield event
    finally:
        stop.set()
        response = response_holder[0]
        close = getattr(response, "close", None)
        if callable(close):
            close()
        if not task.done():
            task.cancel()
        await asyncio.gather(task, return_exceptions=True)


def api_action_bindings(
    provider: Any,
    handler: Any,
) -> tuple[ActionBinding, ...]:
    """Register the provider's declared inference actions on the direct API channel."""

    bindings: list[ActionBinding] = list(lifecycle_action_bindings(provider))
    bindings.extend(legacy_inference_action_bindings(provider))
    for declared_action in provider.manifest.inference_actions:
        action = ProviderAction.parse(declared_action)
        execute = getattr(handler, "execute", None)
        stream = getattr(handler, "stream", None) if action is ProviderAction.CHAT else None
        if execute is None and stream is None:
            raise ValueError(
                "API handler has no executor: "
                f"provider={provider.manifest.id} action={action.value}"
            )
        bindings.append(
            ActionBinding(
                action=action,
                channel=API_TRANSPORT,
                execute=execute,
                stream=stream,
                legacy_operation=action.default_legacy_operation,
            )
        )
    return tuple(bindings)


def _method(value: str) -> str:
    normalized = str(value or "").strip().upper()
    if normalized not in {"GET", "POST", "PUT", "PATCH", "HEAD"}:
        raise ValueError("API method is not allowlisted")
    return normalized


def _url(base_url: str, path: str) -> str:
    base = urlsplit(str(base_url).strip().rstrip("/"))
    if base.scheme != "https" or not base.hostname or base.username or base.password:
        raise ValueError("API request base URL is invalid")
    target = urlsplit(same_origin_path(path))
    return urlunsplit(SplitResult(base.scheme, base.netloc, target.path, target.query, ""))


def _provider_issued_url(value: str) -> SplitResult:
    parsed = urlsplit(str(value or "").strip())
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("provider-issued upload URL must use HTTPS")
    return parsed


def _safe_headers(value: Mapping[str, Any]) -> dict[str, str]:
    result: dict[str, str] = {}
    for name, raw_value in value.items():
        normalized_name = str(name).strip()
        normalized_value = str(raw_value)
        if not _HEADER_NAME.fullmatch(normalized_name):
            raise ValueError("API header name is invalid")
        if "\r" in normalized_value or "\n" in normalized_value:
            raise ValueError("API header value is invalid")
        result[normalized_name] = normalized_value
    return result


def _read_response(response: Any, *, max_response_bytes: int | None) -> bytes:
    limit = max_response_bytes or core_settings().browser_transport_max_buffered_bytes
    if limit < 1:
        raise ValueError("API response byte limit must be positive")
    chunks: list[bytes] = []
    total = 0
    iterator = getattr(response, "iter_content", None)
    if callable(iterator):
        source = iterator(chunk_size=64 * 1024)
    else:
        source = (getattr(response, "content", b""),)
    for chunk in source:
        data = chunk.encode() if isinstance(chunk, str) else bytes(chunk)
        total += len(data)
        if total > limit:
            raise ApiTransportError("API response exceeds the configured byte limit")
        chunks.append(data)
    return b"".join(chunks)


def _response(response: Any, body: bytes) -> dict[str, Any]:
    headers = getattr(response, "headers", {}) or {}
    content_type = str(headers.get("content-type") or "")
    result: dict[str, Any] = {
        "status": int(getattr(response, "status_code", 502) or 502),
        "body": _decode(body),
        "content_type": content_type,
        "transport_mode": API_TRANSPORT,
    }
    credential_patch = credential_patch_from_response(response)
    if credential_patch:
        result["credential_patch"] = credential_patch
    return result


def credential_patch_from_response(response: Any) -> dict[str, dict[str, str]]:
    """Extract only non-empty, syntactically valid cookies from Set-Cookie."""

    headers = getattr(response, "headers", {}) or {}
    values: list[str] = []
    get_list = getattr(headers, "get_list", None)
    if callable(get_list):
        try:
            listed = get_list("set-cookie")
        except (AttributeError, TypeError, ValueError):
            listed = ()
        if isinstance(listed, (list, tuple)):
            values.extend(str(value) for value in listed)
    if not values:
        try:
            raw = headers.get("set-cookie")
        except (AttributeError, TypeError):
            raw = None
        if isinstance(raw, (list, tuple)):
            values.extend(str(value) for value in raw)
        elif raw:
            values.extend(_split_set_cookie_header(str(raw)))

    cookies: dict[str, str] = {}
    for value in values:
        parsed = SimpleCookie()
        try:
            parsed.load(value)
        except CookieError:
            continue
        for name, morsel in parsed.items():
            normalized_name = str(name).strip()
            normalized_value = str(morsel.value).strip()
            if (
                _COOKIE_NAME.fullmatch(normalized_name)
                and normalized_value
                and "\r" not in normalized_value
                and "\n" not in normalized_value
            ):
                cookies[normalized_name] = normalized_value
    return {"cookies": cookies} if cookies else {}


def _split_set_cookie_header(value: str) -> list[str]:
    return re.split(
        r",\s*(?=[!#$%&'*+\-.^_\x60|~0-9A-Za-z]{1,128}=)",
        value,
    )


def _decode(value: bytes) -> str:
    return value.decode("utf-8", errors="replace")


def _impersonation(value: str | None) -> str:
    candidate = str(value or _DEFAULT_PROFILE).strip()
    return candidate if _SAFE_PROFILE.fullmatch(candidate) else _DEFAULT_PROFILE
