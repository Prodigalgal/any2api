from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import os
import time
from copy import deepcopy
from dataclasses import dataclass
from typing import Any
from urllib.parse import urlparse

from patchright.async_api import async_playwright

from ..config import settings as core_settings
from ..lifecycle.browser import camoufox_config_from_options
from .runtime_rules import (
    RuntimeRule,
    RuntimeRuleDiscoveryError,
    RuntimeRuleSelection,
    build_id,
    rule_digest,
)

SCHEMA_VERSION = 1


@dataclass
class OfficialBrowserSession:
    key: str
    browser: Any
    context: Any
    page: Any
    backend: str
    state_digest: str
    input_digest: str
    proxy_url: str
    rule_revision: int
    rule_digest: str
    build_id: str
    created_at: float
    browser_manager: Any | None = None
    playwright: Any | None = None
    camoufox_config: dict[str, Any] | None = None


class OfficialBrowserRuntime:
    """Restores one account-bound official page runtime at a time."""

    def __init__(
        self,
        provider_id: str,
        base_url: str,
        *,
        allowed_domain_suffixes: tuple[str, ...],
        identity_fields: tuple[str, ...],
    ) -> None:
        self.provider_id = provider_id
        self.base_url = base_url.rstrip("/")
        self.allowed_domain_suffixes = tuple(
            value.strip().lower().lstrip(".")
            for value in allowed_domain_suffixes
            if value.strip()
        )
        self.identity_fields = identity_fields
        self.lock = asyncio.Lock()
        self.current: OfficialBrowserSession | None = None
        self.logger = logging.getLogger(
            f"any2api_automation.providers.{provider_id}_official_browser"
        )

    async def session_for(
        self,
        credential: dict[str, Any],
        proxy_url: str,
        selection: RuntimeRuleSelection,
    ) -> OfficialBrowserSession:
        key = self._account_key(credential)
        incoming_digest = self.execution_context_digest(credential)
        current = self.current
        if current is not None:
            closed = (
                current.page.is_closed()
                if callable(getattr(current.page, "is_closed", None))
                else False
            )
            accepted_digests = {current.input_digest, current.state_digest}
            if (
                closed
                or current.key != key
                or current.proxy_url != proxy_url
                or current.rule_revision != selection.revision
                or current.rule_digest != rule_digest(selection)
                or time.monotonic() - current.created_at
                >= selection.rules.session_max_age_seconds
                or (incoming_digest and incoming_digest not in accepted_digests)
            ):
                await self._close_session(current)
                current = None
                self.current = None
        if current is None:
            current = await self._new_session(
                key,
                credential,
                proxy_url,
                incoming_digest,
                selection,
            )
            self.current = current
        elif incoming_digest:
            current.input_digest = incoming_digest
        return current

    async def close(self) -> None:
        async with self.lock:
            if self.current is not None:
                await self._close_session(self.current)
                self.current = None

    async def credential_patch(
        self,
        session: OfficialBrowserSession,
        credential: dict[str, Any],
    ) -> dict[str, Any]:
        try:
            state = await session.context.storage_state(indexed_db=True)
        except TypeError:
            state = await session.context.storage_state()
        execution = deepcopy(self.execution_context(credential))
        execution.update(
            {
                "schema_version": SCHEMA_VERSION,
                "backend": session.backend,
                "storage_state": self.filter_storage_state(state),
                "runtime_fingerprint": await runtime_fingerprint(session.page),
            }
        )
        if session.backend == "camoufox" and session.camoufox_config:
            execution["camoufox_config"] = deepcopy(session.camoufox_config)
        execution_digest = digest(execution)
        session.state_digest = execution_digest
        if execution_digest == self.execution_context_digest(credential):
            return {}
        return {"browser_execution_context": execution}

    def execution_context(self, credential: dict[str, Any]) -> dict[str, Any]:
        value = credential.get("browser_execution_context")
        if value is None:
            return {}
        if not isinstance(value, dict):
            raise TypeError(f"{self.provider_id} browser execution context must be an object")
        if value.get("schema_version") != SCHEMA_VERSION:
            raise ValueError(
                f"{self.provider_id} browser execution context schema is unsupported"
            )
        return deepcopy(value)

    def execution_context_digest(self, credential: dict[str, Any]) -> str:
        value = self.execution_context(credential)
        return digest(value) if value else ""

    def filter_storage_state(self, state: dict[str, Any]) -> dict[str, Any]:
        cookies = state.get("cookies", [])
        origins = state.get("origins", [])
        if not isinstance(cookies, list) or not isinstance(origins, list):
            raise TypeError(
                f"{self.provider_id} browser storage state collections must be arrays"
            )
        filtered_cookies = [
            deepcopy(cookie)
            for cookie in cookies
            if isinstance(cookie, dict)
            and self._allowed_host(str(cookie.get("domain") or ""))
        ]
        filtered_origins = []
        for origin in origins:
            if not isinstance(origin, dict):
                continue
            parsed = urlparse(str(origin.get("origin") or ""))
            if parsed.scheme == "https" and self._allowed_host(parsed.hostname or ""):
                filtered_origins.append(deepcopy(origin))
        return {"cookies": filtered_cookies, "origins": filtered_origins}

    async def configure_context(
        self,
        context: Any,
        credential: dict[str, Any],
    ) -> None:
        del context, credential

    async def configure_page(
        self,
        session: OfficialBrowserSession,
        credential: dict[str, Any],
    ) -> None:
        del session, credential

    async def wait_until_ready(self, page: Any, rule: RuntimeRule) -> None:
        del page, rule

    async def _new_session(
        self,
        key: str,
        credential: dict[str, Any],
        proxy_url: str,
        state_digest: str,
        selection: RuntimeRuleSelection,
    ) -> OfficialBrowserSession:
        execution = self.execution_context(credential)
        backend = str(execution.get("backend") or "camoufox")
        storage_state = self._storage_state(execution)
        browser_manager = None
        playwright = None
        browser = None
        camoufox_config = None
        if backend == "camoufox":
            from camoufox.async_api import AsyncCamoufox

            exact_config = execution.get("camoufox_config")
            prepared = await asyncio.to_thread(
                camoufox_launch_options,
                exact_config if isinstance(exact_config, dict) else {},
                proxy_url,
            )
            camoufox_config = camoufox_config_from_options(prepared)
            browser_manager = AsyncCamoufox(from_options=prepared)
            browser = await browser_manager.__aenter__()
        else:
            backend = "patchright"
            playwright = await async_playwright().start()
            options: dict[str, Any] = {
                "headless": core_settings().registration_headless,
            }
            if proxy_url:
                options["proxy"] = {"server": proxy_url}
            browser = await playwright.chromium.launch(**options)
        context = None
        try:
            options = context_options(execution, backend)
            if storage_state:
                options["storage_state"] = storage_state
            context = await browser.new_context(**options)
            await self.configure_context(context, credential)
            page = await context.new_page()
            session = OfficialBrowserSession(
                key=key,
                browser=browser,
                context=context,
                page=page,
                backend=backend,
                state_digest=state_digest,
                input_digest=state_digest,
                proxy_url=proxy_url,
                rule_revision=selection.revision,
                rule_digest=rule_digest(selection),
                build_id="",
                created_at=time.monotonic(),
                browser_manager=browser_manager,
                playwright=playwright,
                camoufox_config=camoufox_config,
            )
            await self.configure_page(session, credential)
            await page.goto(
                self.base_url,
                wait_until="domcontentloaded",
                timeout=90_000,
            )
            try:
                session.build_id = await official_build_id(
                    page, selection.rules.build_asset_markers
                )
                await self.wait_until_ready(page, selection.rules)
            except Exception as error:
                raise RuntimeRuleDiscoveryError(
                    f"{self.provider_id} runtime discovery failed ({type(error).__name__})"
                ) from error
            self.logger.info(
                "official_browser_session lifecycle=created backend=%s proxy_bound=%s "
                "rule_revision=%s build_id=%s",
                backend,
                bool(proxy_url),
                selection.revision,
                session.build_id[:12],
            )
            return session
        except Exception:
            if context is not None:
                await context.close()
            if browser_manager is not None:
                await browser_manager.__aexit__(None, None, None)
            elif browser is not None:
                await browser.close()
            if playwright is not None:
                await playwright.stop()
            raise

    async def _close_session(self, session: OfficialBrowserSession) -> None:
        try:
            await session.context.close()
        finally:
            if session.browser_manager is not None:
                await session.browser_manager.__aexit__(None, None, None)
            else:
                await session.browser.close()
            if session.playwright is not None:
                await session.playwright.stop()

    def _storage_state(self, execution: dict[str, Any]) -> dict[str, Any] | None:
        state = execution.get("storage_state")
        if state is None:
            return None
        if not isinstance(state, dict):
            raise TypeError(
                f"{self.provider_id} browser storage state must be an object"
            )
        return self.filter_storage_state(state)

    def _account_key(self, credential: dict[str, Any]) -> str:
        for field in self.identity_fields:
            value = str(credential.get(field) or "").strip().lower()
            if value:
                return hashlib.sha256(value.encode()).hexdigest()
        raise ValueError(
            f"{self.provider_id} browser transport requires a stable account identity"
        )

    def _allowed_host(self, value: str) -> bool:
        host = value.strip().lower().lstrip(".")
        return any(
            host == suffix or host.endswith("." + suffix)
            for suffix in self.allowed_domain_suffixes
        )


