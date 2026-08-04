from __future__ import annotations

import pytest
from pydantic import ValidationError

from any2api_automation.providers.qwen_risk import NativeBrowserRequest


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
