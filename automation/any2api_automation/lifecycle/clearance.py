from __future__ import annotations

import random
import threading
import time
from datetime import UTC, datetime
from typing import Any

from ..config import settings
from .browser import BrowserContextProfile, BrowserLaunchProfile, launch_browser
from .browser_session import BrowserSession

_clearance_slots = threading.BoundedSemaphore(max(1, settings().browser_realtime_capacity))
_CHALLENGE_MARKERS = (
    "just a moment",
    "checking your browser",
    "performing security verification",
    "verify you are human",
    "challenge-platform",
    "cf-chl-",
)


def refresh_clearance(
    *,
    browser: BrowserSession,
    proxy_url: str,
    target_url: str,
    timeout_seconds: int | None = None,
) -> dict[str, Any]:
    config = settings()
    timeout = timeout_seconds or config.browser_clearance_timeout_seconds
    timeout = max(30, min(300, int(timeout)))
    attempts = max(1, min(3, config.browser_clearance_attempts))
    errors: list[str] = []
    with _clearance_slots:
        for attempt in range(attempts):
            if attempt:
                time.sleep(random.uniform(0.25, 0.9))
            try:
                return _refresh_once(browser, proxy_url, target_url, timeout)
            except (OSError, RuntimeError, TypeError, ValueError) as error:
                errors.append(type(error).__name__)
    raise RuntimeError("browser clearance refresh failed: " + ",".join(errors))


def _refresh_once(
    browser: BrowserSession,
    proxy_url: str,
    target_url: str,
    timeout_seconds: int,
) -> dict[str, Any]:
    launch = BrowserLaunchProfile(
        headless=settings().registration_headless,
        launch_timeout_ms=min(timeout_seconds * 1000, 120_000),
    )
    context_profile = BrowserContextProfile(
        ignore_https_errors=False,
        locale="en-US",
        timezone_id="Asia/Tokyo" if proxy_url else "UTC",
        viewport_width=1365,
        viewport_height=900,
        accept_language="en-US,en;q=0.9",
        color_scheme="light",
        patchright_user_agent=browser.user_agent,
    )
    with launch_browser(
        "patchright",
        None,
        headless=bool(launch.headless),
        proxy_url=proxy_url,
        profile=launch,
    ) as (backend, runtime):
        context = runtime.new_context(**context_profile.options(backend))
        context.set_default_timeout(timeout_seconds * 1000)
        try:
            cookies = browser.browser_cookies()
            if cookies:
                context.add_cookies(cookies)
            page = context.new_page()
            response = page.goto(
                target_url,
                wait_until="domcontentloaded",
                timeout=timeout_seconds * 1000,
            )
            deadline = time.monotonic() + timeout_seconds
            while time.monotonic() < deadline:
                patch = _clearance_patch(context, browser)
                if patch is not None and not _challenge_visible(page):
                    status = response.status if response is not None else 200
                    if status != 403:
                        return browser.apply_clearance_context(patch)
                page.wait_for_timeout(750)
            raise RuntimeError("Cloudflare challenge did not reach a usable browser context")
        finally:
            context.close()


def _clearance_patch(context: Any, browser: BrowserSession) -> dict[str, Any] | None:
    values: dict[str, str] = {}
    expiries: list[int] = []
    for cookie in context.cookies():
        name = str(cookie.get("name") or "")
        if name not in {"cf_clearance", "__cf_bm", "_cfuvid", "cf_chl_2", "cf_chl_rc_i"}:
            continue
        value = str(cookie.get("value") or "").strip()
        if not value:
            continue
        values[name] = value
        expires = cookie.get("expires")
        if isinstance(expires, (int, float)) and expires > 0:
            expiries.append(int(expires))
    if not values:
        return None
    patch: dict[str, Any] = {
        "cloudflare_cookies": "; ".join(f"{name}={values[name]}" for name in sorted(values)),
        "user_agent": browser.user_agent,
        "browser_profile": browser.profile.impersonate,
        "clearance_refreshed_at": datetime.now(UTC).isoformat(),
    }
    if expiries:
        patch["clearance_expires_at"] = datetime.fromtimestamp(min(expiries), tz=UTC).isoformat()
    return patch


def _challenge_visible(page: Any) -> bool:
    try:
        title = str(page.title() or "").lower()
        body = str(
            page.evaluate(
                "() => (document.body?.innerText || document.documentElement?.innerText || '')"
            )
            or ""
        ).lower()[:8000]
        html = str(page.content() or "").lower()[:16000]
    except Exception:  # noqa: BLE001 - page can navigate while the challenge resolves
        return True
    sample = f"{title}\n{body}\n{html}"
    return any(marker in sample for marker in _CHALLENGE_MARKERS)
