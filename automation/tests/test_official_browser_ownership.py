from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from any2api_automation.lifecycle import proxy
from any2api_automation.lifecycle.proxy_tunnels import ProxyTunnelPool
from any2api_automation.providers import official_browser
from any2api_automation.providers.mimo_browser import default_runtime_plan
from any2api_automation.providers.official_browser import OfficialBrowserRuntime


@pytest.mark.asyncio
@pytest.mark.parametrize("fail_navigation", [False, True])
async def test_browser_owns_tunnel_across_requests_and_releases_on_failure_or_close(
    monkeypatch, fail_navigation
):
    class Tunnel:
        alive = False

        def __enter__(self):
            self.alive = True
            return "http://127.0.0.1:18080"

        def __exit__(self, *_):
            self.alive = False

        def is_alive(self):
            return self.alive

    process = Tunnel()
    pool = ProxyTunnelPool(lambda _: process, capacity=1, idle_seconds=30)
    monkeypatch.setattr(proxy, "proxy_tunnel_pool", pool)
    page = SimpleNamespace(goto=AsyncMock(), is_closed=lambda: False)
    if fail_navigation:
        page.goto.side_effect = RuntimeError("navigation failed")
    context = SimpleNamespace(new_page=AsyncMock(return_value=page), close=AsyncMock())
    browser = SimpleNamespace(new_context=AsyncMock(return_value=context), close=AsyncMock())
    playwright = SimpleNamespace(
        chromium=SimpleNamespace(launch=AsyncMock(return_value=browser)), stop=AsyncMock()
    )
    monkeypatch.setattr(
        official_browser,
        "async_playwright",
        lambda: SimpleNamespace(start=AsyncMock(return_value=playwright)),
    )
    monkeypatch.setattr(official_browser, "official_build_id", AsyncMock(return_value="fixture"))
    runtime = OfficialBrowserRuntime(
        provider_id="test",
        base_url="https://example.test",
        allowed_domain_suffixes=("example.test",),
        identity_fields=("user_id",),
    )
    credential = {
        "user_id": "account",
        "browser_execution_context": {"schema_version": 1, "backend": "patchright"},
    }
    request = pool.acquire("node")
    try:
        async with runtime.account_operation(credential):
            if fail_navigation:
                with pytest.raises(RuntimeError, match="navigation failed"):
                    await runtime.session_for(
                        credential, request.proxy_url, default_runtime_plan().active
                    )
                assert pool.snapshot()["references"] == 1
            else:
                first = await runtime.session_for(
                    credential, request.proxy_url, default_runtime_plan().active
                )
                request.release()
                assert pool.snapshot()["references"] == 1
                second_request = pool.acquire("node")
                try:
                    second = await runtime.session_for(
                        credential, second_request.proxy_url, default_runtime_plan().active
                    )
                finally:
                    second_request.release()
                assert second is first
                playwright.chromium.launch.assert_awaited_once()
    finally:
        request.release()
        await runtime.close()
        assert pool.snapshot()["references"] == 0
        pool.close()
    context.close.assert_awaited_once()
    browser.close.assert_awaited_once()
    playwright.stop.assert_awaited_once()
