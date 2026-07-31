"""Convert an xAI SSO cookie through the current Grok Build device flow."""
from __future__ import annotations

import base64
import binascii
import json
import re
import time
from html.parser import HTMLParser
from typing import Any
from urllib.parse import urljoin

from playwright.sync_api import TimeoutError as PlaywrightTimeoutError

ISSUER = "https://auth.x.ai"
CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
DEFAULT_SCOPES = (
    "openid profile email offline_access grok-cli:access api:access "
    "conversations:read conversations:write workspaces:read workspaces:write"
)
DEFAULT_CLIENT_VERSION = "0.2.112"
DEFAULT_CLIENT_SURFACE = "ui"
DEFAULT_REFERRER = "grok-build"


def exchange_sso_for_token(
    sso: str,
    *,
    proxy: str = "",
    session: Any | None = None,
    attempts: int = 6,
    client_id: str = CLIENT_ID,
    scopes: str = DEFAULT_SCOPES,
    http_timeout: float = 30,
    poll_timeout: float = 60,
    client_version: str = DEFAULT_CLIENT_VERSION,
    client_surface: str = DEFAULT_CLIENT_SURFACE,
    referrer: str = DEFAULT_REFERRER,
) -> dict[str, Any]:
    """Use a curl_cffi browser session to approve an OAuth device request."""
    cookie = str(sso or "").strip()
    if not cookie:
        raise ValueError("SSO cookie is required")
    if session is None:
        from curl_cffi import requests

        kwargs: dict[str, Any] = {"impersonate": "chrome131"}
        if proxy:
            kwargs["proxies"] = {"http": proxy, "https": proxy}
        session = requests.Session(**kwargs)
    _install_sso_cookies(session, cookie)
    sso_claims = _jwt_claims(cookie)

    timeout = max(10.0, float(http_timeout))
    home = session.get("https://accounts.x.ai/", timeout=timeout, allow_redirects=True)
    if "sign-in" in str(home.url or "") or "sign-up" in str(home.url or ""):
        raise RuntimeError("protocol SSO cookie was rejected")
    scopes = scopes.strip()
    max_attempts = max(1, min(12, int(attempts)))
    last_error = "device flow did not start"
    for attempt in range(1, max_attempts + 1):
        try:
            device = session.post(
                f"{ISSUER}/oauth2/device/code",
                data={
                    "client_id": client_id,
                    "scope": scopes,
                    "referrer": referrer,
                },
                headers=_device_headers(client_version, client_surface),
                timeout=timeout,
            )
            payload = _object_json(device)
            device_code = str(payload.get("device_code") or "")
            user_code = str(payload.get("user_code") or "")
            verification_url = str(
                payload.get("verification_uri_complete")
                or payload.get("verification_uri")
                or ""
            )
            if not getattr(device, "ok", False) or not device_code or not user_code or not verification_url:
                raise RuntimeError(_response_error(device, payload, "device code rejected"))

            session.get(verification_url, timeout=timeout, allow_redirects=True)
            verification = session.post(
                f"{ISSUER}/oauth2/device/verify",
                data={"user_code": user_code},
                headers={
                    "content-type": "application/x-www-form-urlencoded",
                    "origin": "https://accounts.x.ai",
                    "referer": verification_url,
                    "accept": (
                        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                    ),
                },
                timeout=timeout,
                allow_redirects=True,
            )
            if not getattr(verification, "ok", False) or "consent" not in str(verification.url or ""):
                raise RuntimeError(_response_error(verification, {}, "device verification rejected"))

            consent = _parse_consent_page(
                str(getattr(verification, "text", "") or ""),
                str(getattr(verification, "url", "") or verification_url),
            )
            if not consent["principal_id"]:
                consent["principal_id"] = str(
                    sso_claims.get("principal_id") or sso_claims.get("sub") or ""
                )
            approval = session.post(
                consent["approve_url"],
                data={
                    "user_code": consent["user_code"] or user_code,
                    "action": "allow",
                    "principal_type": consent["principal_type"] or "User",
                    "principal_id": consent["principal_id"],
                },
                headers={
                    "content-type": "application/x-www-form-urlencoded",
                    "origin": "https://accounts.x.ai",
                    "referer": str(getattr(verification, "url", "") or verification_url),
                    "accept": (
                        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                    ),
                    "upgrade-insecure-requests": "1",
                    "sec-fetch-dest": "document",
                    "sec-fetch-mode": "navigate",
                    "sec-fetch-site": "same-site",
                    "sec-fetch-user": "?1",
                },
                timeout=timeout,
                allow_redirects=True,
            )
            approval_url = str(getattr(approval, "url", "") or "")
            if "denied=1" in approval_url.lower():
                raise RuntimeError("device approval rejected: denied")
            if not getattr(approval, "ok", False) or (
                "done" not in approval_url
                and "authorized" not in str(getattr(approval, "text", "") or "").lower()
            ):
                raise RuntimeError(_response_error(approval, {}, "device approval rejected"))

            try:
                token = _poll_token(
                    session,
                    device_code,
                    interval=float(payload.get("interval") or 1),
                    timeout=timeout,
                    poll_timeout=poll_timeout,
                    client_id=client_id,
                    client_version=client_version,
                    client_surface=client_surface,
                )
            except RuntimeError as error:
                raise RuntimeError(
                    f"{error}; consent_principal_present={bool(consent['principal_id'])}"
                ) from error
            if token:
                return token
            last_error = "device token polling timed out"
        except Exception as exc:  # noqa: BLE001
            last_error = str(exc)
            if not _retryable(last_error) or attempt >= max_attempts:
                break
        if "invalid_grant" in last_error.lower():
            time.sleep(min(60.0, 15.0 * attempt))
        else:
            time.sleep(min(20.0, 1.5 * (1.6 ** (attempt - 1))))
    raise RuntimeError(f"legacy SSO device flow failed: {last_error}")


