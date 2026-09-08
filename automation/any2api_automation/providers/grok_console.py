from __future__ import annotations

from collections.abc import AsyncIterator
from typing import Any
from urllib.parse import urlparse

from ..lifecycle.account import credential
from .base import (
    CAMOUFOX_BROWSER_RUNTIME,
    AutomationProvider,
    AutomationProviderManifest,
)
from .grok_console_browser import (
    GrokConsoleOfficialBrowserTransport,
    build_grok_console_request,
)
from .runtime_rules import parse_runtime_plan
from .sso_channel import probe_result
from .transport_support import transport_frame, transport_proxy_lease

_DEFAULT_BASE_URL = "https://console.x.ai"
_DEFAULT_CLUSTER = "https://us-east-1.api.x.ai"


class GrokConsoleAutomationProvider(AutomationProvider):
    manifest = AutomationProviderManifest(
        id="grok_console",
        browser_backend="camoufox",
        fallback_backend="patchright",
        isolation="process",
        challenge_types=(),
        operations=("keepalive",),
        realtime=True,
        inference_transport=True,
        inference_runtime=CAMOUFOX_BROWSER_RUNTIME,
    )

    def __init__(self) -> None:
        self._transports: dict[str, GrokConsoleOfficialBrowserTransport] = {}

    def _transport(self, payload: dict[str, Any]) -> GrokConsoleOfficialBrowserTransport:
        base_url = _base_url(payload)
        transport = self._transports.get(base_url)
        if transport is None:
            transport = GrokConsoleOfficialBrowserTransport(base_url)
            self._transports[base_url] = transport
        return transport

    async def keepalive(self, payload: dict[str, Any]) -> dict[str, Any]:
        current = credential(payload)
        plan = parse_runtime_plan(payload.get("runtime_plan"), self.manifest.id)
        base_url = _base_url(payload)
        transport = self._transport(payload)
        async with transport_proxy_lease(
            payload,
            check_url=base_url,
        ) as proxy_url:
            result = await transport.request(
                current,
                proxy_url,
                plan,
                _keepalive_body(str(payload.get("model") or "grok-4.3")),
                _runtime_option(payload, "cluster", _DEFAULT_CLUSTER),
            )
        status = int(result.get("status") or 502)
        body = str(result.get("body") or "")
        response = probe_result(status, _completed(body))
        patch = result.get("credential_patch")
        if isinstance(patch, dict) and patch:
            response["credential_patch"] = patch
        reports = result.get("runtime_reports")
        if isinstance(reports, list) and reports:
            response["runtime_reports"] = reports
        return response

    async def transport_request(self, payload: dict[str, Any]) -> dict[str, Any]:
        if str(payload.get("operation") or "") != "chat":
            raise ValueError("Grok Console transport operation is not allowlisted")
        command = payload.get("semantic_command")
        if not isinstance(command, dict):
            raise TypeError("Grok Console semantic command must be an object")
        plan = parse_runtime_plan(payload.get("runtime_plan"), self.manifest.id)
        current = credential(payload)
        transport = self._transport(payload)
        async with transport_proxy_lease(
            payload,
            check_url=_base_url(payload),
        ) as proxy_url:
            return await transport.request(
                current,
                proxy_url,
                plan,
                build_grok_console_request(command),
                _runtime_option(payload, "cluster", _DEFAULT_CLUSTER),
            )

    async def transport_stream(self, payload: dict[str, Any]) -> AsyncIterator[bytes]:
        if str(payload.get("operation") or "") != "chat":
            raise ValueError("Grok Console transport operation is not allowlisted")
        command = payload.get("semantic_command")
        if not isinstance(command, dict):
            raise TypeError("Grok Console semantic command must be an object")
        plan = parse_runtime_plan(payload.get("runtime_plan"), self.manifest.id)
        current = credential(payload)
        transport = self._transport(payload)
        async with transport_proxy_lease(
            payload,
            check_url=_base_url(payload),
        ) as proxy_url:
            try:
                async for event in transport.stream(
                    current,
                    command,
                    proxy_url,
                    plan,
                    _runtime_option(payload, "cluster", _DEFAULT_CLUSTER),
                ):
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


def _keepalive_body(model: str) -> dict[str, Any]:
    return {
        "model": model,
        "input": "Reply with OK.",
        "stream": True,
        "store": False,
        "max_output_tokens": 1,
    }


def _runtime_option(payload: dict[str, Any], name: str, fallback: str) -> str:
    options = payload.get("runtime_options")
    if isinstance(options, dict):
        value = str(options.get(name) or "").strip()
        if value:
            return value
    value = str(payload.get(name) or "").strip()
    return value or fallback


def _base_url(payload: dict[str, Any]) -> str:
    value = _runtime_option(payload, "base_url", _DEFAULT_BASE_URL).rstrip("/")
    parsed = urlparse(value)
    host = (parsed.hostname or "").lower().lstrip(".")
    if parsed.scheme != "https" or not (
        host == "x.ai" or host.endswith((".x.ai", ".grok.com")) or host == "grok.com"
    ):
        raise ValueError("Grok Console runtime base URL is not allowlisted")
    return value


def _completed(body: str) -> bool:
    return "response.completed" in body or "data: [DONE]" in body
