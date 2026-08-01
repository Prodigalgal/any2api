from __future__ import annotations

import base64
from contextlib import contextmanager
from types import SimpleNamespace

import pytest

from any2api_automation.browser_transport import BrowserRequest
from any2api_automation.providers import glm_runtime
from any2api_automation.providers.glm_runtime import (
    _END,
    _completion_request,
    _Flow,
    _FlowManager,
    _start_completion,
    _StreamMetadata,
)


def completion_request(**overrides: object) -> BrowserRequest:
    values: dict[str, object] = {
        "method": "POST",
        "path": "/api/v2/chat/completions?requestId=request-1",
        "headers": {
            "Content-Type": "application/json",
            "x-fe-version": "prod-fe-fixture",
            "x-region": "us-west-2",
            "x-signature": "fixture-signature",
        },
        "fingerprint_profile": "same_origin_fetch",
        "json_body": {"model": "glm-5.2", "messages": []},
        "timeout_seconds": 120,
        "referer_path": "/c/chat-1",
    }
    values.update(overrides)
    return BrowserRequest.model_validate(values)


def test_bound_completion_injects_ticket_without_mutating_java_body() -> None:
    request = completion_request()

    result = _completion_request(request, "official-ticket")

    assert result["path"] == request.path
    assert result["referer_path"] == "/c/chat-1"
    assert result["body"]["captcha_verify_param"] == "official-ticket"
    assert "captcha_verify_param" not in request.json_body


@pytest.mark.parametrize(
    "overrides",
    (
        {"path": "https://attacker.invalid/api/v2/chat/completions"},
        {"path": "/api/v1/auths/"},
        {"headers": {"Authorization": "secret"}},
        {"referer_path": "/"},
    ),
)
def test_bound_completion_rejects_paths_and_headers_outside_provider_contract(
    overrides: dict[str, object],
) -> None:
    with pytest.raises(ValueError):
        _completion_request(completion_request(**overrides), "official-ticket")


def test_bound_completion_starts_fetch_with_validated_payload() -> None:
    captured: dict[str, object] = {}

    class Page:
        def evaluate(self, script: str, payload: object) -> dict[str, object]:
            captured["script"] = script
            captured["payload"] = payload
            return {"status": 200, "content_type": "text/event-stream"}

    result = _start_completion(Page(), completion_request(), "official-ticket")

    assert result.status == 200
    assert result.content_type == "text/event-stream"
    assert captured["payload"]["body"]["captcha_verify_param"] == "official-ticket"
    assert "target.searchParams.set" in captured["script"]
    assert "navigator.userAgent" in captured["script"]


def test_bound_flow_is_single_use_and_session_scoped() -> None:
    manager = _FlowManager()
    flow = _Flow("a" * 32, "session-1", 120)
    manager._flows[flow.id] = flow

    assert manager.claim("session-1", flow.id) is flow
    with pytest.raises(KeyError):
        manager.claim("session-1", flow.id)

    other = _Flow("b" * 32, "session-2", 120)
    manager._flows[other.id] = other
    with pytest.raises(KeyError):
        manager.claim("session-1", other.id)
    assert other.cancel.is_set()


def test_bound_flow_solves_and_streams_on_the_same_worker_page(monkeypatch) -> None:
    captured: dict[str, object] = {}

    class Page:
        reads = 0

        def goto(self, url: str, **kwargs: object) -> None:
            captured["goto"] = (url, kwargs)

        def wait_for_timeout(self, milliseconds: int) -> None:
            captured["wait"] = milliseconds

        def evaluate(self, script: str, payload: object | None = None) -> dict[str, object]:
            if "fetch(path" in script:
                captured["fetch"] = payload
                return {"status": 200, "content_type": "text/event-stream"}
            self.reads += 1
            if self.reads == 1:
                return {
                    "done": False,
                    "body": base64.b64encode(b"data: [DONE]\n\n").decode(),
                }
            return {"done": True, "body": ""}

    page = Page()

    class Context:
        def set_default_timeout(self, milliseconds: int) -> None:
            captured["timeout"] = milliseconds

        def add_cookies(self, cookies: object) -> None:
            captured["cookies"] = cookies

        def new_page(self) -> Page:
            return page

        def close(self) -> None:
            captured["closed"] = True

    class Browser:
        def new_context(self, **kwargs: object) -> Context:
            captured["context"] = kwargs
            return Context()

    entry = SimpleNamespace(
        proxy_url="http://proxy.example:8080",
        browser=SimpleNamespace(
            user_agent="fixture-agent",
            browser_cookies=lambda: [{"name": "session", "value": "value", "domain": ".z.ai"}],
        ),
    )

    @contextmanager
    def lease(session_id: str):
        captured["session_id"] = session_id
        yield entry

    @contextmanager
    def launch(*args: object, **kwargs: object):
        captured["launch"] = (args, kwargs)
        yield "patchright", Browser()

    class Challenge:
        last_diagnostic = "official-ticket"

        def solve(self, current_page: object, *, timeout_seconds: int) -> str:
            assert current_page is page
            captured["captcha_timeout"] = timeout_seconds
            return "official-ticket"

    monkeypatch.setattr(glm_runtime.browser_session_manager, "lease", lease)
    monkeypatch.setattr(glm_runtime, "launch_browser", launch)
    monkeypatch.setattr(
        glm_runtime.GlmAliyunChallenge,
        "for_chat",
        classmethod(lambda cls: Challenge()),
    )
    manager = _FlowManager()

    flow = manager.prepare("session-1", 120)
    claimed = manager.claim("session-1", flow.id)
    claimed.command.put(completion_request())
    metadata = claimed.metadata.get(timeout=2)
    chunks: list[bytes] = []
    while True:
        item = claimed.chunks.get(timeout=2)
        if item is _END:
            break
        assert isinstance(item, bytes)
        chunks.append(item)
    assert claimed.thread is not None
    claimed.thread.join(timeout=2)

    assert metadata == _StreamMetadata(200, "text/event-stream")
    assert chunks == [b"data: [DONE]\n\n"]
    assert captured["fetch"]["body"]["captcha_verify_param"] == "official-ticket"
    assert captured["closed"] is True
    assert not claimed.thread.is_alive()