def digest(value: dict[str, Any]) -> str:
    return hashlib.sha256(
        json.dumps(
            value,
            sort_keys=True,
            ensure_ascii=True,
            separators=(",", ":"),
        ).encode()
    ).hexdigest()


def camoufox_launch_options(config: dict[str, Any], proxy_url: str) -> dict[str, Any]:
    from camoufox.utils import get_env_vars, get_target_os, launch_options

    if config:
        prepared = launch_options(
            config=deepcopy(config),
            headless=core_settings().registration_headless,
            geoip=False,
            proxy=None,
            env={**os.environ, "MOZ_DISABLE_CONTENT_SANDBOX": "1"},
            firefox_user_prefs={"security.sandbox.content.level": 0},
            i_know_what_im_doing=True,
        )
        prepared_env = dict(prepared.get("env") or {})
        for name in tuple(prepared_env):
            if name.startswith("CAMOU_CONFIG_"):
                prepared_env.pop(name)
        prepared_env.update(get_env_vars(config, get_target_os(config)))
        prepared["env"] = prepared_env
    else:
        prepared = launch_options(
            os="windows",
            headless=core_settings().registration_headless,
            humanize=False,
            geoip=bool(proxy_url),
            proxy={"server": proxy_url} if proxy_url else None,
            env={**os.environ, "MOZ_DISABLE_CONTENT_SANDBOX": "1"},
            firefox_user_prefs={"security.sandbox.content.level": 0},
        )
    if proxy_url:
        prepared["proxy"] = {"server": proxy_url}
    return prepared


