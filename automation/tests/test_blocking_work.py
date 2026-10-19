import asyncio
import contextvars
import logging
import threading

import pytest

from any2api_automation.blocking_work import (
    BlockingWorkPool,
    background_work,
    is_background_work,
    run_blocking,
)
from any2api_automation.browser_budget import BrowserProcessBudget


async def wait_until(predicate):
    async with asyncio.timeout(2):
        while not predicate():
            await asyncio.sleep(0.005)


@pytest.mark.asyncio
async def test_cancelled_worker_keeps_admission_until_its_thread_exits():
    pool = BlockingWorkPool(1, name="test-background")
    entered = threading.Event()
    release = threading.Event()

    def blocking():
        entered.set()
        release.wait(3)

    first = asyncio.create_task(pool.run(blocking))
    replacement = None
    try:
        await wait_until(entered.is_set)
        first.cancel()
        with pytest.raises(asyncio.CancelledError):
            await first
        assert pool.snapshot()["in_flight"] == 1
        assert pool.snapshot()["cancelled_in_flight"] == 1
        replacement = asyncio.create_task(pool.run(lambda: "replacement"))
        await asyncio.sleep(0.02)
        assert not replacement.done()
        assert pool.snapshot()["waiting"] == 1
    finally:
        release.set()
        if replacement is not None:
            assert await replacement == "replacement"
        await wait_until(lambda: pool.snapshot()["in_flight"] == 0)
        pool._executor.shutdown(wait=True)


@pytest.mark.asyncio
async def test_cancellation_reaches_a_thread_waiting_for_browser_capacity():
    budget = BrowserProcessBudget(1)
    owner = budget.acquire_sync("owner")
    entered = threading.Event()
    exited = threading.Event()
    acquired_after_cancellation = []

    def worker():
        entered.set()
        try:
            lease = budget.acquire_sync("waiter")
            acquired_after_cancellation.append(True)
            lease.release()
        finally:
            exited.set()

    pending = asyncio.create_task(run_blocking(worker))
    try:
        await wait_until(entered.is_set)
        pending.cancel()
        with pytest.raises(asyncio.CancelledError):
            await pending
        await wait_until(exited.is_set)
        assert not acquired_after_cancellation
        assert budget.snapshot().in_use == 1
        assert budget.snapshot().foreground_waiting == 0
    finally:
        owner.release()


@pytest.mark.asyncio
async def test_cleanup_and_reaper_progress_when_default_workers_are_waiting(monkeypatch):
    import concurrent.futures
    import importlib

    from any2api_automation.lifecycle.process_reaper import BrowserProcessReaper

    official = importlib.import_module("any2api_automation.providers.official_browser")
    loop = asyncio.get_running_loop()
    loop.set_default_executor(concurrent.futures.ThreadPoolExecutor(max_workers=2))
    budget = BrowserProcessBudget(2)
    owners = [budget.acquire_sync("owner") for _ in range(2)]
    entered = [threading.Event(), threading.Event()]
    terminated = []
    swept = []
    monkeypatch.setattr(
        official,
        "terminate_residual_browser_process",
        lambda *args, **kwargs: terminated.append(True),
    )

    def worker(index):
        entered[index].set()
        with budget.acquire_sync("waiting-worker"):
            pass

    workers = [asyncio.create_task(run_blocking(worker, index)) for index in range(2)]
    reaper = BrowserProcessReaper()
    monkeypatch.setattr(reaper, "sweep", lambda: swept.append(True) or 0)
    try:
        await wait_until(lambda: all(event.is_set() for event in entered))
        await asyncio.wait_for(reaper._sweep_async(), 1)
        assert swept
        await asyncio.wait_for(
            official.close_owned_browser(
                "fixture",
                logger=logging.getLogger("fixture"),
                context=None,
                browser=None,
                browser_manager=None,
                playwright=None,
                driver_processes=(42,),
                browser_lease=owners[0],
                proxy_tunnel=None,
            ),
            1,
        )
        assert terminated
        await asyncio.wait_for(asyncio.gather(*workers), 1)
    finally:
        for owner in owners:
            owner.release()
        await asyncio.gather(*workers, return_exceptions=True)
    assert budget.snapshot().in_use == 0


@pytest.mark.asyncio
async def test_background_worker_preserves_context_and_uses_separate_admission():
    correlation = contextvars.ContextVar("fixture-correlation")
    token = correlation.set("fixture")
    try:
        with background_work():
            assert await run_blocking(lambda: (correlation.get(), is_background_work())) == (
                "fixture",
                True,
            )
        assert not is_background_work()
    finally:
        correlation.reset(token)


