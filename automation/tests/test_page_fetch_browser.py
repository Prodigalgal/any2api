import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from any2api_automation.providers.mimo_browser import default_runtime_plan
from any2api_automation.providers.page_fetch_browser import (
    PageFetchBrowserRuntime,
    _data_shape,
    _resolve_target_path,
)


def test_endpoint_key_resolves_without_a_fallback_path() -> None:
    assert (
        _resolve_target_path("", "completion", {"completion": "/api/v0/chat/completion"})
        == "/api/v0/chat/completion"
    )


def test_missing_endpoint_key_still_requires_a_valid_fallback_path() -> None:
    with pytest.raises(ValueError, match="same-origin path"):
        _resolve_target_path("", "missing", {})


def test_data_shape_exposes_event_metadata_without_event_content() -> None:
    assert _data_shape(
        '{"event":{"type":"content","content":"secret answer"},"lastOne":false}'
    ) == (
        "json:top=event,lastOne;event=content;event_keys=content,type;"
        "content_type=str;content_len=13"
    )


def test_data_shape_marks_non_json_payloads() -> None:
    assert _data_shape("upstream returned html") == "non_json"


@pytest.mark.asyncio
@pytest.mark.parametrize("cancel_stream", [False, True])
async def test_stream_owns_one_challenge_helper_and_closes_it_before_releasing_the_page(
    monkeypatch, cancel_stream
) -> None:
    runtime = PageFetchBrowserRuntime(
        "fixture",
        "https://example.test",
        allowed_domain_suffixes=("example.test",),
        identity_fields=("user_id",),
    )
    started = asyncio.Event()
    stopped = asyncio.Event()
    helper_calls = 0

    async def solve(_page):
        nonlocal helper_calls
        helper_calls += 1
        started.set()
        try:
            await asyncio.Future()
        finally:
            stopped.set()

    async def evaluate(_script, request):
        event = {
            "requestId": request["requestId"],
            "type": "recaptcha",
            "version": "v2",
            "state": "required",
        }
        runtime._emit(event)
        runtime._emit(event)
        await started.wait()
        if cancel_stream:
            await asyncio.Future()
        runtime._emit({"requestId": request["requestId"], "type": "status", "status": 403})

    plan = default_runtime_plan()
    session = SimpleNamespace(page=SimpleNamespace(evaluate=evaluate))
    monkeypatch.setattr(
        runtime, "_select_session", AsyncMock(return_value=(session, plan.active, []))
    )
    monkeypatch.setattr(runtime, "credential_patch", AsyncMock(return_value={}))
    monkeypatch.setattr(runtime, "_try_solve_page_recaptcha_v2", solve)
    cancel_request = AsyncMock()
    monkeypatch.setattr(runtime, "cancel_stream_request", cancel_request)
    stream = runtime.stream({"user_id": "account"}, "", plan, method="POST", path="/chat")
    try:
        async with asyncio.timeout(2):
            if cancel_stream:
                await anext(stream)
                await started.wait()
                await stream.aclose()
            else:
                async for _ in stream:
                    pass
        assert stopped.is_set()
        assert helper_calls == 1
        assert runtime._stream_queues == {}
        assert cancel_request.await_count == int(cancel_stream)
    finally:
        await stream.aclose()
        await runtime.close()


@pytest.mark.asyncio
async def test_recaptcha_helper_targets_one_visible_anchor_when_the_hidden_v3_frame_exists():
    runtime = PageFetchBrowserRuntime(
        "fixture",
        "https://example.test",
        allowed_domain_suffixes=("example.test",),
        identity_fields=("user_id",),
    )
    clicked = AsyncMock()
    checkbox = SimpleNamespace(
        count=AsyncMock(return_value=1), is_visible=AsyncMock(return_value=True), click=clicked
    )
    visible_frame = SimpleNamespace(locator=lambda _: SimpleNamespace(first=checkbox))

    class AmbiguousFrames:
        @property
        def first(self):
            return visible_frame

        def locator(self, _):
            raise AssertionError("multiple visible widgets must resolve to a single frame")

    class Page:
        def frame_locator(self, selector):
            if ":visible" not in selector:
                raise AssertionError("the hidden v3 anchor must not shadow the visible v2 widget")
            return AmbiguousFrames()

    try:
        await runtime._try_solve_page_recaptcha_v2(Page())
        clicked.assert_awaited_once_with(timeout=5000)
    finally:
        await runtime.close()