def context_options(execution: dict[str, Any], backend: str) -> dict[str, Any]:
    options: dict[str, Any] = {"ignore_https_errors": True}
    if backend == "camoufox":
        return options
    runtime = execution.get("runtime_fingerprint")
    if not isinstance(runtime, dict):
        return options
    screen = runtime.get("screen") if isinstance(runtime.get("screen"), dict) else {}
    width = int(screen.get("width") or 1440)
    height = int(screen.get("height") or 900)
    options.update(
        {
            "user_agent": str(
                runtime.get("user_agent") or core_settings().provider_user_agent
            ),
            "locale": str(runtime.get("language") or "en-US"),
            "timezone_id": str(runtime.get("timezone_id") or "UTC"),
            "viewport": {"width": width, "height": height},
            "screen": {"width": width, "height": height},
        }
    )
    return options


async def runtime_fingerprint(page: Any) -> dict[str, Any]:
    value = await page.evaluate(
        r"""() => {
          const gl = document.createElement('canvas').getContext('webgl');
          const debug = gl?.getExtension('WEBGL_debug_renderer_info');
          return {
            user_agent: navigator.userAgent,
            platform: navigator.platform || '',
            language: navigator.language || '',
            languages: Array.from(navigator.languages || []),
            hardware_concurrency: navigator.hardwareConcurrency || null,
            device_memory: navigator.deviceMemory || null,
            timezone_id: Intl.DateTimeFormat().resolvedOptions().timeZone || '',
            timezone_offset_minutes: new Date().getTimezoneOffset(),
            color_scheme: matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light',
            screen: {
              width: screen.width,
              height: screen.height,
              avail_width: screen.availWidth,
              avail_height: screen.availHeight,
              color_depth: screen.colorDepth,
              pixel_ratio: devicePixelRatio
            },
            webgl_vendor: debug ? gl.getParameter(debug.UNMASKED_VENDOR_WEBGL) : '',
            webgl_renderer: debug ? gl.getParameter(debug.UNMASKED_RENDERER_WEBGL) : ''
          };
        }"""
    )
    return value if isinstance(value, dict) else {}


async def official_build_id(page: Any, markers: tuple[str, ...]) -> str:
    values = await page.evaluate(
        """markers => [...document.scripts]
          .map(script => String(script.src || ''))
          .filter(source => source && markers.some(marker => source.includes(marker)))
          .map(source => {
            const parsed = new URL(source, location.href);
            return parsed.origin + parsed.pathname;
          })""",
        list(markers),
    )
    if not isinstance(values, list):
        raise RuntimeRuleDiscoveryError("official build asset collection is invalid")
    return build_id([str(value) for value in values])
