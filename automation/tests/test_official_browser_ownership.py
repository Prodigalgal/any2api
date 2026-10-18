import asyncio
import threading
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest

from any2api_automation.browser_budget import BrowserProcessBudget
from any2api_automation.lifecycle import browser as browser_lifecycle
from any2api_automation.lifecycle import proxy
from any2api_automation.lifecycle.proxy_tunnels import ProxyTunnelPool
from any2api_automation.providers import official_browser
from any2api_automation.providers.mimo_browser import default_runtime_plan
from any2api_automation.providers.official_browser import (
    OfficialBrowserRuntime,
    OfficialBrowserSession,
)


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


def _runtime():
    return OfficialBrowserRuntime(
        provider_id="test",
        base_url="https://example.test",
        allowed_domain_suffixes=("example.test",),
        identity_fields=("user_id",),
    )


def _session(budget, **overrides):
    return OfficialBrowserSession(
        key="account",
        browser=overrides.get("browser", SimpleNamespace(close=AsyncMock())),
        context=overrides.get("context", SimpleNamespace(close=AsyncMock())),
        page=SimpleNamespace(),
        backend="patchright",
        state_digest="",
        input_digest="",
        credential_digest="",
        proxy_url="",
        rule_revision=1,
        rule_digest="",
        build_id="",
        created_at=0,
        playwright=overrides.get("playwright", SimpleNamespace(stop=AsyncMock())),
        browser_manager=overrides.get("browser_manager"),
        driver_processes=(1234,),
        browser_lease=budget.acquire_sync("test"),
        proxy_tunnel=SimpleNamespace(release=Mock()),
    )


@pytest.mark.asyncio
@pytest.mark.parametrize("phase", ["context", "browser", "driver", "browser_manager"])
@pytest.mark.parametrize("failure", ["timeout", "exception"])
async def test_failed_shutdown_phase_does_not_leak_process_budget_or_tunnel(
    monkeypatch, caplog, phase, failure
):
    monkeypatch.setattr(
        official_browser,
        "core_settings",
        lambda: SimpleNamespace(
            official_browser_session_pool_size=1, browser_cleanup_timeout_seconds=0.02
        ),
    )
    budget = BrowserProcessBudget(1)
    runtime = _runtime()
    session = _session(budget)

    async def fail():
        if failure == "timeout":
            await asyncio.Event().wait()
        raise RuntimeError("private cleanup failure detail")

    failing_close = AsyncMock(side_effect=fail)
    if phase == "browser_manager":

        async def exit_manager(*_):
            await fail()

        session.browser_manager = SimpleNamespace(__aexit__=AsyncMock(side_effect=exit_manager))
    else:
        owner = session.playwright if phase == "driver" else getattr(session, phase)
        setattr(owner, "stop" if phase == "driver" else "close", failing_close)

    main_thread = threading.get_ident()
    killed = []

    def terminate(processes, *, label):
        assert threading.get_ident() != main_thread
        assert budget.snapshot().in_use == 1
        session.proxy_tunnel.release.assert_not_called()
        killed.append(processes)

    monkeypatch.setattr(official_browser, "terminate_residual_browser_process", terminate)
    try:
        await asyncio.wait_for(runtime._close_session(session), 0.5)
        assert budget.snapshot().in_use == 0
        session.proxy_tunnel.release.assert_called_once()
        assert killed == [(1234,)]
        assert f"phase={phase}" in caplog.text
        assert "private cleanup failure detail" not in caplog.text
        session.playwright.stop.assert_awaited_once()
    finally:
        await runtime.close()


@pytest.mark.asyncio
async def test_cancelled_shutdown_keeps_ownership_until_cleanup_finishes(monkeypatch):
    budget = BrowserProcessBudget(1)
    runtime = _runtime()
    started = asyncio.Event()
    finish = asyncio.Event()

    async def close_context():
        started.set()
        await finish.wait()

    session = _session(budget, context=SimpleNamespace(close=AsyncMock(side_effect=close_context)))
    monkeypatch.setattr(
        official_browser, "terminate_residual_browser_process", lambda *_, **__: None
    )
    closing = asyncio.create_task(runtime._close_session(session))
    try:
        await started.wait()
        closing.cancel()
        await asyncio.sleep(0)
        assert not closing.done()
        assert budget.snapshot().in_use == 1
        session.proxy_tunnel.release.assert_not_called()
    finally:
        finish.set()
        with pytest.raises(asyncio.CancelledError):
            await asyncio.wait_for(closing, 0.5)
        await runtime.close()
    assert budget.snapshot().in_use == 0
    session.proxy_tunnel.release.assert_called_once()


