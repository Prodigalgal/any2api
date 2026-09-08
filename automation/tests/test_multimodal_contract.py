from __future__ import annotations

import json
from pathlib import Path
from unittest.mock import AsyncMock

import pytest

from any2api_automation.providers.deepseek_browser import build_deepseek_request
from any2api_automation.providers.glm_runtime import build_glm_command
from any2api_automation.providers.grok_browser import build_grok_request
from any2api_automation.providers.grok_console_browser import build_grok_console_request
from any2api_automation.providers.grok_web_browser import build_grok_web_request
from any2api_automation.providers.longcat_browser import build_longcat_request
from any2api_automation.providers.mimo_browser import build_mimo_chat_request
from any2api_automation.providers.minmax import build_minmax_request
from any2api_automation.providers.multimodal import (
    content_blocks,
    decode_inline_data_url,
    iter_media_blocks,
    media_source,
)
from any2api_automation.providers.qwen import build_qwen_request
from any2api_automation.providers.qwen_risk import (
    _UPLOAD_MEDIA,
    NativeBrowserRequest,
    QwenNativeBrowserTransport,
    _AccountBrowserSession,
)


def test_shared_content_contract_covers_image_audio_video_and_file_sources() -> None:
    assert "OSS4-HMAC-SHA256" in _UPLOAD_MEDIA
    blocks = content_blocks(
        [
            {"type": "input_text", "text": "inspect"},
            {"type": "input_image", "image_url": {"url": "https://example.test/a.png"}},
            {"type": "input_audio", "input_audio": {"data": "YQ==", "format": "wav"}},
            {"type": "input_video", "input_video": {"video_url": "https://example.test/a.mp4"}},
            {"type": "input_file", "input_file": {"file_id": "file-1"}},
        ],
        "contract",
    )

    assert [kind for _, kind, _ in iter_media_blocks([{"content": blocks}], "contract")] == [
        "image",
        "audio",
        "video",
        "file",
    ]


def test_browser_runtime_schema_declares_all_canonical_media_aliases() -> None:
    schema_path = (
        Path(__file__).resolve().parents[2]
        / "contracts"
        / "schemas"
        / ("browser-runtime-command.schema.json")
    )
    schema = json.loads(schema_path.read_text(encoding="utf-8"))

    aliases = schema["$defs"]["media_content_block"]["properties"]["type"]["enum"]
    assert set(aliases) == {
        "image",
        "image_url",
        "input_image",
        "audio",
        "audio_url",
        "input_audio",
        "video",
        "video_url",
        "input_video",
        "file",
        "input_file",
        "attachment",
    }


@pytest.mark.parametrize(
    ("block", "expected"),
    [
        (
            {"type": "image", "source": {"type": "base64", "data": "YQ=="}},
            "YQ==",
        ),
        (
            {"type": "attachment", "attachment": {"file_url": "https://example.test/a.pdf"}},
            "https://example.test/a.pdf",
        ),
        (
            {"type": "input_video", "source": {"type": "url", "url": "https://example.test/a.mp4"}},
            "https://example.test/a.mp4",
        ),
    ],
)
def test_shared_media_source_supports_nested_provider_neutral_shapes(
    block: dict[str, object], expected: str
) -> None:
    normalized = content_blocks([block], "contract")[0]

    assert media_source(normalized) == expected


def test_inline_media_decoder_accepts_the_limit_and_rejects_one_byte_over() -> None:
    source = "data:image/png;base64,YQ=="

    assert decode_inline_data_url(
        source, "contract image", max_bytes=1, expected_prefix="image/"
    ) == ("image/png", b"a")

    with pytest.raises(ValueError, match="upload limit"):
        decode_inline_data_url(source, "contract image", max_bytes=0, expected_prefix="image/")


@pytest.mark.parametrize(
    "source",
    ["", "data:image/png;base64,not-base64", "data:audio/wav;base64,YQ=="],
)
def test_inline_media_decoder_rejects_empty_invalid_or_wrong_media(source: str) -> None:
    with pytest.raises(ValueError):
        decode_inline_data_url(
            source,
            "contract image",
            max_bytes=1024,
            expected_prefix="image/",
        )


