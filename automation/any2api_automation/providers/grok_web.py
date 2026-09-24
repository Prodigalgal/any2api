import asyncio
from collections.abc import AsyncIterator
from typing import Any

from ..lifecycle.account import credential, flow_max_attempts, prepare_registration
from ..lifecycle.browser import BrowserLaunchProfile, run_browser_flow
from ..lifecycle.proxy import proxy_attempt_payload
from ..lifecycle.registration import RegistrationStage, RegistrationTrace
from .base import (
    API_TRANSPORT,
    CAMOUFOX_BROWSER_RUNTIME,
    AutomationProvider,
    AutomationProviderManifest,
)
from .grok_web_browser import GrokWebOfficialBrowserTransport, register_grok_web
from .runtime_rules import parse_runtime_plan
from .sso_channel import probe_result
from .transport_support import transport_frame, transport_proxy_lease

_BASE_URL = "https://grok.com"


class GrokWebAutomationProvider(AutomationProvider):
    manifest = AutomationProviderManifest(
        id="grok_web",
        browser_backend="camoufox",
        fallback_backend="patchright",
        isolation="process",
        challenge_types=("cloudflare",),
        operations=("register", "keepalive"),
        realtime=True,
        inference_transport=True,
        inference_runtime=CAMOUFOX_BROWSER_RUNTIME,
        inference_modes=(API_TRANSPORT, CAMOUFOX_BROWSER_RUNTIME),
        inference_actions=("model_discovery", "chat"),
    )

    def __init__(self) -> None:
        self._transports: dict[str, GrokWebOfficialBrowserTransport] = {}

    def action_bindings(self):
        from .grok_web_api_actions import grok_web_api_action_bindings

        return grok_web_api_action_bindings(self)

    def browser_launch_profile(self) -> BrowserLaunchProfile:
        return BrowserLaunchProfile(
            headless=False,
            firefox_user_prefs=(("webgl.force-enabled", True), ("webgl.disabled", False)),
        )

    def _transport(self, payload: dict[str, Any]) -> GrokWebOfficialBrowserTransport:
        base_url = str(payload.get("base_url") or _BASE_URL).rstrip("/")
        transport = self._transports.get(base_url)
        if transport is None:
            transport = GrokWebOfficialBrowserTransport(base_url)
            self._transports[base_url] = transport
        return transport

    async def register(self, payload: dict[str, Any]) -> dict[str, Any]:
        trace = RegistrationTrace(self.manifest.id)
        try:
            mail, mailbox, password = await prepare_registration(payload)
            trace.mark(RegistrationStage.MAILBOX_CREATED)
            attempts = flow_max_attempts(payload, 2)
            last_failure: Exception | None = None
            for attempt in range(1, attempts + 1):
                try:
                    flow_payload = proxy_attempt_payload(
                        {**payload, "proxy_check_url": str(payload.get("base_url") or _BASE_URL)},
                        identity=mailbox.address,
                        attempt=attempt,
                    )
                    if flow_payload.get("dynamic_proxy") or flow_payload.get("proxy_pool"):
                        flow_payload["strict_proxy_affinity"] = True
                    result = await asyncio.to_thread(
                        run_browser_flow,
                        lambda page, context, backend, proxy_url, _mail=mail, _mailbox=mailbox, _password=password, _trace=trace, _flow=flow_payload: (
                            register_grok_web(
                                page,
                                context,
                                backend,
                                _mail,
                                _mailbox,
                                _password,
                                _flow,
                                _trace,
                            )
                        ),
                        preferred=self.manifest.browser_backend,
                        fallback=self.manifest.fallback_backend,
                        payload=flow_payload,
                        context_profile=self.browser_context_profile(),
                        launch_profile=self.browser_launch_profile(),
                    )
                    response = result.response()
                    response.setdefault("metadata", {})["browser_attempt"] = attempt
                    return response
                except Exception as error:
                    last_failure = error
                    accepted = trace.current in {
                        RegistrationStage.UPSTREAM_ACCEPTED.value,
                        RegistrationStage.ACTIVATED.value,
                        RegistrationStage.CREDENTIAL_CAPTURED.value,
                    }
                    if accepted or attempt >= attempts:
                        raise
                    await asyncio.sleep(2.0)
            if last_failure is not None:
                raise last_failure
            raise RuntimeError("Grok Web registration attempts exhausted")
        except Exception as error:
            raise trace.failure(error) from error

    async def keepalive(self, payload: dict[str, Any]) -> dict[str, Any]:
        current = credential(payload)
        plan = parse_runtime_plan(payload.get("runtime_plan"), self.manifest.id)
        transport = self._transport(payload)
        async with transport_proxy_lease(
            payload,
            check_url=str(payload.get("base_url") or _BASE_URL),
        ) as proxy_url:
            result = await transport.request(current, proxy_url, plan)
        response = probe_result(int(result.get("status") or 502), _authenticated(result))
        patch = result.get("credential_patch")
        if isinstance(patch, dict) and patch:
            response["credential_patch"] = patch
        reports = result.get("runtime_reports")
        if isinstance(reports, list) and reports:
            response["runtime_reports"] = reports
        return response

    async def transport_request(self, payload: dict[str, Any]) -> dict[str, Any]:
        operation = str(payload.get("operation") or "")
        if operation not in {"models", "keepalive"}:
            raise ValueError("Grok Web transport operation is not allowlisted")
        current = credential(payload)
        plan = parse_runtime_plan(payload.get("runtime_plan"), self.manifest.id)
        transport = self._transport(payload)
        async with transport_proxy_lease(
            payload,
            check_url=str(payload.get("base_url") or _BASE_URL),
        ) as proxy_url:
            return await transport.request(
                current,
                proxy_url,
                plan,
                operation=operation,
            )

    async def transport_stream(self, payload: dict[str, Any]) -> AsyncIterator[bytes]:
        if str(payload.get("operation") or "") != "chat":
            raise ValueError("Grok Web transport operation is not allowlisted")
        command = payload.get("semantic_command")
        if not isinstance(command, dict):
            raise TypeError("Grok Web semantic command must be an object")
        current = credential(payload)
        plan = parse_runtime_plan(payload.get("runtime_plan"), self.manifest.id)
        transport = self._transport(payload)
        async with transport_proxy_lease(
            payload,
            check_url=str(payload.get("base_url") or _BASE_URL),
        ) as proxy_url:
            try:
                async for event in transport.stream(current, command, proxy_url, plan):
                    event_type = str(event.get("type") or "error")
                    details = {key: value for key, value in event.items() if key != "type"}
                    yield transport_frame(event_type, **details)
            except Exception as error:  # noqa: BLE001 - normalized stream boundary
                yield transport_frame(
                    "error",
                    data=f"official browser stream failed ({type(error).__name__})",
                )

    async def close(self) -> None:
        transports = tuple(self._transports.values())
        self._transports.clear()
        for transport in transports:
            await transport.close()


def _authenticated(result: dict[str, Any]) -> bool:
    body = str(result.get("body") or "")
    return '"userId"' in body or '"user_id"' in body
