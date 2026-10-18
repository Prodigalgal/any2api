import asyncio
import json
from contextvars import ContextVar
from types import SimpleNamespace

import pytest

from any2api_automation import provider_api
from any2api_automation.session_pool import AccountSessionPool


@pytest.fixture
def short_first_frame_timeout(monkeypatch):
    monkeypatch.setattr(
        provider_api,
        "core_settings",
        lambda: SimpleNamespace(inference_first_frame_timeout_seconds=0.02),
    )


@pytest.mark.asyncio
async def test_first_frame_timeout_cancels_waiter_without_stealing_a_session(
    short_first_frame_timeout,
):
    closed = asyncio.Event()
    pool = AccountSessionPool(1, lambda _: asyncio.sleep(0))

    async def waiting():
        try:
            async with pool.borrow("busy"):
                pytest.fail("timed out waiter took the active account")
                yield b"unused"
        finally:
            closed.set()

    async with pool.borrow("busy") as slot:
        slot.value = "existing session"
        frames = [json.loads(frame) async for frame in provider_api._guard_action_stream(waiting())]
        assert slot.value == "existing session"
    assert frames == [
        {"type": "status", "status": 504},
        {"type": "error", "data": "provider action timed out before first frame"},
    ]
    assert closed.is_set()
    async with pool.borrow("next"):
        pass
    await pool.close()


@pytest.mark.asyncio
async def test_first_frame_deadline_does_not_limit_an_active_stream(short_first_frame_timeout):
    operation = ContextVar("operation")
    closed = asyncio.Event()

    async def active():
        token = operation.set("account")
        try:
            yield b"first"
            await asyncio.sleep(0.04)
            assert operation.get() == "account"
            yield b"later"
        finally:
            operation.reset(token)
            closed.set()

    assert [frame async for frame in provider_api._guard_action_stream(active())] == [
        b"first",
        b"later",
    ]
    assert closed.is_set()


@pytest.mark.asyncio
async def test_cancelled_request_closes_preparing_stream_without_error_frames(
    short_first_frame_timeout,
):
    started = asyncio.Event()
    closed = asyncio.Event()

    async def preparing():
        try:
            started.set()
            await asyncio.Event().wait()
            yield b"unused"
        finally:
            closed.set()

    stream = provider_api._guard_action_stream(preparing())
    task = asyncio.create_task(anext(stream))
    await started.wait()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert closed.is_set()
    await stream.aclose()


@pytest.mark.asyncio
async def test_stopped_consumer_closes_the_active_provider_iterator():
    closed = asyncio.Event()

    async def active():
        try:
            yield b"first"
            yield b"unconsumed"
        finally:
            closed.set()

    stream = provider_api._guard_action_stream(active())
    assert await anext(stream) == b"first"
    await stream.aclose()
    assert closed.is_set()


@pytest.mark.asyncio
async def test_provider_timeout_is_not_misreported_as_the_first_frame_deadline(
    short_first_frame_timeout,
):
    async def failing():
        raise TimeoutError("provider's own timeout")
        yield b"unused"

    frames = [json.loads(frame) async for frame in provider_api._guard_action_stream(failing())]
    assert frames == [
        {"type": "status", "status": 502},
        {"type": "error", "data": "provider action failed (TimeoutError)"},
    ]


@pytest.mark.asyncio
@pytest.mark.parametrize("synchronous", [False, True])
async def test_empty_stream_preserves_empty_result(synchronous):
    async def empty():
        if False:
            yield b"unused"

    stream = [] if synchronous else empty()
    assert [frame async for frame in provider_api._guard_action_stream(stream)] == []


@pytest.mark.asyncio
async def test_synchronous_stream_keeps_existing_frames():
    assert [frame async for frame in provider_api._guard_action_stream([b"one", b"two"])] == [
        b"one",
        b"two",
    ]
