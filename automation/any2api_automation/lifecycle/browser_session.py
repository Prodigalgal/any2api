from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import UTC, datetime
from http.cookies import CookieError, SimpleCookie
from typing import Any, Self
from urllib.parse import urlparse

from curl_cffi import requests

CLEARANCE_COOKIE_NAMES = frozenset(
    {
        "cf_clearance",
        "__cf_bm",
        "_cfuvid",
        "cf_chl_2",
        "cf_chl_rc_i",
        "aws-waf-token",
    }
)
_CF_COOKIE_NAMES = frozenset(name for name in CLEARANCE_COOKIE_NAMES if name != "aws-waf-token")
_DEFAULT_USER_AGENTS = {
    "chrome136": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/136.0.0.0 Safari/537.36"
    ),
    "chrome131": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/131.0.0.0 Safari/537.36"
    ),
    "firefox144": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0"
    ),
    "firefox147": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:147.0) Gecko/20100101 Firefox/147.0"
    ),
}


@dataclass(frozen=True)
class BrowserSessionProfile:
    impersonate: str = "chrome136"
    http_version: str = "v2"

    def user_agent(self, credential: dict[str, Any]) -> str:
        configured = str(credential.get("user_agent") or "").strip()
        return configured or _DEFAULT_USER_AGENTS.get(
            self.impersonate, _DEFAULT_USER_AGENTS["chrome136"]
        )


class BrowserSession:
    """One browser-shaped HTTP session bound to one origin and one proxy lease."""

    def __init__(
        self,
        *,
        origin: str,
        credential: dict[str, Any],
        proxy_url: str = "",
        profile: BrowserSessionProfile | None = None,
        cookie_domains: tuple[str, ...] = (),
        initial_cookies: dict[str, str] | None = None,
        bearer_token: str = "",
    ) -> None:
        self.origin = origin.rstrip("/")
        self.profile = profile or BrowserSessionProfile()
        self.user_agent = self.profile.user_agent(credential)
        self.cookie_domains = cookie_domains or (_cookie_domain(self.origin),)
        self._clearance_domain = _cookie_domain(self.origin)
        kwargs: dict[str, Any] = {
            "impersonate": self.profile.impersonate,
            "http_version": self.profile.http_version,
        }
        if proxy_url:
            kwargs["proxies"] = {"http": proxy_url, "https": proxy_url}
        self.client = requests.Session(**kwargs)
        fingerprint_headers = {
            "User-Agent": self.user_agent,
            "Accept-Language": "en-US,en;q=0.9",
        }
        if self.profile.impersonate.startswith("chrome"):
            fingerprint_headers.update(
                {
                    "sec-ch-ua": _client_hint(self.profile.impersonate),
                    "sec-ch-ua-mobile": "?0",
                    "sec-ch-ua-platform": '"Windows"',
                }
            )
        self.client.headers.update(fingerprint_headers)
        if bearer_token:
            if "\r" in bearer_token or "\n" in bearer_token:
                raise ValueError("browser session bearer token is invalid")
            self.client.headers["Authorization"] = "Bearer " + bearer_token
        self._load_credential_cookies(credential)
        for name, value in (initial_cookies or {}).items():
            if not re.fullmatch(r"[!#$%&'*+\-.^_`|~0-9A-Za-z]{1,128}", name):
                raise ValueError("browser session cookie name is invalid")
            if not value or len(value) > 8192 or "\r" in value or "\n" in value:
                raise ValueError("browser session cookie value is invalid")
            for domain in self.cookie_domains:
                self.client.cookies.set(name, value, domain=domain, path="/")

    def __enter__(self) -> Self:
        return self

    def __exit__(self, *_: object) -> None:
        self.client.close()

    def credential_patch(self) -> dict[str, Any] | None:
        cookies = _cookie_items(self.client.cookies)
        clearance = {
            name: value for name, value in cookies.items() if name in CLEARANCE_COOKIE_NAMES
        }
        if not clearance:
            return None
        header = "; ".join(f"{name}={clearance[name]}" for name in sorted(clearance))
        result: dict[str, Any] = {
            "user_agent": self.user_agent,
            "browser_profile": self.profile.impersonate,
        }
        if any(name not in _CF_COOKIE_NAMES for name in clearance):
            result["clearance_cookies"] = header
        cloudflare = {name: value for name, value in clearance.items() if name in _CF_COOKIE_NAMES}
        if cloudflare:
            result["cloudflare_cookies"] = "; ".join(
                f"{name}={cloudflare[name]}" for name in sorted(cloudflare)
            )
        expiries = [
            int(cookie.expires)
            for cookie in _cookie_objects(self.client.cookies)
            if cookie.name in CLEARANCE_COOKIE_NAMES and cookie.expires
        ]
        if expiries:
            result["clearance_expires_at"] = datetime.fromtimestamp(
                min(expiries), tz=UTC
            ).isoformat()
        return result

    def browser_cookies(self) -> list[dict[str, Any]]:
        result: list[dict[str, Any]] = []
        for cookie in _cookie_objects(self.client.cookies):
            if not cookie.name or not cookie.value:
                continue
            domain = str(cookie.domain or "").strip() or _cookie_domain(self.origin)
            result.append(
                {
                    "name": str(cookie.name),
                    "value": str(cookie.value),
                    "domain": domain,
                    "path": str(cookie.path or "/"),
                    "secure": True,
                }
            )
        return result

    def apply_clearance_context(self, context: dict[str, Any]) -> dict[str, Any]:
        user_agent = str(context.get("user_agent") or "").strip()
        browser_profile = str(context.get("browser_profile") or "").strip()
        if user_agent and user_agent != self.user_agent:
            raise ValueError("clearance User-Agent does not match the browser session")
        if browser_profile and browser_profile != self.profile.impersonate:
            raise ValueError("clearance browser profile does not match the browser session")
        values = _parse_cookie_header(str(context.get("clearance_cookies") or ""))
        values.update(_parse_cookie_header(str(context.get("cloudflare_cookies") or "")))
        values = {name: value for name, value in values.items() if name in CLEARANCE_COOKIE_NAMES}
        if not values:
            raise ValueError("clearance context contains no supported anti-bot cookies")
        for name, value in values.items():
            self.client.cookies.set(name, value, domain=self._clearance_domain, path="/")
        patch = self.credential_patch()
        if patch is None:
            raise RuntimeError("clearance context was not applied")
        for field in ("clearance_refreshed_at", "clearance_expires_at"):
            value = str(context.get(field) or "").strip()
            if value:
                patch[field] = value
        return patch

    def _load_credential_cookies(self, credential: dict[str, Any]) -> None:
        values: dict[str, str] = {}
        for field in ("clearance_cookies", "cloudflare_cookies", "cf_cookies"):
            values.update(_parse_cookie_header(str(credential.get(field) or "")))
        for name in CLEARANCE_COOKIE_NAMES:
            value = str(credential.get(name) or "").strip()
            if value:
                values[name] = value
        for name, value in values.items():
            if name not in CLEARANCE_COOKIE_NAMES:
                continue
            self.client.cookies.set(name, value, domain=self._clearance_domain, path="/")