@pytest.mark.parametrize(
    "builder",
    [
        lambda command: build_deepseek_request(command, "session-1"),
        build_grok_web_request,
        build_longcat_request,
        lambda command: build_glm_command(command, "user@example.test", timestamp_ms=1),
    ],
)
@pytest.mark.parametrize(
    "media_block",
    [
        {"type": "input_image", "image_url": "data:image/png;base64,YQ=="},
        {"type": "input_audio", "input_audio": {"data": "YQ==", "format": "wav"}},
        {"type": "input_video", "input_video": {"video_url": "https://example.test/a.mp4"}},
        {"type": "input_file", "input_file": {"file_id": "file-1"}},
    ],
)
def test_text_only_browser_adapters_fail_closed_instead_of_dropping_media(
    builder,
    media_block: dict[str, object],
) -> None:
    command = _command(
        [
            {
                "role": "user",
                "content": [{"type": "input_text", "text": "inspect"}, media_block],
            }
        ]
    )

    with pytest.raises(ValueError, match="(image|audio|video|file)"):
        builder(command)


@pytest.mark.parametrize("builder", [build_grok_request, build_grok_console_request])
@pytest.mark.parametrize(
    "media_block",
    [
        {"type": "input_audio", "input_audio": {"data": "YQ==", "format": "wav"}},
        {"type": "input_video", "input_video": {"video_url": "https://example.test/a.mp4"}},
    ],
)
def test_xai_browser_adapters_reject_media_without_a_supported_payload(
    builder,
    media_block: dict[str, object],
) -> None:
    with pytest.raises(ValueError, match="(audio|video)"):
        builder(_command([{"role": "user", "content": [media_block]}]))


def test_xai_browser_adapters_preserve_image_and_file_blocks() -> None:
    command = _command(
        [
            {
                "role": "user",
                "content": [
                    {"type": "input_text", "text": "inspect"},
                    {"type": "input_image", "image_url": "https://example.test/a.png"},
                    {
                        "type": "input_file",
                        "input_file": {
                            "file_url": "https://example.test/a.pdf",
                            "filename": "a.pdf",
                        },
                    },
                ],
            }
        ]
    )

    for builder in (build_grok_request, build_grok_console_request):
        body = builder(command)
        content = body["input"][0]["content"]
        assert [item["type"] for item in content] == [
            "input_text",
            "input_image",
            "input_file",
        ]
        assert content[1]["image_url"] == "https://example.test/a.png"
        assert content[2]["file_url"] == "https://example.test/a.pdf"
        assert content[2]["filename"] == "a.pdf"


@pytest.mark.parametrize(
    ("provider", "builder", "supported"),
    [
        ("deepseek", lambda command: build_deepseek_request(command, "session-1"), set()),
        (
            "glm",
            lambda command: build_glm_command(command, "user@example.test", timestamp_ms=1),
            set(),
        ),
        ("grok", build_grok_request, {"image", "file"}),
        ("grok_console", build_grok_console_request, {"image", "file"}),
        ("grok_web", build_grok_web_request, set()),
        ("longcat", build_longcat_request, set()),
        (
            "mimo",
            lambda command: build_mimo_chat_request(command, uploaded_media=[{"url": "media-1"}]),
            {"image"},
        ),
        ("minmax", build_minmax_request, {"image"}),
        (
            "qwen",
            lambda command: build_qwen_request(
                command, "chat-1", uploaded_files=[{"id": "file-1"}]
            ),
            {"image"},
        ),
    ],
)
@pytest.mark.parametrize(
    ("kind", "media_block"),
    [
        ("image", {"type": "input_image", "image_url": "data:image/png;base64,YQ=="}),
        ("audio", {"type": "input_audio", "input_audio": {"data": "YQ==", "format": "wav"}}),
        (
            "video",
            {"type": "input_video", "input_video": {"video_url": "https://example.test/a.mp4"}},
        ),
        ("file", {"type": "input_file", "input_file": {"file_id": "file-1"}}),
    ],
)
def test_all_nine_provider_builders_have_an_explicit_multimodal_outcome(
    provider: str, builder, supported: set[str], kind: str, media_block: dict[str, object]
) -> None:
    command = _command(
        [
            {
                "role": "user",
                "content": [{"type": "input_text", "text": "inspect"}, media_block],
            }
        ]
    )

    if kind in supported:
        body = builder(command)
        assert isinstance(body, dict), provider
        return

    with pytest.raises((ValueError, TypeError), match=kind):
        builder(command)


def test_mimo_image_requires_a_completed_upload_result() -> None:
    command = _command(
        [
            {
                "role": "user",
                "content": [
                    {"type": "input_text", "text": "describe"},
                    {"type": "input_image", "image_url": "data:image/png;base64,YQ=="},
                ],
            }
        ]
    )

    with pytest.raises(ValueError, match="same account browser session"):
        build_mimo_chat_request(command)

    body = build_mimo_chat_request(command, uploaded_media=[{"url": "media-1"}])
    assert body["query"].endswith("[USER]\ndescribe")
    assert body["multiMedias"] == [{"url": "media-1"}]


