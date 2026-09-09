import asyncio

import pytest

from any2api_automation.browser_budget import BrowserProcessBudget


@pytest.mark.asyncio
async def test_async_budget_never_exceeds_capacity() -> None:
    budget = BrowserProcessBudget(2)
    active = 0
    maximum_active = 0
    lock = asyncio.Lock()

    async def worker() -> None:
        nonlocal active, maximum_active
        lease = await budget.acquire_async("test")
        try:
            async with lock:
                active += 1
                maximum_active = max(maximum_active, active)
            await asyncio.sleep(0.01)
        finally:
            async with lock:
                active -= 1
            lease.release()

    await asyncio.gather(*(worker() for _ in range(8)))

    assert maximum_active == 2
    assert budget.snapshot().in_use == 0


@pytest.mark.asyncio
async def test_sync_and_async_callers_share_budget() -> None:
    budget = BrowserProcessBudget(1)
    sync_lease = budget.acquire_sync("sync")
    pending = asyncio.create_task(budget.acquire_async("async"))
    await asyncio.sleep(0.01)

    assert not pending.done()
    sync_lease.release()
    async_lease = await pending
    async_lease.release()
    async_lease.release()

    assert budget.snapshot().in_use == 0


@pytest.mark.asyncio
async def test_cancelled_async_acquire_does_not_leak_capacity() -> None:
    budget = BrowserProcessBudget(1)
    held = budget.acquire_sync("held")
    pending = asyncio.create_task(budget.acquire_async("cancelled"))
    await asyncio.sleep(0.01)
    pending.cancel()

    with pytest.raises(asyncio.CancelledError):
        await pending

    held.release()
    replacement = await budget.acquire_async("replacement")
    replacement.release()

    assert budget.snapshot().in_use == 0
