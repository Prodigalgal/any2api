from __future__ import annotations

import asyncio
import logging
from collections.abc import AsyncIterator
from typing import Any

from ..lifecycle.account import (
    RegistrationPasswordPolicy,
    credential,
    mail_client,
    strong_password,
)
from ..lifecycle.browser import BrowserContextProfile, BrowserLaunchProfile, run_browser_flow
from ..lifecycle.proxy import proxy_attempt_payload
from ..lifecycle.registration import RegistrationStage, RegistrationTrace
from ..observability import OperationFailure
from .arena_browser import (
    ArenaOfficialBrowserTransport,
    _allowlisted_base_url,
    _arena_config,
    account_status_is_healthy,
    register_with_magic_link,
)
from .base import (
    API_TRANSPORT,
    CAMOUFOX_BROWSER_RUNTIME,
    AutomationProvider,
    AutomationProviderManifest,
)
from .runtime_rules import parse_runtime_plan
from .transport_support import transport_frame, transport_proxy_lease

logger = logging.getLogger("any2api_automation.providers.arena")


class ArenaAutomationProvider(AutomationProvider):
    manifest = AutomationProviderManifest(
        id="arena",
        browser_backend="camoufox",
        fallback_backend="patchright",
        isolation="process",
        challenge_types=("email_magic_link",),
        operations=("register", "reauthenticate", "keepalive"),
        realtime=True,
        inference_transport=True,
        inference_runtime="camoufox_browser_runtime",
        inference_modes=(API_TRANSPORT, CAMOUFOX_BROWSER_RUNTIME),
        inference_actions=("model_discovery", "chat"),
        registration_attempt_mode="single_identity",
        registration_max_target=1,
        registration_max_attempts=1,
    )

    def __init__(self) -> None:
        self._transports: dict[str, ArenaOfficialBrowserTransport] = {}

    def _transport(self, payload: dict[str, Any]) -> ArenaOfficialBrowserTransport:
        base_url = _allowlisted_base_url(_runtime_option(payload, "base_url"))
        transport = self._transports.get(base_url)
        if transport is None:
            config = _arena_config({"runtime_options": {"base_url": base_url}})
            transport = ArenaOfficialBrowserTransport(
                base_url,
                page_path=config["page_path"],
                me_path=config["me_path"],
                chat_path=config["chat_path"],
            )
            self._transports[base_url] = transport
        return transport

    def action_bindings(self):
        from .api_transport import api_action_bindings
        from .arena_api_actions import ArenaApiActionHandler

        return api_action_bindings(self, ArenaApiActionHandler())

    async def register(self, payload: dict[str, Any]) -> dict[str, Any]:
        trace = RegistrationTrace(self.manifest.id)
        try:
            mail = mail_client(payload)
            password = RegistrationPasswordPolicy(
                strong_password,
                min_length=8,
                max_length=64,
            ).resolve(payload.get("password"))
            mailbox = await mail.create_address()
            trace.mark(RegistrationStage.MAILBOX_CREATED)
            seen_ids = await asyncio.to_thread(mail.message_ids_sync, mailbox)
            base_url = _allowlisted_base_url(_runtime_option(payload, "base_url"))
            flow_payload = proxy_attempt_payload(
                {**payload, "proxy_check_url": base_url},
                identity=mailbox.address,
                attempt=1,
            )
            flow_payload["strict_proxy_affinity"] = True
            result = await asyncio.to_thread(
                run_browser_flow,
                lambda page, context, backend, proxy_url: register_with_magic_link(
                    page,
                    context,
                    backend,
                    mail,
                    mailbox,
                    seen_ids,
                    password,
                    flow_payload,
                    trace,
                ),
                preferred=self.manifest.browser_backend,
                fallback=self.manifest.fallback_backend,
                payload=flow_payload,
                context_profile=self.browser_context_profile(),
                launch_profile=self.browser_launch_profile(),
            )
            return result.response()
        except OperationFailure:
            raise
        except Exception as error:
            raise trace.failure(error) from error

    async def reauthenticate(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await self._account_probe(payload, operation="reauthenticate")

    async def keepalive(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await self._account_probe(payload, operation="keepalive")

    async def _account_probe(self, payload: dict[str, Any], *, operation: str) -> dict[str, Any]:
        current = credential(payload)
        plan = parse_runtime_plan(payload.get("runtime_plan"), self.manifest.id)
        base_url = _allowlisted_base_url(_runtime_option(payload, "base_url"))
        async with transport_proxy_lease(payload, check_url=base_url) as proxy_url:
            result = await self._transport(payload).account_status(current, proxy_url, plan)
        response = _account_probe_response(result, operation)
        return response

    async def transport_request(self, payload: dict[str, Any]) -> dict[str, Any]:
        if str(payload.get("operation") or "") != "models":
            raise ValueError("Arena transport operation is not allowlisted")
        plan = parse_runtime_plan(payload.get("runtime_plan"), self.manifest.id)
        current = credential(payload)
        base_url = _allowlisted_base_url(_runtime_option(payload, "base_url"))
        async with transport_proxy_lease(payload, check_url=base_url) as proxy_url:
            return await self._transport(payload).models(current, proxy_url, plan)

    async def transport_stream(self, payload: dict[str, Any]) -> AsyncIterator[bytes]:
        if str(payload.get("operation") or "") != "chat":
            raise ValueError("Arena transport operation is not allowlisted")
        command = payload.get("semantic_command")
        if not isinstance(command, dict):
            raise TypeError("Arena semantic command must be an object")
        plan = parse_runtime_plan(payload.get("runtime_plan"), self.manifest.id)
        current = credential(payload)
        base_url = _allowlisted_base_url(_runtime_option(payload, "base_url"))
        try:
            async with transport_proxy_lease(payload, check_url=base_url) as proxy_url:
                async for event in self._transport(payload).chat_stream(
                    current, command, proxy_url, plan
                ):
                    event_type = str(event.get("type") or "error")
                    yield transport_frame(
                        event_type,
                        **{key: value for key, value in event.items() if key != "type"},
                    )
        except (TypeError, ValueError):
            yield transport_frame("status", status=422)
            yield transport_frame("error", data="Arena semantic command or model is invalid")
        except Exception as error:  # noqa: BLE001 - normalized stream boundary
            logger.warning("arena_transport_stream_failed error_type=%s", type(error).__name__)
            yield transport_frame("status", status=502)
            yield transport_frame("error", data="Arena browser stream failed")

    async def close(self) -> None:
        transports = tuple(self._transports.values())
        self._transports.clear()
        await asyncio.gather(*(transport.close() for transport in transports))

    def browser_context_profile(self) -> BrowserContextProfile:
        return BrowserContextProfile(
            ignore_https_errors=True,
            locale="en-US",
            timezone_id="UTC",
            viewport_width=1440,
            viewport_height=900,
            accept_language="en-US,en;q=0.9",
        )

    def browser_launch_profile(self) -> BrowserLaunchProfile:
        return BrowserLaunchProfile(headless=True, humanize=False, camoufox_os="windows")


def _account_probe_response(result: dict[str, Any], operation: str) -> dict[str, Any]:
    status = int(result.get("status") or 502)
    body = str(result.get("body") or "")
    authenticated = 200 <= status < 300 and account_status_is_healthy(body)
    if status in {401, 403}:
        error_class = (
            "arena_interactive_auth_required"
            if operation == "reauthenticate"
            else "arena_credentials_rejected"
        )
    else:
        error_class = "" if authenticated else "arena_profile_unavailable"
    response: dict[str, Any] = {
        "healthy": authenticated,
        "auth_expired": status in {401, 403},
        "ready_for_inference": False,
        "inference_probe_required": authenticated,
        "error_class": error_class,
    }
    patch = result.get("credential_patch")
    if isinstance(patch, dict) and patch:
        response["credential_patch"] = patch
    reports = result.get("runtime_reports")
    if isinstance(reports, list) and reports:
        response["runtime_reports"] = reports
    return response


def _runtime_option(payload: dict[str, Any], name: str) -> str | None:
    options = payload.get("runtime_options")
    if not isinstance(options, dict):
        return None
    value = options.get(name)
    return str(value).strip() if value is not None else None
