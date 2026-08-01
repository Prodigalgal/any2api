from __future__ import annotations

import logging
import os
import secrets
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from dataclasses import dataclass, field, replace
from typing import Any

from ..config import settings
from .proxy import proxy_lease, proxy_parameters

logger = logging.getLogger("any2api_automation.browser")


@dataclass(frozen=True)
class BrowserResult:
    external_id: str
    email: str
    credential: dict[str, Any]
    metadata: dict[str, Any] = field(default_factory=dict)
    expires_at: str | None = None
    ready_for_inference: bool = True

    def response(self) -> dict[str, Any]:
        return {
            "healthy": self.ready_for_inference,
            "ready_for_inference": self.ready_for_inference,
            "external_id": self.external_id,
            "email": self.email,
            "credential": self.credential,
            "metadata": self.metadata,
            "expires_at": self.expires_at,
        }


@dataclass(frozen=True)
class BrowserContextProfile:
    ignore_https_errors: bool = False
    locale: str | None = None
    timezone_id: str | None = None
    viewport_width: int | None = None
    viewport_height: int | None = None
    accept_language: str | None = None
    color_scheme: str | None = None
    patchright_user_agent: str | None = None

    def options(self, backend: str) -> dict[str, Any]:
        result: dict[str, Any] = {"ignore_https_errors": self.ignore_https_errors}
        if self.locale:
            result["locale"] = self.locale
        if self.timezone_id:
            result["timezone_id"] = self.timezone_id
        if self.viewport_width and self.viewport_height:
            result["viewport"] = {
                "width": self.viewport_width,
                "height": self.viewport_height,
            }
        if self.accept_language:
            result["extra_http_headers"] = {"Accept-Language": self.accept_language}
        if self.color_scheme:
            result["color_scheme"] = self.color_scheme
        if backend == "patchright" and self.patchright_user_agent:
            result["user_agent"] = self.patchright_user_agent
        return result


@dataclass(frozen=True)
class BrowserFingerprintVariant:
    id: str
    os: str
    locale: str
    timezone_id: str
    viewport_width: int
    viewport_height: int
    accept_language: str
    color_scheme: str = "light"


@dataclass(frozen=True)
class BrowserFingerprintPolicy:
    variants: tuple[BrowserFingerprintVariant, ...]
    camoufox_mode: str = "synthetic"

    def resolve(
        self,
        context: BrowserContextProfile,
        launch: BrowserLaunchProfile,
    ) -> tuple[BrowserContextProfile, BrowserLaunchProfile, str]:
        if not self.variants:
            raise ValueError("browser fingerprint policy requires at least one variant")
        variant = secrets.choice(self.variants)
        if self.camoufox_mode not in {"synthetic", "real"}:
            raise ValueError("camoufox fingerprint mode must be synthetic or real")
        return (
            replace(
                context,
                locale=variant.locale,
                timezone_id=variant.timezone_id,
                viewport_width=variant.viewport_width,
                viewport_height=variant.viewport_height,
                accept_language=variant.accept_language,
                color_scheme=variant.color_scheme,
            ),
            replace(
                launch,
                camoufox_os=variant.os,
                camoufox_fingerprint_preset=self.camoufox_mode == "real",
            ),
            variant.id,
        )


@dataclass(frozen=True)
class BrowserLaunchProfile:
    headless: bool | None = None
    humanize: bool = False
    camoufox_os: str | None = None
    window_width: int | None = None
    window_height: int | None = None
    block_webrtc: bool | None = None
    camoufox_fingerprint_preset: bool | None = None
    firefox_user_prefs: tuple[tuple[str, Any], ...] = ()
    patchright_args: tuple[str, ...] = ()
    patchright_ignore_default_args: tuple[str, ...] = ()
    launch_timeout_ms: int | None = None

    def resolve_headless(self, payload: dict[str, Any], fallback: bool) -> bool:
        if "headless" in payload:
            value = payload["headless"]
            if isinstance(value, bool):
                return value
            if isinstance(value, str):
                normalized = value.strip().lower()
                if normalized in {"1", "true", "yes", "on"}:
                    return True
                if normalized in {"0", "false", "no", "off"}:
                    return False
            raise ValueError("headless must be a boolean")
        return self.headless if self.headless is not None else fallback