@pytest.mark.asyncio
async def test_background_browser_acquisition_leaves_foreground_capacity():
    budget = BrowserProcessBudget(2, acquire_timeout_seconds=0.05)
    with background_work():
        background = await budget.acquire_async("background")
        with pytest.raises(TimeoutError):
            await budget.acquire_async("background-overflow")
    foreground = await budget.acquire_async("foreground")
    assert budget.snapshot().in_use == 2
    assert budget.snapshot().background_in_use == 1
    foreground.release()
    background.release()


@pytest.mark.asyncio
async def test_browser_capacity_wait_is_bounded_for_sync_and_async_callers():
    budget = BrowserProcessBudget(1, acquire_timeout_seconds=0.04)
    owner = budget.acquire_sync("owner")
    try:
        with pytest.raises(TimeoutError):
            await run_blocking(budget.acquire_sync, "sync-timeout")
        with pytest.raises(TimeoutError):
            await budget.acquire_async("async-timeout")
        assert budget.snapshot().in_use == 1
        assert budget.snapshot().foreground_waiting == 0
    finally:
        owner.release()


@pytest.mark.asyncio
async def test_model_discovery_uses_background_workers_while_chat_stays_foreground(monkeypatch):
    import importlib

    from any2api_automation.providers.actions import ProviderAction, ProviderActionRequest

    api = importlib.import_module("any2api_automation.provider_api")

    async def execute(request):
        return {"background": await run_blocking(is_background_work)}

    monkeypatch.setattr(api.action_dispatcher, "execute", execute)
    for action, expected in ((ProviderAction.MODEL_DISCOVERY, True), (ProviderAction.CHAT, False)):
        result = await api._execute_action(
            ProviderActionRequest(provider_id="mimo", action=action, channel="runtime")
        )
        assert result["background"] is expected


@pytest.mark.asyncio
async def test_glm_stream_cancellation_stops_a_worker_waiting_for_capacity(monkeypatch):
    import importlib
    from contextlib import asynccontextmanager
    from types import SimpleNamespace

    glm = importlib.import_module("any2api_automation.providers.glm")
    budget = BrowserProcessBudget(1)
    owner = budget.acquire_sync("owner")
    entered = threading.Event()
    exited = threading.Event()

    @asynccontextmanager
    async def proxy(*args, **kwargs):
        yield ""

    def stream(*args, **kwargs):
        entered.set()
        try:
            with budget.acquire_sync("glm-waiter"):
                yield {"type": "done"}
        finally:
            exited.set()

    monkeypatch.setattr(glm, "transport_proxy_lease", proxy)
    monkeypatch.setattr(
        glm,
        "parse_runtime_plan",
        lambda *args: SimpleNamespace(
            active=SimpleNamespace(rules=SimpleNamespace(canary_timeout_seconds=1)), candidate=None
        ),
    )
    monkeypatch.setattr(glm.official_browser_transport, "stream", stream)
    provider = glm.GlmAutomationProvider()
    generator = provider.transport_stream(
        {"operation": "chat", "semantic_command": {}, "credential": {"token": "fixture"}}
    )
    pending = asyncio.create_task(anext(generator))
    try:
        await wait_until(entered.is_set)
        pending.cancel()
        with pytest.raises(asyncio.CancelledError):
            await pending
        await wait_until(exited.is_set)
        assert budget.snapshot().in_use == 1
    finally:
        owner.release()
        await generator.aclose()


@pytest.mark.asyncio
async def test_minmax_stream_drains_bounded_queue_with_only_one_default_worker(monkeypatch):
    import concurrent.futures
    import importlib

    module = importlib.import_module("any2api_automation.providers.minmax_api_actions")
    asyncio.get_running_loop().set_default_executor(
        concurrent.futures.ThreadPoolExecutor(max_workers=1)
    )

    class Response:
        status_code = 200

        def __enter__(self):
            return self

        def __exit__(self, *args):
            return False

        def iter_lines(self):
            for _ in range(100):
                yield b"data: fixture"

        def close(self):
            pass

    class Client(Response):
        def __init__(self, **kwargs):
            pass

        def stream(self, *args, **kwargs):
            return Response()

    monkeypatch.setattr(module, "CurlSession", Client)
    monkeypatch.setattr(module, "_signed_request", lambda *args, **kwargs: ("https://fixture", {}))
    monkeypatch.setattr(module, "credential_patch_from_response", lambda response: {})
    async with asyncio.timeout(3):
        frames = [event async for event in module._api_stream({}, "POST", "/fixture", "{}", "")]
    assert len(frames) == 101
    assert frames[0] == {"type": "status", "status": 200}
    assert all(frame == {"type": "data", "data": "fixture"} for frame in frames[1:])