def _install_sso_cookies(session: Any, cookie: str) -> None:
    """Mirror the browser session cookie scope required by accounts.x.ai."""
    for name in ("sso", "sso-rw"):
        for domain in (".x.ai", "accounts.x.ai"):
            session.cookies.set(name, cookie, domain=domain, path="/")


def exchange_sso_for_token_in_browser(
    sso: str,
    *,
    page: Any,
    context: Any,
    sso_rw: str = "",
    proxy: str = "",
    client_id: str = CLIENT_ID,
    scopes: str = DEFAULT_SCOPES,
    http_timeout: float = 30,
    poll_timeout: float = 60,
    client_version: str = DEFAULT_CLIENT_VERSION,
    client_surface: str = DEFAULT_CLIENT_SURFACE,
    referrer: str = DEFAULT_REFERRER,
) -> dict[str, Any]:
    """Run the current Grok Build device flow with consent in the live browser."""
    cookie = str(sso or "").strip()
    if not cookie:
        raise ValueError("SSO cookie is required")

    shared_cookies = [
        {
            "name": "sso",
            "value": cookie,
            "domain": ".x.ai",
            "path": "/",
            "secure": True,
            "httpOnly": True,
            "sameSite": "Lax",
        }
    ]
    if str(sso_rw or "").strip():
        shared_cookies.append(
            {
                "name": "sso-rw",
                "value": str(sso_rw).strip(),
                "domain": ".x.ai",
                "path": "/",
                "secure": True,
                "httpOnly": True,
                "sameSite": "Lax",
            }
        )
    context.add_cookies(shared_cookies)

    from curl_cffi import requests

    session_kwargs: dict[str, Any] = {"impersonate": "chrome131"}
    if proxy:
        session_kwargs["proxies"] = {"http": proxy, "https": proxy}
    session = requests.Session(**session_kwargs)
    timeout = max(10.0, float(http_timeout))
    response = session.post(
        f"{ISSUER}/oauth2/device/code",
        data={
            "client_id": client_id,
            "scope": scopes.strip(),
            "referrer": referrer,
        },
        headers=_device_headers(client_version, client_surface),
        timeout=timeout,
    )
    payload = _object_json(response)
    device_code = str(payload.get("device_code") or "")
    user_code = str(payload.get("user_code") or "")
    verification_url = str(
        payload.get("verification_uri_complete")
        or payload.get("verification_uri")
        or ""
    )
    if not getattr(response, "ok", False) or not all(
        (device_code, user_code, verification_url)
    ):
        raise RuntimeError(_response_error(response, payload, "device code rejected"))

    page.goto(verification_url, wait_until="domcontentloaded", timeout=int(timeout * 1000))
    _approve_device_in_browser(page, timeout)
    token = _poll_token(
        session,
        device_code,
        interval=float(payload.get("interval") or 5),
        timeout=timeout,
        poll_timeout=poll_timeout,
        client_id=client_id,
        client_version=client_version,
        client_surface=client_surface,
    )
    if not token:
        raise RuntimeError("device token polling timed out")
    return token


def _approve_device_in_browser(page: Any, timeout: float) -> None:
    deadline = time.monotonic() + max(10.0, timeout)
    selectors = (
        'form[action*="/oauth2/device/verify"] button[type="submit"]',
        'form[action*="/oauth2/device/verify"] button',
        'form[action*="/oauth2/device/verify"] input[type="submit"]',
        'form[action*="/oauth2/device/approve"] button[name="action"][value="allow"]',
        'form[action*="/oauth2/device/approve"] input[name="action"][value="allow"]',
        'button[name="action"][value="allow"]',
    )
    while time.monotonic() < deadline:
        current_url = str(page.url or "")
        body = str(page.locator("body").inner_text(timeout=3000) or "").lower()
        if "denied=1" in current_url.lower():
            raise RuntimeError("device approval rejected: denied")
        if "/oauth2/device/done" in current_url or "authorized" in body:
            return
        for selector in selectors:
            locator = page.locator(selector).first
            if locator.count() and locator.is_visible():
                locator.click(timeout=5000)
                page.wait_for_timeout(500)
                break
        else:
            for label in (
                "Allow",
                "Authorize",
                "Approve",
                "Continue",
                "Confirm",
                "同意",
                "允许",
                "許可",
                "承認",
            ):
                locator = page.get_by_role(
                    "button", name=re.compile(label, re.IGNORECASE)
                ).first
                if locator.count() and locator.is_visible():
                    locator.click(timeout=5000)
                    page.wait_for_timeout(500)
                    break
            else:
                page.wait_for_timeout(300)
                continue
        try:
            page.wait_for_load_state("domcontentloaded", timeout=5000)
        except PlaywrightTimeoutError:
            pass
    form_actions = page.locator("form").evaluate_all(
        "forms => forms.map(form => form.getAttribute('action') || '').slice(0, 5)"
    )
    raise RuntimeError(
        "device consent did not reach done "
        f"(url={str(page.url or '')[:180]}, forms={form_actions})"
    )


