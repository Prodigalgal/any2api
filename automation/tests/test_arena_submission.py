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
    def __init__(self, submitted=None, *, blocked=False, template=False):
        self.submitted = submitted
        self.blocked = blocked
        self.template = template
        self.draft = ""
        self.handler = None
        self.fill = AsyncMock(side_effect=self.fill_draft)
        self.input_value = AsyncMock(side_effect=lambda: self.draft)
        self.exit_template = AsyncMock(side_effect=self.clear_template)
        self.submit = AsyncMock(side_effect=self.send)
        self.submission_started = asyncio.Event()
        self.ready_controls = object()
        self.ready_intersections = 0
        self.unroute = AsyncMock(side_effect=self.remove_route)
        self.fulfill = AsyncMock()
        self.goto = AsyncMock()

    async def route(self, _pattern, handler):
        self.handler = handler

    async def remove_route(self, _pattern, handler):
        assert self.handler is handler
        self.handler = None

    def locator(self, selector):
        if selector == ":enabled:visible":
            return self.ready_controls
        return SimpleNamespace(fill=self.fill, input_value=self.input_value)

    def get_by_role(self, role, *, name, exact):
        assert role == "button" and exact
        if name == "Exit template":
            button = SimpleNamespace(
                is_visible=AsyncMock(return_value=self.template), press=self.exit_template
            )
        else:
            assert name == "Send message"
            button = SimpleNamespace(press=self.submit)

        def ready(controls):
            assert controls is self.ready_controls
            self.ready_intersections += 1
            return button

        button.and_ = ready
        return button

    async def clear_template(self, key):
        assert key == "Enter"
        self.template = False

    async def fill_draft(self, text):
        if self.template:
            raise ValueError("Template fields are still active")
        self.draft = text

    async def send(self, key):
        assert key == "Enter"
        self.submission_started.set()
        if self.blocked:
            await asyncio.Future()
        await self.handler(
            SimpleNamespace(
                request=SimpleNamespace(method="POST", post_data=json.dumps(self.submitted)),
                fulfill=self.fulfill,
            )
        )


@pytest.mark.asyncio
@pytest.mark.parametrize("template", [False, True])
@pytest.mark.parametrize(
    "prompt", ["[system]\nFollow tools\n[user]\n你好", "", "你好\r\n第二行\r🙂"]
)
async def test_native_preparation_never_generates_and_preserves_the_canonical_body(
    monkeypatch, prompt, template
):
    runtime = _runtime()
    monkeypatch.setattr(runtime, "before_stream_request", AsyncMock())
    page = NativePage(template=template)
    request = _request(prompt)
    original_body = request["body"]
    tokens = []
    expected_draft = (prompt or "[attachment]").replace("\r\n", "\n").replace("\r", "\n")
    try:
        for index in range(2):
            page.submitted = {
                "cx": f"native-context-{index}",
                "recaptchaV3Token": f"fresh-{index}",
                "userMessage": {"content": expected_draft},
            }
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
        page.fill.assert_awaited_with(expected_draft)
        assert page.exit_template.await_count == int(template)
        assert page.submit.await_count == 2
        assert page.ready_intersections == 2 + int(template)
        page.submit.assert_awaited_with("Enter")
        if template:
            page.exit_template.assert_awaited_once_with("Enter")
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
        await page.submission_started.wait()
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
async def test_frontend_whitespace_normalization_keeps_the_canonical_prompt_unchanged(monkeypatch):
    runtime = _runtime()
    monkeypatch.setattr(runtime, "before_stream_request", AsyncMock())
    request = _request("\r\n  第一行\r\n最后一行  \n")
    original_body = request["body"]
    page = NativePage(
        {
            "cx": "context",
            "recaptchaV3Token": "token",
            "userMessage": {"content": "第一行\n最后一行"},
        }
    )
    try:
        prepared = await runtime.prepare_stream_request(page, request)
        assert prepared["body"] == original_body
        page.fill.assert_awaited_once_with("\n  第一行\n最后一行  \n")
        assert page.handler is None
    finally:
        await runtime.close()


@pytest.mark.asyncio
async def test_native_submission_with_a_template_or_stale_prompt_never_generates(monkeypatch):
    runtime = _runtime()
    monkeypatch.setattr(runtime, "before_stream_request", AsyncMock())
    page = NativePage(
        {"cx": "context", "recaptchaV3Token": "token", "userMessage": {"content": "stale template"}}
    )
    try:
        with pytest.raises(ValueError, match="prompt does not match"):
            await runtime.prepare_stream_request(page, _request())
        page.fulfill.assert_awaited_once()
        assert page.fulfill.await_args.kwargs["status"] == 418
        assert page.handler is None
    finally:
        await runtime.close()


@pytest.mark.asyncio
async def test_modified_composer_fails_before_submission_and_removes_the_route(monkeypatch):
    runtime = _runtime()
    monkeypatch.setattr(runtime, "before_stream_request", AsyncMock())
    page = NativePage()
    page.input_value.return_value = "different draft"
    page.input_value.side_effect = None
    try:
        with pytest.raises(ValueError, match="did not retain"):
            await runtime.prepare_stream_request(page, _request())
        page.submit.assert_not_awaited()
        page.fulfill.assert_not_awaited()
        assert page.handler is None
    finally:
        await runtime.close()


@pytest.mark.asyncio
async def test_template_exit_failure_cleans_up_without_filling_an_unrelated_field(monkeypatch):
    runtime = _runtime()
    monkeypatch.setattr(runtime, "before_stream_request", AsyncMock())
    page = NativePage(template=True)
    page.exit_template.side_effect = RuntimeError("template control unavailable")
    try:
        with pytest.raises(RuntimeError, match="template control"):
            await runtime.prepare_stream_request(page, _request())
        page.fill.assert_not_awaited()
        page.submit.assert_not_awaited()
        assert page.handler is None
    finally:
        await runtime.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("with_media", [False, True])
async def test_chat_uses_inference_deadline_and_logs_only_safe_media_metadata(
    monkeypatch, with_media
):
    runtime = _runtime()
    observed = []
    monkeypatch.setattr(
        runtime, "models", AsyncMock(return_value={"status": 200, "body": '{"models": []}'})
    )

    async def stream(*_args, **kwargs):
        observed.append(kwargs)
        yield {"type": "data", "data": 'a0:"answer"'}

    monkeypatch.setattr(runtime, "stream", stream)
    if with_media:
        monkeypatch.setattr(arena_browser, "arena_media_sources", lambda _: [{}])
        monkeypatch.setattr(
            runtime,
            "upload_attachments",
            AsyncMock(
                return_value={
                    "attachments": [
                        {
                            "name": "private.png",
                            "contentType": "image/png",
                            "url": "https://storage.example.test/private.png?signature=secret",
                        }
                    ],
                }
            ),
        )
    monkeypatch.setattr(
        arena_browser, "core_settings", lambda: SimpleNamespace(inference_timeout_seconds=240)
    )
    command = {
        "schemaVersion": 1,
        "requestId": "fixture",
        "protocol": "RESPONSES",
        "model": "11111111-1111-4111-8111-111111111111",
        "stream": True,
        "messages": [{"role": "user", "content": "private prompt"}],
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
        assert events[-1]["type"] == "data"
        assert observed[0]["timeout_ms"] == 240_000
        assert "private prompt" in observed[0]["body"]
        diagnostics = [event for event in events if event["type"] == "diagnostic"]
        assert len(diagnostics) == (2 if with_media else 0)
        for event in diagnostics:
            assert "private" not in json.dumps(event) and "secret" not in json.dumps(event)
            assert event["attachmentCount"] == 1
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
