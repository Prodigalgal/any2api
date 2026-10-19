from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import os
import re
import time
from collections.abc import Awaitable, Callable
from contextlib import asynccontextmanager
from contextvars import ContextVar
from copy import deepcopy
from dataclasses import dataclass
from typing import Any
from urllib.parse import urlparse

from patchright.async_api import Page as PatchrightPage
from patchright.async_api import async_playwright

from ..blocking_work import blocking_wait_timeout, run_blocking, run_cleanup
from ..browser_budget import BrowserProcessLease, browser_process_budget
from ..config import settings as core_settings
from ..lifecycle.browser import (
    _driver_pid,
    _process_tree,
    browser_operation_deadline,
    camoufox_config_from_options,
    terminate_residual_browser_process,
)
from ..lifecycle.proxy import ProxyTunnelLease, browser_proxy_options, retain_proxy_tunnel
from ..session_pool import AccountSessionPool, SessionSlot
from .runtime_rules import (
    RuntimeRule,
    RuntimeRuleDiscoveryError,
    RuntimeRuleSelection,
    build_id,
    rule_digest,
)

SCHEMA_VERSION = 1
_NO_SCRIPT_ARGUMENT = object()


async def evaluate_in_page_world(
    page: Any, expression: str, argument: Any = _NO_SCRIPT_ARGUMENT
) -> Any:
    """Access page-owned SDKs without replaying side effects on signature errors."""
    options = {"isolated_context": False} if isinstance(page, PatchrightPage) else {}
    if argument is _NO_SCRIPT_ARGUMENT:
        return await page.evaluate(expression, **options)
    return await page.evaluate(expression, argument, **options)


@dataclass
class OfficialBrowserSession:
    key: str
    browser: Any
    context: Any
    page: Any
    backend: str
    state_digest: str
    input_digest: str
    credential_digest: str
    proxy_url: str
    rule_revision: int
    rule_digest: str
    build_id: str
    created_at: float
    browser_manager: Any | None = None
    playwright: Any | None = None
    camoufox_config: dict[str, Any] | None = None
    browser_lease: BrowserProcessLease | None = None
    driver_processes: tuple[int, ...] = ()
    proxy_tunnel: ProxyTunnelLease | None = None