def test_minmax_image_attachment_preserves_order_and_rejects_other_media() -> None:
    command = _command(
        [
            {
                "role": "user",
                "content": [
                    {"type": "input_text", "text": "first"},
                    {"type": "input_image", "image_url": "data:image/png;base64,YQ=="},
                    {"type": "image_url", "image_url": "data:image/jpeg;base64,Yg=="},
                ],
            }
        ]
    )

    prepared = build_minmax_request(command)

    assert [item["mime_type"] for item in prepared["attachments"]] == [
        "image/png",
        "image/jpeg",
    ]

    with pytest.raises(ValueError, match="audio"):
        build_minmax_request(
            _command(
                [
                    {
                        "role": "user",
                        "content": [{"type": "input_audio", "input_audio": {"data": "YQ=="}}],
                    }
                ]
            )
        )


def test_qwen_image_file_is_attached_without_flattening_the_image_into_text() -> None:
    command = _command(
        [
            {"role": "system", "content": "follow the instruction"},
            {
                "role": "user",
                "content": [
                    {"type": "input_text", "text": "describe"},
                    {"type": "input_image", "image_url": "data:image/png;base64,YQ=="},
                ],
            },
        ]
    )
    file = {"type": "image", "id": "file-1", "file_class": "vision"}

    body = build_qwen_request(command, "chat-1", uploaded_files=[file])

    message = body["messages"][0]
    assert message["content"].startswith("[System instructions]")
    assert message["content"].endswith("describe")
    assert message["files"] == [file]


def test_qwen_image_upload_does_not_replace_preexisting_provider_files() -> None:
    command = _command(
        [
            {
                "role": "user",
                "content": [
                    {"type": "input_text", "text": "describe"},
                    {"type": "input_image", "image_url": "data:image/png;base64,YQ=="},
                ],
                "files": [{"id": "existing-file"}],
            }
        ]
    )

    body = build_qwen_request(
        command,
        "chat-1",
        uploaded_files=[{"id": "uploaded-image"}],
    )

    assert body["messages"][0]["files"] == [
        {"id": "existing-file"},
        {"id": "uploaded-image"},
    ]


@pytest.mark.asyncio
async def test_qwen_media_upload_stays_in_the_account_browser_context() -> None:
    class Page:
        def __init__(self) -> None:
            self.script = ""
            self.payload: dict[str, object] = {}

        async def evaluate(
            self, script: str, payload: dict[str, object]
        ) -> list[dict[str, object]]:
            self.script = script
            self.payload = payload
            return [{"type": "image", "id": "file-1", "file_class": "vision"}]

    page = Page()
    session = _AccountBrowserSession("account", object(), page, "current")
    transport = QwenNativeBrowserTransport()
    transport._session_for = AsyncMock(return_value=session)
    transport._prepare_authenticated_surface = AsyncMock()
    transport._credential_patch = AsyncMock(return_value={})

    request = NativeBrowserRequest(
        path="/api/v2/files/getstsToken",
        bearer_token="token-value-that-is-long-enough",
        referer_path="/c/chat-1",
    )
    sources = [
        {
            "data_url": "data:image/png;base64,YQ==",
            "filename": "input.png",
            "mime_type": "image/png",
            "size": 1,
        }
    ]

    result = await transport.upload_media(request, sources, 1024, user_id="user-1")

    assert result["files"][0]["id"] == "file-1"
    assert "OSS4-HMAC-SHA256" in page.script
    assert "x-oss-security-token" in page.script
    assert page.payload["stsPath"] == "/api/v2/files/getstsToken"
    assert page.payload["sources"] == sources
    transport._prepare_authenticated_surface.assert_awaited_once()


@pytest.mark.asyncio
async def test_qwen_media_upload_rejects_remote_sources_before_opening_a_session() -> None:
    transport = QwenNativeBrowserTransport()
    transport._session_for = AsyncMock()
    request = NativeBrowserRequest(
        path="/api/v2/files/getstsToken",
        bearer_token="token-value-that-is-long-enough",
        referer_path="/c/chat-1",
    )

    with pytest.raises(ValueError, match="inline base64 data URL"):
        await transport.upload_media(
            request,
            [{"data_url": "https://example.test/image.png"}],
            1024,
        )

    transport._session_for.assert_not_awaited()


def _command(messages: list[dict[str, object]]) -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "requestId": "multimodal-test",
        "protocol": "CHAT_COMPLETIONS",
        "model": "model-1",
        "stream": True,
        "messages": messages,
        "generation": {},
        "reasoning": {},
        "tools": [],
        "providerOptions": {},
        "controls": {},
    }
