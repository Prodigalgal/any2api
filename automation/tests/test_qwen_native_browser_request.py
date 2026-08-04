from __future__ import annotations

import base64

import pytest
from pydantic import ValidationError

from any2api_automation.providers.qwen_risk import (
    NativeBrowserRequest,
    QwenNativeBrowserTransport,
)


def test_qwen_native_browser_request_accepts_provider_scoped_paths() -> None:
    request = NativeBrowserRequest(
        path="/api/v2/chat/completions?chat_id=chat-1",
        body="{}",
        bearer_token="token-value-that-is-long-enough",
        referer_path="/c/chat-1",
    )

    assert request.path.endswith("chat_id=chat-1")
    assert request.referer_path == "/c/chat-1"


@pytest.mark.parametrize(
    ("path", "referer"),
    [
        ("https://example.com/api/v2/chat", "/"),
        ("//example.com/api/v2/chat", "/"),
        ("/api/v2/chat", "https://example.com/"),
        ("/admin/secrets", "/"),
    ],
)
def test_qwen_native_browser_request_rejects_cross_origin_paths(path: str, referer: str) -> None:
    with pytest.raises(ValidationError):
        NativeBrowserRequest(
            path=path,
            bearer_token="token-value-that-is-long-enough",
            referer_path=referer,
        )


@pytest.mark.asyncio
async def test_qwen_native_transport_passes_the_path_to_the_browser_script() -> None:
    class FakePage:
        def is_closed(self) -> bool:
            return False

        async def evaluate(self, _script: str, payload: dict[str, object]) -> dict[str, object]:
            assert payload["path"] == "/api/v2/chats/new"
            return {
                "status": 200,
                "contentType": "application/json",
                "requestId": "request-id",
                "retryAfter": "",
                "bodyBase64": base64.b64encode(b'{"data":{"id":"chat"}}').decode(),
            }

    transport = QwenNativeBrowserTransport()
    transport._page = FakePage()
    transport._frontend_version = "current"

    response = await transport.fetch(
        NativeBrowserRequest(
            path="/api/v2/chats/new",
            body="{}",
            bearer_token="token-value-that-is-long-enough",
            referer_path="/c/new-chat",
        )
    )

    assert response["status"] == 200
    assert base64.b64decode(response["body_base64"]) == b'{"data":{"id":"chat"}}'
