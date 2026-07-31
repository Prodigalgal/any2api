from __future__ import annotations

import asyncio
from typing import Any

from ..lifecycle.account import credential
from ..lifecycle.browser_session import BrowserSession, attach_credential_patch
from ..lifecycle.proxy import proxy_lease, proxy_parameters
from .base import AutomationProvider, AutomationProviderManifest
from .sso_channel import probe_result, session_cookies


class GrokConsoleAutomationProvider(AutomationProvider):
    manifest = AutomationProviderManifest(
        id="grok_console",
        browser_backend="none",
        fallback_backend=None,
        isolation="request",
        challenge_types=(),
        operations=("keepalive",),
    )

    async def keepalive(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await asyncio.to_thread(_keepalive_sync, payload, credential(payload))


def _keepalive_sync(payload: dict[str, Any], current: dict[str, Any]) -> dict[str, Any]:
    base_url = str(payload.get("base_url") or "https://console.x.ai").rstrip("/")
    with (
        proxy_lease(check_url=base_url, **proxy_parameters(payload)) as proxy_url,
        BrowserSession(
            origin=base_url,
            credential=current,
            proxy_url=proxy_url,
            cookie_domains=(".x.ai", ".grok.com"),
        ) as browser,
    ):
        for name, value in session_cookies(current).items():
            for domain in (".x.ai", ".grok.com"):
                browser.client.cookies.set(name, value, domain=domain, path="/")
        response = browser.client.post(
            f"{base_url}/v1/responses",
            stream=True,
            timeout=60,
            headers={
                "Authorization": "Bearer anonymous",
                "Accept": "text/event-stream",
                "Origin": base_url,
                "Referer": f"{base_url}/",
                "x-cluster": str(payload.get("cluster") or "https://us-east-1.api.x.ai"),
                "Sec-Fetch-Dest": "empty",
                "Sec-Fetch-Mode": "cors",
                "Sec-Fetch-Site": "same-origin",
                "Priority": "u=1, i",
            },
            json={
                "model": str(payload.get("model") or "grok-4.3"),
                "input": "Reply with OK.",
                "stream": True,
                "store": False,
                "max_output_tokens": 1,
            },
        )
        completed = False
        if 200 <= response.status_code < 300:
            completed = any(
                "response.completed" in _line_text(line)
                or _line_text(line).strip() == "data: [DONE]"
                for line in response.iter_lines()
            )
        result = attach_credential_patch(probe_result(response.status_code, completed), browser)
        response.close()
        return result


def _line_text(value: str | bytes) -> str:
    return value.decode("utf-8", errors="replace") if isinstance(value, bytes) else value
