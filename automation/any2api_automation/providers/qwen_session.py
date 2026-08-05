from __future__ import annotations

import hashlib
import json
import re
from typing import Any
from urllib.parse import urlparse

QWEN_BROWSER_STATE_SCHEMA_VERSION = 1
MAX_BROWSER_STATE_BYTES = 512 * 1024
MAX_COOKIES = 128
MAX_STORAGE_ITEMS = 128
MAX_COOKIE_VALUE_LENGTH = 8_192
MAX_STORAGE_VALUE_LENGTH = 256 * 1024
_COOKIE_NAME = re.compile(r"[!#$%&'*+.^_`|~0-9A-Za-z-]{1,256}")
_SAME_SITE = {"Strict", "Lax", "None"}


def normalize_browser_state(value: Any, base_url: str) -> dict[str, Any]:
    if value in (None, {}):
        return {}
    if not isinstance(value, dict):
        raise TypeError("Qwen browser state must be an object")
    _require_size(value)
    if "storage_state" in value:
        if value.get("schema_version") != QWEN_BROWSER_STATE_SCHEMA_VERSION:
            raise ValueError("Qwen browser state schema version is unsupported")
        raw_state = value.get("storage_state")
    else:
        raw_state = value
    if not isinstance(raw_state, dict):
        raise TypeError("Qwen browser storage state must be an object")

    expected = urlparse(base_url)
    if expected.scheme != "https" or not expected.hostname:
        raise ValueError("Qwen browser state requires an HTTPS provider origin")
    expected_origin = f"{expected.scheme}://{expected.netloc}"
    cookie_domains = {_registrable_domain(expected.hostname), "alibaba.com"}

    raw_cookies = raw_state.get("cookies", [])
    raw_origins = raw_state.get("origins", [])
    if not isinstance(raw_cookies, list) or len(raw_cookies) > MAX_COOKIES:
        raise ValueError("Qwen browser state contains too many cookies")
    if not isinstance(raw_origins, list) or len(raw_origins) > 2:
        raise ValueError("Qwen browser state contains too many origins")

    cookies = [_normalize_cookie(item, cookie_domains) for item in raw_cookies]
    origins = [_normalize_origin(item, expected_origin) for item in raw_origins]
    normalized = {
        "schema_version": QWEN_BROWSER_STATE_SCHEMA_VERSION,
        "storage_state": {"cookies": cookies, "origins": origins},
    }
    _require_size(normalized)
    return normalized


def playwright_storage_state(value: Any, base_url: str) -> dict[str, Any] | None:
    normalized = normalize_browser_state(value, base_url)
    return normalized.get("storage_state") if normalized else None


def browser_state_digest(value: Any, base_url: str) -> str:
    normalized = normalize_browser_state(value, base_url)
    if not normalized:
        return ""
    encoded = json.dumps(normalized, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(encoded).hexdigest()


def browser_state_cookie_map(value: Any, base_url: str) -> dict[str, str]:
    normalized = normalize_browser_state(value, base_url)
    if not normalized:
        return {}
    result: dict[str, str] = {}
    for cookie in normalized["storage_state"]["cookies"]:
        result[cookie["name"]] = cookie["value"]
    return result


def _normalize_cookie(value: Any, allowed_domains: set[str]) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise TypeError("Qwen browser state cookie must be an object")
    name = str(value.get("name") or "")
    cookie_value = str(value.get("value") or "")
    domain = str(value.get("domain") or "").strip().lower()
    path = str(value.get("path") or "/")
    if not _COOKIE_NAME.fullmatch(name):
        raise ValueError("Qwen browser state contains an invalid cookie name")
    if len(cookie_value) > MAX_COOKIE_VALUE_LENGTH or "\r" in cookie_value or "\n" in cookie_value:
        raise ValueError("Qwen browser state contains an invalid cookie value")
    bare_domain = domain.lstrip(".")
    if not any(
        bare_domain == allowed or bare_domain.endswith("." + allowed) for allowed in allowed_domains
    ):
        raise ValueError("Qwen browser state contains a cross-origin cookie")
    if not path.startswith("/") or len(path) > 2_048:
        raise ValueError("Qwen browser state contains an invalid cookie path")

    result: dict[str, Any] = {
        "name": name,
        "value": cookie_value,
        "domain": domain,
        "path": path,
        "expires": _finite_number(value.get("expires"), -1),
        "httpOnly": bool(value.get("httpOnly", False)),
        "secure": bool(value.get("secure", True)),
    }
    same_site = str(value.get("sameSite") or "Lax").title()
    if same_site not in _SAME_SITE:
        raise ValueError("Qwen browser state contains an invalid SameSite value")
    result["sameSite"] = same_site
    return result


def _normalize_origin(value: Any, expected_origin: str) -> dict[str, Any]:
    if not isinstance(value, dict) or str(value.get("origin") or "") != expected_origin:
        raise ValueError("Qwen browser state contains a cross-origin storage entry")
    raw_items = value.get("localStorage", [])
    if not isinstance(raw_items, list) or len(raw_items) > MAX_STORAGE_ITEMS:
        raise ValueError("Qwen browser state contains too many local storage items")
    items: list[dict[str, str]] = []
    for item in raw_items:
        if not isinstance(item, dict):
            raise TypeError("Qwen browser local storage item must be an object")
        name = str(item.get("name") or "")
        item_value = str(item.get("value") or "")
        if not name or len(name) > 256 or len(item_value) > MAX_STORAGE_VALUE_LENGTH:
            raise ValueError("Qwen browser state contains an invalid local storage item")
        items.append({"name": name, "value": item_value})
    result: dict[str, Any] = {"origin": expected_origin, "localStorage": items}
    if "indexedDB" in value:
        indexed_db = value["indexedDB"]
        if not isinstance(indexed_db, list):
            raise ValueError("Qwen browser IndexedDB state must be an array")
        result["indexedDB"] = json.loads(json.dumps(indexed_db))
    return result


def _registrable_domain(hostname: str) -> str:
    labels = hostname.lower().split(".")
    if len(labels) < 2:
        raise ValueError("Qwen provider host is invalid")
    return ".".join(labels[-2:])


def _finite_number(value: Any, fallback: float) -> float:
    if not isinstance(value, (int, float)):
        return fallback
    number = float(value)
    return number if -1 <= number <= 32_503_680_000 else fallback


def _require_size(value: Any) -> None:
    try:
        size = len(json.dumps(value, ensure_ascii=True, separators=(",", ":")).encode())
    except (TypeError, ValueError) as error:
        raise ValueError("Qwen browser state must contain JSON values") from error
    if size > MAX_BROWSER_STATE_BYTES:
        raise ValueError("Qwen browser state exceeds the encrypted credential size limit")