class OfficialBrowserRuntime:
    """Restores isolated account runtimes with bounded, idle-only eviction."""

    # Provider policies override saved engine/mode only when explicitly selected.
    inference_backend: str | None = None
    inference_headless: bool | None = None
    inference_exclude_default_addons: bool = False
    # Camoufox supplies native cursor trajectories; this does not prove a score.
    inference_humanize: bool = False

    def __init__(
        self,
        provider_id: str,
        base_url: str,
        *,
        allowed_domain_suffixes: tuple[str, ...],
        identity_fields: tuple[str, ...],
        require_build_assets: bool = True,
        page_url: str | None = None,
    ) -> None:
        self.provider_id = provider_id
        self.base_url = base_url.rstrip("/")
        self.page_url = (page_url or self.base_url).rstrip("/")
        self.require_build_assets = require_build_assets
        self.allowed_domain_suffixes = tuple(
            value.strip().lower().lstrip(".") for value in allowed_domain_suffixes if value.strip()
        )
        self.identity_fields = identity_fields
        self._sessions: AccountSessionPool[OfficialBrowserSession] = AccountSessionPool(
            core_settings().official_browser_session_pool_size, self._close_session
        )
        self._operation: ContextVar[SessionSlot[OfficialBrowserSession]] = ContextVar(
            f"{provider_id}_browser_operation"
        )
        self._event_loop: asyncio.AbstractEventLoop | None = None
        self._unregister_budget_evictors = browser_process_budget.register_evictors(
            self._evict_idle_for_budget,
            self._evict_idle_for_budget_sync,
        )
        self.logger = logging.getLogger(
            f"any2api_automation.providers.{provider_id}_official_browser"
        )

    @asynccontextmanager
    async def account_operation(self, credential: dict[str, Any]):
        self._event_loop = asyncio.get_running_loop()
        started = time.monotonic()
        self.logger.info("official_browser_operation stage=waiting_for_session")
        async with self._sessions.borrow(self._account_key(credential)) as slot:
            self.logger.info(
                "official_browser_operation stage=session_acquired wait_ms=%s",
                round((time.monotonic() - started) * 1000),
            )
            token = self._operation.set(slot)
            try:
                yield
            finally:
                self._operation.reset(token)

    async def session_for(
        self,
        credential: dict[str, Any],
        proxy_url: str,
        selection: RuntimeRuleSelection,
    ) -> OfficialBrowserSession:
        key = self._account_key(credential)
        incoming_digest = self.execution_context_digest(credential)
        incoming_credential_digest = self.credential_digest(credential)
        slot = self._operation.get()
        if slot.key != key:
            raise ValueError("browser operation account mismatch")
        current = slot.value
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
                or time.monotonic() - current.created_at >= selection.rules.session_max_age_seconds
                or (incoming_digest and incoming_digest not in accepted_digests)
                or current.credential_digest != incoming_credential_digest
            ):
                slot.value = None
                await self._close_session(current)
                current = None
        if current is None:
            current = await self._new_session(
                key,
                credential,
                proxy_url,
                incoming_digest,
                incoming_credential_digest,
                selection,
            )
            slot.value = current
        elif incoming_digest:
            current.input_digest = incoming_digest
        return current

    async def close(self) -> None:
        self._unregister_budget_evictors()
        await self._sessions.close()

    async def _evict_idle_for_budget(self) -> bool:
        return await self._sessions.evict_idle()

    def _evict_idle_for_budget_sync(self) -> bool:
        loop = self._event_loop
        if loop is None or not loop.is_running():
            return False
        try:
            if asyncio.get_running_loop() is loop:
                return False
        except RuntimeError:
            pass
        future = asyncio.run_coroutine_threadsafe(self._sessions.evict_idle(), loop)
        try:
            return bool(future.result(timeout=blocking_wait_timeout(10)))
        except TimeoutError:
            future.cancel()
            self.logger.warning("official_browser_idle_eviction_timed_out")
            return False
        except Exception:
            self.logger.exception("official_browser_idle_eviction_failed")
            return False

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
            raise ValueError(f"{self.provider_id} browser execution context schema is unsupported")
        return deepcopy(value)

    def execution_context_digest(self, credential: dict[str, Any]) -> str:
        value = self.execution_context(credential)
        return digest(value) if value else ""

    def credential_digest(self, credential: dict[str, Any]) -> str:
        value = {
            key: item for key, item in credential.items() if key != "browser_execution_context"
        }
        return digest(value)

    def filter_storage_state(self, state: dict[str, Any]) -> dict[str, Any]:
        cookies = state.get("cookies", [])
        origins = state.get("origins", [])
        if not isinstance(cookies, list) or not isinstance(origins, list):
            raise TypeError(f"{self.provider_id} browser storage state collections must be arrays")
        filtered_cookies = [
            deepcopy(cookie)
            for cookie in cookies
            if isinstance(cookie, dict) and self._allowed_host(str(cookie.get("domain") or ""))
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
        credential_digest: str,
        selection: RuntimeRuleSelection,
    ) -> OfficialBrowserSession:
        execution = self.execution_context(credential)
        backend = self.inference_backend or str(execution.get("backend") or "camoufox")
        exact_config = execution.get("camoufox_config")
        if (
            backend == "camoufox"
            and exact_config is not None
            and not isinstance(exact_config, dict)
        ):
            raise TypeError(f"{self.provider_id} Camoufox configuration must be an object")
        headless = (
            self.inference_headless
            if self.inference_headless is not None
            else core_settings().registration_headless
        )
        storage_state = self._storage_state(execution)
        browser_manager = None
        playwright = None
        browser = None
        camoufox_config = None
        browser_lease = await browser_process_budget.acquire_async(f"provider:{self.provider_id}")
        context = None
        proxy_tunnel = None
        try:
            self.logger.info("official_browser_session stage=launching backend=%s", backend)
            proxy_tunnel = retain_proxy_tunnel(proxy_url)
            if backend == "camoufox":
                from camoufox.async_api import AsyncCamoufox

                prepared = await run_blocking(
                    camoufox_launch_options,
                    exact_config if isinstance(exact_config, dict) else {},
                    proxy_url,
                    humanize=self.inference_humanize,
                    headless=headless,
                    exclude_default_addons=self.inference_exclude_default_addons,
                )
                camoufox_config = camoufox_config_from_options(prepared)
                browser_manager = AsyncCamoufox(from_options=prepared)
                browser = await browser_manager.__aenter__()
            else:
                backend = "patchright"
                playwright = await async_playwright().start()
                browser_lease.track_process(_driver_pid(playwright))
                options: dict[str, Any] = {
                    "headless": headless,
                }
                if proxy_url:
                    options["proxy"] = browser_proxy_options(proxy_url)
                browser = await playwright.chromium.launch(**options)
            browser_lease.track_process(_driver_pid(browser))
            options = context_options(
                execution,
                backend,
                browser_version=getattr(browser, "version", ""),
                headless=headless,
            )
            if storage_state:
                options["storage_state"] = storage_state
            context = await browser.new_context(**options)
            await self.configure_context(context, credential)
            page = await context.new_page()
            driver_pid = _driver_pid(browser) or _driver_pid(playwright)
            driver_processes = tuple(_process_tree(driver_pid)) if driver_pid else ()
            session = OfficialBrowserSession(
                key=key,
                browser=browser,
                context=context,
                page=page,
                backend=backend,
                state_digest=state_digest,
                input_digest=state_digest,
                credential_digest=credential_digest,
                proxy_url=proxy_url,
                rule_revision=selection.revision,
                rule_digest=rule_digest(selection),
                build_id="",
                created_at=time.monotonic(),
                browser_manager=browser_manager,
                playwright=playwright,
                camoufox_config=camoufox_config,
                browser_lease=browser_lease,
                driver_processes=driver_processes,
                proxy_tunnel=proxy_tunnel,
            )
            await self.configure_page(session, credential)
            await page.goto(
                self.page_url,
                wait_until="domcontentloaded",
                timeout=90_000,
            )
            try:
                try:
                    session.build_id = await official_build_id(
                        page, selection.rules.build_asset_markers
                    )
                except RuntimeRuleDiscoveryError:
                    if self.require_build_assets:
                        raise
                    session.build_id = build_id([self.page_url])
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
        except BaseException:
            driver_pid = (
                _driver_pid(browser) or _driver_pid(playwright) or _driver_pid(browser_manager)
            )
            await self._dispose_browser(
                context=context,
                browser=browser,
                browser_manager=browser_manager,
                playwright=playwright,
                driver_processes=tuple(_process_tree(driver_pid)) if driver_pid else (),
                browser_lease=browser_lease,
                proxy_tunnel=proxy_tunnel,
            )
            raise

    async def _close_session(self, session: OfficialBrowserSession) -> None:
        await self._dispose_browser(
            context=session.context,
            browser=session.browser,
            browser_manager=session.browser_manager,
            playwright=session.playwright,
            driver_processes=session.driver_processes,
            browser_lease=session.browser_lease,
            proxy_tunnel=session.proxy_tunnel,
        )

    async def _dispose_browser(
        self,
        *,
        context: Any,
        browser: Any,
        browser_manager: Any,
        playwright: Any,
        driver_processes: tuple[int, ...],
        browser_lease: BrowserProcessLease | None,
        proxy_tunnel: ProxyTunnelLease | None,
    ) -> None:
        await close_owned_browser(
            self.provider_id,
            logger=self.logger,
            context=context,
            browser=browser,
            browser_manager=browser_manager,
            playwright=playwright,
            driver_processes=driver_processes,
            browser_lease=browser_lease,
            proxy_tunnel=proxy_tunnel,
        )

    def _storage_state(self, execution: dict[str, Any]) -> dict[str, Any] | None:
        state = execution.get("storage_state")
        if state is None:
            return None
        if not isinstance(state, dict):
            raise TypeError(f"{self.provider_id} browser storage state must be an object")
        return self.filter_storage_state(state)

    def _account_key(self, credential: dict[str, Any]) -> str:
        for field in self.identity_fields:
            value = str(credential.get(field) or "").strip().lower()
            if value:
                return hashlib.sha256(value.encode()).hexdigest()
        raise ValueError(f"{self.provider_id} browser transport requires a stable account identity")

    def _allowed_host(self, value: str) -> bool:
        host = value.strip().lower().lstrip(".")
        return any(
            host == suffix or host.endswith("." + suffix) for suffix in self.allowed_domain_suffixes
        )


