import asyncio
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from any2api_automation.providers import arena_browser
from any2api_automation.providers.runtime_rules import default_runtime_plan_for_provider


def _runtime():
    runtime = arena_browser.ArenaOfficialBrowserTransport(
        "https://arena.ai", page_path="/", me_path="/api/me", chat_path="/chat"
    )
    runtime.wait_until_ready = AsyncMock()
    return runtime


def _request(prompt="[user]\n你好"):
    return {
        "url": "https://arena.ai/chat",
        "method": "POST",
        "body": json.dumps(
            {
                "userMessage": {"content": prompt, "experimental_attachments": [{"id": "image"}]},
                "modelAId": "requested-model",
            }
        ),
    }


class NativePage:
    def __init__(self, submitted=None, *, blocked=False):
        self.submitted = submitted
        self.blocked = blocked
        self.handler = None
        self.fill = AsyncMock()
        self.press_started = asyncio.Event()
        self.unroute = AsyncMock(side_effect=self.remove_route)
        self.fulfill = AsyncMock()
        self.goto = AsyncMock()

    async def route(self, _pattern, handler):
        self.handler = handler

    async def remove_route(self, _pattern, handler):
        assert self.handler is handler
        self.handler = None

    def locator(self, _selector):
        return SimpleNamespace(first=SimpleNamespace(fill=self.fill, press=self.press))

    async def press(self, key):
        assert key == "Enter"
        self.press_started.set()
        if self.blocked:
            await asyncio.Future()
        await self.handler(
            SimpleNamespace(
                request=SimpleNamespace(method="POST", post_data=json.dumps(self.submitted)),
                fulfill=self.fulfill,
            )
        )


@pytest.mark.asyncio
@pytest.mark.parametrize("prompt", ["[system]\nFollow tools\n[user]\n你好", ""])
async def test_native_preparation_never_generates_and_preserves_the_canonical_body(
    monkeypatch, prompt
):
    runtime = _runtime()
    monkeypatch.setattr(runtime, "before_stream_request", AsyncMock())
    page = NativePage()
    request = _request(prompt)
    original_body = request["body"]
    tokens = []
    try:
        for index in range(2):
            page.submitted = {"cx": f"native-context-{index}", "recaptchaV3Token": f"fresh-{index}"}
            prepared = await runtime.prepare_stream_request(page, request)
            tokens.append(prepared["nativeSubmission"]["recaptchaV3Token"])
            assert prepared["body"] == original_body
            assert "nativeSubmission" not in request
            assert page.handler is None
        assert tokens == ["fresh-0", "fresh-1"]
        assert page.fulfill.await_count == 2
        page.goto.assert_awaited_once_with(
            "https://arena.ai", wait_until="domcontentloaded", timeout=30_000
        )
        assert page.fulfill.await_args.kwargs["status"] == 418
        page.fill.assert_awaited_with(prompt or "[attachment]")
    finally:
        await runtime.close()


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "submitted", [None, [], {}, {"cx": "context"}, {"cx": 4, "recaptchaV3Token": "token"}]
)
async def test_incomplete_native_context_fails_without_a_generation_request(monkeypatch, submitted):
    runtime = _runtime()
    monkeypatch.setattr(runtime, "before_stream_request", AsyncMock())
    page = NativePage(submitted)
    try:
        with pytest.raises(ValueError, match="incomplete"):
            await runtime.prepare_stream_request(page, _request())
        assert page.handler is None
        page.fulfill.assert_awaited_once()
    finally:
        await runtime.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("cancel", [False, True])
async def test_native_preparation_removes_its_route_on_timeout_or_cancel(monkeypatch, cancel):
    runtime = _runtime()
    monkeypatch.setattr(runtime, "before_stream_request", AsyncMock())
    monkeypatch.setattr(arena_browser, "_ARENA_NATIVE_SUBMISSION_TIMEOUT_SECONDS", 0.02)
    page = NativePage(blocked=True)
    operation = asyncio.create_task(runtime.prepare_stream_request(page, _request()))
    try:
        await page.press_started.wait()
        if cancel:
            operation.cancel()
        with pytest.raises(asyncio.CancelledError if cancel else TimeoutError):
            await operation
        assert page.handler is None
        page.unroute.assert_awaited_once()
        page.fulfill.assert_not_awaited()
    finally:
        await runtime.close()


@pytest.mark.asyncio
async def test_chat_uses_the_inference_deadline_instead_of_the_catalog_canary_deadline(monkeypatch):
    runtime = _runtime()
    observed = []
    monkeypatch.setattr(
        runtime, "models", AsyncMock(return_value={"status": 200, "body": '{"models": []}'})
    )

    async def stream(*_args, **kwargs):
        observed.append(kwargs)
        yield {"type": "data", "data": 'a0:"answer"'}

    monkeypatch.setattr(runtime, "stream", stream)
    monkeypatch.setattr(
        arena_browser, "core_settings", lambda: SimpleNamespace(inference_timeout_seconds=240)
    )
    command = {
        "schemaVersion": 1,
        "requestId": "fixture",
        "protocol": "RESPONSES",
        "model": "11111111-1111-4111-8111-111111111111",
        "stream": True,
        "messages": [{"role": "user", "content": "你好"}],
        "generation": {},
        "reasoning": {},
        "tools": [],
        "providerOptions": {},
        "controls": {},
    }
    try:
        events = [
            event
            async for event in runtime.chat_stream(
                {}, command, "", default_runtime_plan_for_provider("arena")
            )
        ]
        assert events[0]["type"] == "data"
        assert observed[0]["timeout_ms"] == 240_000
    finally:
        await runtime.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("refresh_status", [200, 502])
async def test_shared_catalog_cache_never_replays_another_accounts_browser_state(
    monkeypatch, refresh_status
):
    runtime = _runtime()
    owner_patch = {"browser_execution_context": {"storage_state": {"owner": "account-a"}}}
    catalog = [{"id": "Max", "metadata": {"arena_model_id": "model-id"}}]

    def parse(body):
        if body != "valid-catalog":
            raise ValueError("invalid catalog")
        return catalog

    request = AsyncMock(
        side_effect=[
            {
                "status": 200,
                "body": "valid-catalog",
                "credential_patch": owner_patch,
                "runtime_reports": [{"revision": "account-a-plan"}],
                "content_type": "text/html",
                "transport_mode": "camoufox_browser_runtime",
            },
            {"status": refresh_status, "body": "invalid-catalog"},
        ]
    )
    monkeypatch.setattr(runtime, "request", request)
    monkeypatch.setattr(arena_browser, "parse_arena_models", parse)
    plan = default_runtime_plan_for_provider("arena")
    try:
        fresh = await runtime.models({"email": "account-a"}, "", plan)
        assert fresh["credential_patch"] == owner_patch
        for force_refresh in (False, True):
            cached = await runtime.models(
                {"email": "account-b"}, "", plan, force_refresh=force_refresh
            )
            assert cached["status"] == 200
            assert json.loads(cached["body"])["models"] == catalog
            assert "credential_patch" not in cached
            assert "runtime_reports" not in cached
        assert request.await_count == 2
    finally:
        await runtime.close()