@pytest.mark.asyncio
async def test_residual_process_shutdown_does_not_block_event_loop(monkeypatch):
    budget = BrowserProcessBudget(1)
    runtime = _runtime()
    session = _session(budget)
    started = threading.Event()
    finish = threading.Event()

    def terminate(*_, **__):
        started.set()
        assert finish.wait(timeout=1)

    monkeypatch.setattr(official_browser, "terminate_residual_browser_process", terminate)
    closing = asyncio.create_task(runtime._close_session(session))
    try:
        assert await asyncio.to_thread(started.wait, 0.5)
        await asyncio.sleep(0)
        assert not closing.done()
        assert budget.snapshot().in_use == 1
    finally:
        finish.set()
        await asyncio.wait_for(closing, 0.5)
        await runtime.close()
    assert budget.snapshot().in_use == 0


@pytest.mark.asyncio
async def test_watchdog_unblocks_driver_finally_during_cancelled_close(monkeypatch):
    monkeypatch.setattr(
        official_browser,
        "core_settings",
        lambda: SimpleNamespace(
            official_browser_session_pool_size=1, browser_cleanup_timeout_seconds=0.02
        ),
    )
    budget = BrowserProcessBudget(1)
    runtime = _runtime()
    loop = asyncio.get_running_loop()
    killed = asyncio.Event()

    async def close_context():
        try:
            await asyncio.Event().wait()
        finally:
            await killed.wait()

    def terminate(*_, **__):
        loop.call_soon_threadsafe(killed.set)

    monkeypatch.setattr(browser_lifecycle, "_driver_pid", lambda _: 1234)
    monkeypatch.setattr(browser_lifecycle, "_terminate_process_tree", terminate)
    monkeypatch.setattr(
        official_browser, "terminate_residual_browser_process", lambda *_, **__: None
    )
    session = _session(budget, context=SimpleNamespace(close=AsyncMock(side_effect=close_context)))
    try:
        await asyncio.wait_for(runtime._close_session(session), 0.5)
        assert killed.is_set()
        assert budget.snapshot().in_use == 0
        session.proxy_tunnel.release.assert_called_once()
    finally:
        await runtime.close()


@pytest.mark.asyncio
async def test_cancelled_startup_releases_session_slot_even_if_context_close_stalls(monkeypatch):
    monkeypatch.setattr(
        official_browser,
        "core_settings",
        lambda: SimpleNamespace(
            official_browser_session_pool_size=1,
            browser_cleanup_timeout_seconds=0.02,
            registration_headless=True,
        ),
    )
    budget = BrowserProcessBudget(1)
    monkeypatch.setattr(official_browser, "browser_process_budget", budget)
    started = asyncio.Event()

    async def navigate(*_, **__):
        started.set()
        await asyncio.Event().wait()

    async def hanging_close():
        await asyncio.Event().wait()

    page = SimpleNamespace(goto=AsyncMock(side_effect=navigate))
    context = SimpleNamespace(
        new_page=AsyncMock(return_value=page), close=AsyncMock(side_effect=hanging_close)
    )
    browser = SimpleNamespace(new_context=AsyncMock(return_value=context), close=AsyncMock())
    playwright = SimpleNamespace(
        chromium=SimpleNamespace(launch=AsyncMock(return_value=browser)), stop=AsyncMock()
    )
    monkeypatch.setattr(
        official_browser,
        "async_playwright",
        lambda: SimpleNamespace(start=AsyncMock(return_value=playwright)),
    )
    tunnel = SimpleNamespace(release=Mock())
    monkeypatch.setattr(official_browser, "retain_proxy_tunnel", lambda _: tunnel)
    runtime = _runtime()
    credential = {
        "user_id": "account",
        "browser_execution_context": {"schema_version": 1, "backend": "patchright"},
    }

    async def prepare():
        async with runtime.account_operation(credential):
            await runtime.session_for(credential, "", default_runtime_plan().active)

    task = asyncio.create_task(prepare())
    try:
        await started.wait()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await asyncio.wait_for(task, 0.5)
        assert budget.snapshot().in_use == 0
        tunnel.release.assert_called_once()
        browser.close.assert_awaited_once()
        playwright.stop.assert_awaited_once()
        async with runtime.account_operation(credential):
            pass
    finally:
        await runtime.close()