def _device_headers(client_version: str, client_surface: str) -> dict[str, str]:
    return {
        "content-type": "application/x-www-form-urlencoded",
        "accept": "application/json",
        "x-grok-client-version": client_version,
        "x-grok-client-surface": client_surface,
    }


class _ConsentFormParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.forms: list[dict[str, Any]] = []
        self._current: dict[str, Any] | None = None

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = {key: value or "" for key, value in attrs}
        if tag == "form":
            self._current = {"action": values.get("action", ""), "inputs": {}}
        elif tag == "input" and self._current is not None:
            name = values.get("name", "")
            if name:
                self._current["inputs"][name] = values.get("value", "")

    def handle_endtag(self, tag: str) -> None:
        if tag == "form" and self._current is not None:
            self.forms.append(self._current)
            self._current = None


def _parse_consent_page(html: str, page_url: str) -> dict[str, str]:
    parser = _ConsentFormParser()
    parser.feed(html or "")
    form = next(
        (
            item
            for item in parser.forms
            if "approve" in str(item.get("action") or "").lower()
        ),
        parser.forms[0] if parser.forms else {},
    )
    inputs = form.get("inputs") if isinstance(form.get("inputs"), dict) else {}
    principal_id = str(inputs.get("principal_id") or "")
    if not principal_id:
        for pattern in (
            r'"userId":"([0-9a-fA-F-]{36})"',
            r'\\"userId\\":\\"([0-9a-fA-F-]{36})\\"',
        ):
            match = re.search(pattern, html or "")
            if match:
                principal_id = match.group(1)
                break
    action = str(form.get("action") or f"{ISSUER}/oauth2/device/approve")
    return {
        "approve_url": urljoin(page_url, action),
        "user_code": str(inputs.get("user_code") or ""),
        "principal_type": str(inputs.get("principal_type") or "User"),
        "principal_id": principal_id,
    }


def _jwt_claims(token: str) -> dict[str, Any]:
    try:
        segment = token.split(".")[1]
        segment += "=" * (-len(segment) % 4)
        value = json.loads(base64.urlsafe_b64decode(segment))
        return value if isinstance(value, dict) else {}
    except (binascii.Error, TypeError, UnicodeDecodeError, ValueError):
        return {}


def _poll_token(
    session: Any,
    device_code: str,
    *,
    interval: float,
    timeout: float,
    poll_timeout: float,
    client_id: str,
    client_version: str = DEFAULT_CLIENT_VERSION,
    client_surface: str = DEFAULT_CLIENT_SURFACE,
) -> dict[str, Any] | None:
    deadline = time.monotonic() + max(30.0, float(poll_timeout))
    delay = max(1.0, min(15.0, interval))
    while time.monotonic() < deadline:
        time.sleep(delay)
        response = session.post(
            f"{ISSUER}/oauth2/token",
            data={
                "grant_type": "urn:ietf:params:oauth:grant-type:device_code",
                "device_code": device_code,
                "client_id": client_id,
            },
            headers={
                **_device_headers(client_version, client_surface),
            },
            timeout=timeout,
        )
        payload = _object_json(response)
        if getattr(response, "ok", False) and str(payload.get("access_token") or ""):
            return payload
        error = str(payload.get("error") or "")
        if error == "slow_down":
            delay = min(10.0, delay + 1.0)
        elif error != "authorization_pending":
            raise RuntimeError(_response_error(response, payload, "device token rejected"))
    return None


def _object_json(response: Any) -> dict[str, Any]:
    try:
        value = response.json()
    except ValueError:
        return {}
    return value if isinstance(value, dict) else {}


def _response_error(response: Any, payload: dict[str, Any], fallback: str) -> str:
    error = str(payload.get("error") or "").strip()
    description = str(payload.get("error_description") or "").strip()
    status = int(getattr(response, "status_code", 0) or 0)
    detail = error or f"HTTP {status}"
    if description and description != error:
        detail = f"{detail}: {description}"
    return f"{fallback}: {detail[:500]}"


def _retryable(message: str) -> bool:
    value = message.lower()
    if "access denied" in value:
        return False
    return any(term in value for term in ("429", "rate", "slow_down", "invalid_grant", "timeout", "temporar", "network"))