async def close_owned_browser(
    provider_id: str,
    *,
    logger: logging.Logger,
    context: Any,
    browser: Any,
    browser_manager: Any,
    playwright: Any,
    driver_processes: tuple[int, ...],
    browser_lease: BrowserProcessLease | None,
    proxy_tunnel: ProxyTunnelLease | None,
) -> None:
    async def phase(name: str, operation: Callable[[], Awaitable[None]]) -> None:
        try:
            async with asyncio.timeout(core_settings().browser_cleanup_timeout_seconds):
                await operation()
        except Exception as error:  # noqa: BLE001 - continue with driver/process cleanup
            logger.warning(
                "official_browser_cleanup_failed phase=%s error_type=%s",
                name,
                type(error).__name__,
            )

    async def cleanup() -> None:
        started = time.monotonic()
        logger.info("official_browser_cleanup stage=started")
        try:
            # A driver can await again in finally after cancellation. The process
            # watchdog bounds that teardown before either ownership token is released.
            with browser_operation_deadline(
                browser or playwright or browser_manager,
                core_settings().browser_cleanup_timeout_seconds,
                label=f"{provider_id} async session cleanup",
            ):
                if context is not None:
                    await phase("context", context.close)
                if browser_manager is not None:
                    await phase(
                        "browser_manager", lambda: browser_manager.__aexit__(None, None, None)
                    )
                elif browser is not None:
                    await phase("browser", browser.close)
                if playwright is not None:
                    await phase("driver", playwright.stop)
        finally:
            try:
                if driver_processes:
                    await run_cleanup(
                        terminate_residual_browser_process,
                        driver_processes,
                        label=f"{provider_id} session cleanup",
                    )
            except Exception as error:  # noqa: BLE001 - ownership release must still run
                logger.warning(
                    "official_browser_cleanup_failed phase=process error_type=%s",
                    type(error).__name__,
                )
            finally:
                try:
                    if browser_lease is not None:
                        browser_lease.release()
                finally:
                    if proxy_tunnel is not None:
                        await run_cleanup(proxy_tunnel.release)
        logger.info(
            "official_browser_cleanup stage=finished duration_ms=%s",
            round((time.monotonic() - started) * 1000),
        )

    task = asyncio.create_task(cleanup())
    try:
        await asyncio.shield(task)
    except asyncio.CancelledError:
        await task
        raise