def attach_credential_patch(result: dict[str, Any], session: BrowserSession) -> dict[str, Any]:
    patch = session.credential_patch()
    if patch:
        result["credential_patch"] = patch
    return result


def _parse_cookie_header(value: str) -> dict[str, str]:
    if not value.strip():
        return {}
    parsed = SimpleCookie()
    try:
        parsed.load(value)
    except CookieError:
        return {}
    return {name: morsel.value for name, morsel in parsed.items()}


def _cookie_items(cookie_jar: Any) -> dict[str, str]:
    if hasattr(cookie_jar, "get_dict"):
        return {str(name): str(value) for name, value in cookie_jar.get_dict().items()}
    if hasattr(cookie_jar, "items"):
        return {str(name): str(value) for name, value in cookie_jar.items()}
    return {}


def _cookie_objects(cookie_jar: Any) -> list[Any]:
    jar = getattr(cookie_jar, "jar", cookie_jar)
    try:
        return list(jar)
    except TypeError:
        return []


def _cookie_domain(origin: str) -> str:
    hostname = urlparse(origin).hostname
    if not hostname:
        raise ValueError("browser session origin requires a hostname")
    return hostname


def _client_hint(impersonate: str) -> str:
    version = re.sub(r"\D", "", impersonate) or "136"
    return f'"Chromium";v="{version}", "Google Chrome";v="{version}", "Not.A/Brand";v="99"'
