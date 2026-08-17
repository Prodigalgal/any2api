from __future__ import annotations

import json
from contextlib import asynccontextmanager

import pytest

from any2api_automation.providers import glm as glm_module
from any2api_automation.providers.glm import GlmAutomationProvider
from any2api_automation.providers.glm_runtime import (
    _filter_storage_state,
    _validate_command,
    discover_runtime_names,
)


def test_glm_discovers_current_official_runtime_without_minified_names() -> None:
    source = (
        "const aa=async(t,e,r)=>{return fetch('/api/v1/chats/new')},"
        "bb=()=>{const x={sortedPayload:'a',urlParams:'b',timestamp:'c'};return x},"
        "cc=(t,e,r)=>{const bucket=Number(r)/(5*60*1e3);"
        "return {signature:String(bucket),timestamp:r}},"
        "dd=async(t='',e,r)=>{const h={'X-Signature':'value'};"
        "return fetch(r+'/chat/completions?query',{headers:h})};"
    )

    assert discover_runtime_names(source) == {
        "new_chat": "aa",
        "request_context": "bb",
        "sign": "cc",
        "completion": "dd",
    }


def test_glm_storage_injection_rejects_cross_provider_state() -> None:
    result = _filter_storage_state(
        {
            "cookies": [
                {"name": "glm", "value": "1", "domain": ".z.ai"},
                {"name": "foreign", "value": "2", "domain": ".example.com"},
            ],
            "origins": [
                {"origin": "https://chat.z.ai", "localStorage": []},
                {"origin": "https://example.com", "localStorage": []},
            ],
        }
    )

    assert [cookie["name"] for cookie in result["cookies"]] == ["glm"]
    assert [origin["origin"] for origin in result["origins"]] == [
        "https://chat.z.ai"
    ]


@pytest.mark.parametrize(
    "command",
    (
        {},
        {"chat": {}, "completion": {}, "prompt": ""},
        {"chat": [], "completion": {}, "prompt": "hello"},
        {"chat": {}, "completion": [], "prompt": "hello"},
    ),
)
def test_glm_rejects_incomplete_semantic_commands(command: dict[str, object]) -> None:
    with pytest.raises((TypeError, ValueError)):
        _validate_command(command)


@pytest.mark.asyncio
async def test_glm_transport_normalizes_worker_startup_failure_to_error_frame(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class BrokenTransport:
        def stream(self, *_args: object, **_kwargs: object):
            raise RuntimeError("runtime missing")
            yield {}

    @asynccontextmanager
    async def proxy_lease(*_args: object, **_kwargs: object):
        yield ""

    monkeypatch.setattr(glm_module, "official_browser_transport", BrokenTransport())
    monkeypatch.setattr(glm_module, "transport_proxy_lease", proxy_lease)

    frames = [
        json.loads(frame)
        async for frame in GlmAutomationProvider().transport_stream(
            {
                "method": "POST",
                "path": "/api/v2/chat/completions",
                "body": json.dumps(
                    {"chat": {}, "completion": {}, "prompt": "hello"}
                ),
                "credential": {},
            }
        )
    ]

    assert frames == [
        {"type": "error", "data": "official browser stream failed (RuntimeError)"}
    ]