def run_browser_flow(
    flow: Callable[[Any, Any, str, str], BrowserResult],
    *,
    preferred: str,
    fallback: str | None,
    payload: dict[str, Any],
    context_profile: BrowserContextProfile | None = None,
    launch_profile: BrowserLaunchProfile | None = None,
    fingerprint_policy: BrowserFingerprintPolicy | None = None,
) -> BrowserResult:
    config = settings()
    effective_launch_profile = launch_profile or BrowserLaunchProfile()
    effective_context_profile = context_profile or BrowserContextProfile()
    fingerprint_variant = "native"
    if fingerprint_policy is not None:
        (
            effective_context_profile,
            effective_launch_profile,
            fingerprint_variant,
        ) = fingerprint_policy.resolve(effective_context_profile, effective_launch_profile)
    proxy_check_url = str(
        payload.get("proxy_check_url") or "https://www.cloudflare.com/cdn-cgi/trace"
    )
    reject_hosts = tuple(str(value) for value in payload.get("proxy_reject_redirect_hosts", ()))
    headless = effective_launch_profile.resolve_headless(payload, config.registration_headless)
    with (
        proxy_lease(
            check_url=proxy_check_url,
            reject_redirect_hosts=reject_hosts,
            **proxy_parameters(payload),
        ) as leased_proxy,
        launch_browser(
            preferred,
            fallback,
            headless=headless,
            proxy_url=leased_proxy,
            profile=effective_launch_profile,
        ) as launched,
    ):
        backend, browser = launched
        context = browser.new_context(**effective_context_profile.options(backend))
        context.set_default_timeout(config.registration_timeout_seconds * 1000)
        page = context.new_page()
        try:
            result = flow(page, context, backend, leased_proxy)
            return replace(
                result,
                metadata={
                    **result.metadata,
                    "fingerprint_variant": fingerprint_variant,
                },
            )
        finally:
            context.set_default_timeout(max(1, config.browser_cleanup_timeout_seconds) * 1000)
            try:
                context.close()
            except Exception as error:  # noqa: BLE001 - browser process cleanup follows
                logger.warning(
                    "browser context cleanup failed backend=%s error_type=%s",
                    backend,
                    type(error).__name__,
                )


@contextmanager
def launch_browser(
    preferred: str,
    fallback: str | None,
    *,
    headless: bool,
    proxy_url: str,
    profile: BrowserLaunchProfile | None = None,
) -> Iterator[tuple[str, Any]]:
    effective_profile = profile or BrowserLaunchProfile()
    order = list(dict.fromkeys(name for name in (preferred, fallback) if name))
    errors: list[str] = []
    for backend in order:
        if backend == "camoufox":
            try:
                from camoufox.sync_api import Camoufox

                manager_options: dict[str, Any] = {
                    "headless": headless,
                    "humanize": effective_profile.humanize,
                    "geoip": bool(proxy_url),
                    "proxy": {"server": proxy_url} if proxy_url else None,
                    "env": {**os.environ, "MOZ_DISABLE_CONTENT_SANDBOX": "1"},
                    "firefox_user_prefs": {
                        "security.sandbox.content.level": 0,
                        **dict(effective_profile.firefox_user_prefs),
                    },
                }
                if effective_profile.camoufox_os:
                    manager_options["os"] = effective_profile.camoufox_os
                if effective_profile.window_width and effective_profile.window_height:
                    manager_options["window"] = (
                        effective_profile.window_width,
                        effective_profile.window_height,
                    )
                if effective_profile.block_webrtc is not None:
                    manager_options["block_webrtc"] = effective_profile.block_webrtc
                if effective_profile.camoufox_fingerprint_preset is not None:
                    manager_options["fingerprint_preset"] = (
                        effective_profile.camoufox_fingerprint_preset
                    )
                manager = Camoufox(**manager_options)
                browser = manager.__enter__()
            except Exception as exc:  # noqa: BLE001 - optional third-party backend
                errors.append(f"camoufox: {type(exc).__name__}")
                continue
            try:
                yield backend, browser
            finally:
                manager.__exit__(None, None, None)
            return
        if backend == "patchright":
            try:
                from patchright.sync_api import sync_playwright

                runtime = sync_playwright().start()
                options: dict[str, Any] = {"headless": headless}
                if proxy_url:
                    options["proxy"] = {"server": proxy_url}
                if effective_profile.patchright_args:
                    options["args"] = list(effective_profile.patchright_args)
                if effective_profile.patchright_ignore_default_args:
                    options["ignore_default_args"] = list(
                        effective_profile.patchright_ignore_default_args
                    )
                if effective_profile.launch_timeout_ms:
                    options["timeout"] = effective_profile.launch_timeout_ms
                browser = runtime.chromium.launch(**options)
            except Exception as exc:  # noqa: BLE001 - optional third-party backend
                errors.append(f"patchright: {type(exc).__name__}")
                continue
            try:
                yield backend, browser
            finally:
                browser.close()
                runtime.stop()
            return
    raise RuntimeError("no browser backend available: " + "; ".join(errors))