def digest(value: dict[str, Any]) -> str:
    return hashlib.sha256(
        json.dumps(
            value,
            sort_keys=True,
            ensure_ascii=True,
            separators=(",", ":"),
        ).encode()
    ).hexdigest()


def camoufox_launch_options(
    config: dict[str, Any],
    proxy_url: str,
    *,
    humanize: bool = False,
    headless: bool | None = None,
    exclude_default_addons: bool = False,
) -> dict[str, Any]:
    from camoufox.utils import get_env_vars, get_target_os, launch_options

    headless = core_settings().registration_headless if headless is None else headless
    addon_options: dict[str, Any] = {}
    if exclude_default_addons:
        from camoufox.addons import DefaultAddons

        addon_options["exclude_addons"] = list(DefaultAddons)
    if config:
        exact_config = deepcopy(config)
        if humanize:
            exact_config["humanize"] = True
        prepared = launch_options(
            config=deepcopy(exact_config),
            headless=headless,
            humanize=humanize,
            geoip=False,
            proxy=None,
            env={**os.environ, "MOZ_DISABLE_CONTENT_SANDBOX": "1"},
            firefox_user_prefs={"security.sandbox.content.level": 0},
            i_know_what_im_doing=True,
            **addon_options,
        )
        prepared_env = dict(prepared.get("env") or {})
        for name in tuple(prepared_env):
            if name.startswith("CAMOU_CONFIG_"):
                prepared_env.pop(name)
        prepared_env.update(get_env_vars(exact_config, get_target_os(exact_config)))
        prepared["env"] = prepared_env
    else:
        prepared = launch_options(
            os="windows",
            headless=headless,
            humanize=humanize,
            geoip=True,
            proxy=browser_proxy_options(proxy_url) if proxy_url else None,
            env={**os.environ, "MOZ_DISABLE_CONTENT_SANDBOX": "1"},
            firefox_user_prefs={"security.sandbox.content.level": 0},
            **addon_options,
        )
    if proxy_url:
        prepared["proxy"] = browser_proxy_options(proxy_url)
    return prepared


def context_options(
    execution: dict[str, Any],
    backend: str,
    *,
    browser_version: str = "",
    headless: bool | None = None,
) -> dict[str, Any]:
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
            "locale": str(runtime.get("language") or "en-US"),
            "timezone_id": str(runtime.get("timezone_id") or "UTC"),
            "viewport": {"width": width, "height": height},
            "screen": {"width": width, "height": height},
        }
    )
    user_agent = str(runtime.get("user_agent") or "")
    if user_agent:
        version = re.match(r"(\d+)\.", browser_version)
        saved_version = re.search(r"Chrome/(\d+)\.", user_agent)
        same_version = not browser_version or bool(
            version and saved_version and version[1] == saved_version[1]
        )
        same_mode = headless is None or ("HeadlessChrome/" in user_agent) == headless
        # A browser upgrade or launch-mode change must use its own default UA;
        # restoring an older one creates an inconsistent runtime identity.
        if same_version and same_mode:
            options["user_agent"] = user_agent
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
