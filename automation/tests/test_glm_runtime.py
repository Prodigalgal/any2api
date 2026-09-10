from __future__ import annotations

import json
from contextlib import asynccontextmanager

import pytest

from any2api_automation.providers import glm as glm_module
from any2api_automation.providers.glm import GlmAutomationProvider
from any2api_automation.providers.glm_runtime import (
    _UPLOAD_FILE,
    _filter_storage_state,
    _upload_glm_images,
    _validate_semantic_command,
    build_glm_command,
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
    assert [origin["origin"] for origin in result["origins"]] == ["https://chat.z.ai"]


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
        _validate_semantic_command(command)


def test_glm_builds_current_chat_envelopes_in_automation() -> None:
    command = build_glm_command(_semantic_command(), "user@example.test", 1_785_337_442_000)

    assert command["chat"]["models"] == ["glm-5.2"]
    assert command["completion"]["model"] == "glm-5.2"
    assert command["completion"]["signature_prompt"] == "hello"
    assert command["completion"]["features"]["auto_web_search"] is True
    assert command["completion"]["features"]["preview_mode"] is False
    assert command["completion"]["features"]["reasoning_effort"] == "high"


def test_glm_attaches_uploaded_images_to_the_official_vlm_message_shape() -> None:
    command = _semantic_command()
    command["messages"] = [
        {
            "role": "user",
            "content": [
                {"type": "input_text", "text": "describe"},
                {"type": "input_image", "image_url": "data:image/png;base64,YQ=="},
            ],
        }
    ]
    uploaded = [
        {
            "message_index": 0,
            "file": {
                "type": "image",
                "id": "file-1",
                "media": "image",
                "status": "uploaded",
            },
        }
    ]

    result = build_glm_command(
        command,
        "user@example.test",
        timestamp_ms=1_785_337_442_000,
        uploaded_files=uploaded,
        user_message_id="message-1",
    )

    assert result["chat"]["history"]["currentId"] == "message-1"
    assert result["chat"]["history"]["messages"]["message-1"]["files"][0]["id"] == "file-1"
    content = result["completion"]["messages"][0]["content"]
    assert content == [
        {"type": "text", "text": "describe"},
        {"type": "image_url", "image_url": {"url": "file-1"}},
    ]
    assert result["completion"]["files"][0]["id"] == "file-1"


def test_glm_uploads_inline_images_through_the_authenticated_page() -> None:
    class FakePage:
        def evaluate(self, script: str, payload: dict[str, str]) -> dict[str, object]:
            assert script == _UPLOAD_FILE
            assert payload["filename"] == "any2api-image.png"
            assert payload["dataUrl"] == "data:image/png;base64,YQ=="
            return {"status": 201, "body": json.dumps({"id": "file-1"})}

    command = _semantic_command()
    command["messages"] = [
        {
            "role": "user",
            "content": [
                {
                    "type": "input_image",
                    "image_url": "data:image/png;base64,YQ==",
                }
            ],
        }
    ]

    uploaded = _upload_glm_images(FakePage(), command, "message-1", "https://chat.z.ai")

    assert uploaded == [
        {
            "message_index": 0,
            "file": {
                "type": "image",
                "file": {"id": "file-1"},
                "id": "file-1",
                "url": "https://chat.z.ai/api/v1/files/file-1",
                "name": "any2api-image.png",
                "media": "image",
                "status": "uploaded",
                "size": 1,
                "ref_user_msg_id": "message-1",
            },
        }
    ]


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
                "operation": "chat",
                "semantic_command": _semantic_command(),
                "runtime_plan": _runtime_plan(),
                "credential": {},
            }
        )
    ]

    assert frames == [{"type": "error", "data": "official browser stream failed (RuntimeError)"}]


def _semantic_command() -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "requestId": "request-1",
        "protocol": "RESPONSES",
        "model": "glm-5.2",
        "stream": True,
        "messages": [{"role": "user", "content": "hello"}],
        "generation": {"temperature": 0.3},
        "reasoning": {"effort": "high"},
        "tools": [],
        "providerOptions": {"preview_mode": False, "web_search": True},
        "controls": {"web_search": True},
    }


def _runtime_plan() -> dict[str, object]:
    return {
        "active": {
            "providerId": "glm",
            "revision": 1,
            "rules": {
                "schemaVersion": 1,
                "sessionMaxAgeSeconds": 900,
                "canaryTimeoutSeconds": 60,
                "buildAssetMarkers": ["/assets/index-"],
                "discoveryMarkers": {
                    "newChat": ["/chats/new"],
                    "completion": ["X-Signature"],
                    "requestContext": ["sortedPayload"],
                    "sign": ["5*60*1e3"],
                },
                "capabilities": {},
                "endpointPaths": {
                    "chat": "/api/v2/chat/completions",
                    "apiBase": "/api/v2",
                },
            },
        },
        "candidate": None,
    }