def first_visible(page: Any, selectors: tuple[str, ...], timeout_ms: int = 10_000) -> Any | None:
    for selector in selectors:
        locator = page.locator(selector).first
        try:
            locator.wait_for(state="visible", timeout=timeout_ms)
            return locator
        except Exception:  # noqa: BLE001,S112 - browser locator failures are expected here
            continue
    return None


def fill_first(page: Any, selectors: tuple[str, ...], value: str) -> None:
    locator = first_visible(page, selectors)
    if locator is None:
        raise RuntimeError("required registration field is unavailable")
    locator.fill(value)


def click_first(page: Any, selectors: tuple[str, ...]) -> None:
    locator = first_visible(page, selectors)
    if locator is None:
        raise RuntimeError("required registration action is unavailable")
    locator.click()


def enter_code(page: Any, code: str) -> None:
    combined = first_visible(
        page,
        (
            'input[autocomplete="one-time-code"]',
            'input[inputmode="numeric"]',
            'input[maxlength="6"]',
        ),
    )
    if combined is not None and combined.get_attribute("maxlength") != "1":
        combined.fill(code)
        return
    cells = page.locator('input[maxlength="1"]')
    if cells.count() < len(code):
        raise RuntimeError("verification code fields are unavailable")
    for index, character in enumerate(code):
        cells.nth(index).fill(character)


def credential_from_context(
    context: Any, page: Any, password: str, mail_jwt: str
) -> dict[str, Any]:
    cookies = context.cookies()
    cookie_map = {item["name"]: item["value"] for item in cookies}
    storage = page.evaluate(
        """() => Object.fromEntries(Object.keys(localStorage).map(k => [k, localStorage.getItem(k)]))"""
    )
    profile = page.evaluate(r"""() => {
      const ua = navigator.userAgent;
      const browserName = navigator.userAgentData?.brands?.at(-1)?.brand ||
        (/Firefox\//.test(ua) ? 'Firefox' : /Edg\//.test(ua) ? 'Edge' :
          /Chrome\//.test(ua) ? 'Chrome' : 'Unknown');
      return ({
        user_agent: ua,
        os_name: navigator.userAgentData?.platform || navigator.platform || '',
        browser_name: browserName,
        device_memory: navigator.deviceMemory || 8,
        cpu_core_num: navigator.hardwareConcurrency || 8,
        browser_language: navigator.language || 'en-US',
        browser_platform: navigator.platform || '',
        screen_width: screen.width,
        screen_height: screen.height,
        timezone_offset: -(new Date().getTimezoneOffset() * 60)
      });
    }""")
    credential: dict[str, Any] = {
        "password": password,
        "mail_jwt": mail_jwt,
        "cookies": cookie_map,
        "cookie": "; ".join(f"{key}={value}" for key, value in cookie_map.items()),
        "device_profile": profile,
        "user_agent": profile.get("user_agent", ""),
    }
    for key, value in storage.items():
        lowered = key.lower()
        if any(marker in lowered for marker in ("token", "auth", "user")):
            credential[key] = value
    return credential
