from __future__ import annotations

import base64
import binascii
import re
from collections.abc import Iterable
from typing import Any

TEXT_CONTENT_TYPES = frozenset({"text", "input_text", "output_text"})
MEDIA_CONTENT_TYPES = {
    "image_url": "image",
    "input_image": "image",
    "image": "image",
    "input_audio": "audio",
    "audio_url": "audio",
    "audio": "audio",
    "video_url": "video",
    "input_video": "video",
    "video": "video",
    "file": "file",
    "input_file": "file",
    "attachment": "file",
}
_DATA_URL = re.compile(r"\Adata:([^;,\s]+);base64,([^\s]*)\Z", re.IGNORECASE)


def content_blocks(value: Any, provider: str) -> list[dict[str, Any]]:
    """Validate one canonical message content value without losing media blocks."""
    if isinstance(value, str):
        return [{"type": "text", "text": value}]
    if not isinstance(value, list):
        raise TypeError(f"{provider} message content must be a string or an array")

    blocks: list[dict[str, Any]] = []
    for index, item in enumerate(value):
        if isinstance(item, str):
            blocks.append({"type": "text", "text": item})
            continue
        if not isinstance(item, dict):
            raise TypeError(f"{provider} content block {index} must be an object or string")
        block = dict(item)
        block_type = str(block.get("type") or "").strip().lower()
        if block_type in TEXT_CONTENT_TYPES:
            if not isinstance(block.get("text"), str):
                raise ValueError(f"{provider} text content blocks require a string text field")
            block["type"] = block_type
            blocks.append(block)
            continue
        kind = MEDIA_CONTENT_TYPES.get(block_type)
        if kind is None:
            raise ValueError(f"{provider} unsupported content block type: {block_type}")
        _validate_media_source(block, block_type, kind, provider)
        block["type"] = block_type
        blocks.append(block)
    return blocks


def iter_media_blocks(messages: Any, provider: str) -> Iterable[tuple[int, str, dict[str, Any]]]:
    if not isinstance(messages, list):
        raise TypeError(f"{provider} semantic command messages must be an array")
    for message_index, message in enumerate(messages):
        if not isinstance(message, dict):
            raise TypeError(f"{provider} messages must contain objects")
        for block in content_blocks(message.get("content", ""), provider):
            kind = MEDIA_CONTENT_TYPES.get(str(block.get("type") or "").lower())
            if kind is not None:
                yield message_index, kind, block


def text_content(value: Any, provider: str, *, allow_media: bool = False) -> str:
    blocks = content_blocks(value, provider)
    text: list[str] = []
    for block in blocks:
        kind = MEDIA_CONTENT_TYPES.get(str(block.get("type") or "").lower())
        if kind is not None:
            if not allow_media:
                raise ValueError(
                    f"{provider} runtime cannot translate {kind} content blocks without "
                    "a provider upload strategy"
                )
            continue
        text.append(str(block.get("text") or ""))
    return "\n".join(part for part in text if part)


def xai_input_content(value: Any, provider: str, *, role: str) -> list[dict[str, Any]]:
    """Map canonical blocks to xAI Responses input blocks without dropping media."""
    blocks = content_blocks(value, provider)
    normalized_role = str(role or "user").lower()
    output: list[dict[str, Any]] = []
    for block in blocks:
        block_type = str(block.get("type") or "").lower()
        kind = MEDIA_CONTENT_TYPES.get(block_type)
        if kind is None:
            output.append(
                {
                    "type": "output_text" if normalized_role == "assistant" else "input_text",
                    "text": str(block.get("text") or ""),
                }
            )
            continue
        if kind in {"audio", "video"}:
            raise ValueError(
                f"{provider} xAI Responses input does not support {kind} blocks in this adapter"
            )
        source = media_source(block)
        if kind == "image":
            target: dict[str, Any] = {"type": "input_image"}
            field = _media_source_field(block, "image_url")
            target["file_id" if field == "file_id" else "image_url"] = source
        else:
            target = {"type": "input_file"}
            field = _media_source_field(block, "file")
            target[
                "file_id"
                if field == "file_id"
                else "file_url"
                if field == "file_url"
                else "file_data"
            ] = source
            filename = _string_value(block.get("filename"))
            for nested_key in ("file", "input_file", "attachment", "source"):
                nested_value = block.get(nested_key)
                if not filename and isinstance(nested_value, dict):
                    filename = _string_value(nested_value.get("filename"))
            if filename:
                target["filename"] = filename
        output.append(target)
    return output


def media_source(block: dict[str, Any]) -> str:
    block_type = str(block.get("type") or "").strip().lower()
    kind = MEDIA_CONTENT_TYPES.get(block_type)
    if kind is None:
        return ""
    if kind == "image":
        value = block.get("image_url", block.get("image", block.get("source")))
        return _source_value(value) or _string_value(block.get("file_id"))
    if kind == "audio":
        value = block.get(
            "input_audio", block.get("audio_url", block.get("audio", block.get("source")))
        )
        return _source_value(value)
    if kind == "video":
        value = block.get(
            "video_url", block.get("input_video", block.get("video", block.get("source")))
        )
        return _source_value(value)
    value = block.get("file", block.get("input_file", block.get("attachment", block.get("source"))))
    return _source_value(value) or next(
        (
            _string_value(block.get(field))
            for field in ("file_url", "file_id", "file_data", "data")
            if _string_value(block.get(field))
        ),
        "",
    )


def decode_inline_data_url(
    source: str,
    provider: str,
    *,
    max_bytes: int,
    expected_prefix: str | None = None,
) -> tuple[str, bytes]:
    match = _DATA_URL.fullmatch(str(source or "").strip())
    if match is None:
        raise ValueError(f"{provider} media input must be an inline base64 data URL")
    content_type = match.group(1).lower()
    if expected_prefix and not content_type.startswith(expected_prefix.lower()):
        raise ValueError(f"{provider} media input must use {expected_prefix} content")
    try:
        content = base64.b64decode(match.group(2), validate=True)
    except (binascii.Error, ValueError) as error:
        raise ValueError(f"{provider} media input contains invalid base64") from error
    if not content or len(content) > max_bytes:
        raise ValueError(f"{provider} media input exceeds the configured upload limit")
    return content_type, content


def _validate_media_source(
    block: dict[str, Any],
    block_type: str,
    kind: str,
    provider: str,
) -> None:
    source = media_source(block)
    if not source:
        raise ValueError(f"{provider} {kind} content block {block_type} has no source")


def _media_source_field(block: dict[str, Any], field_name: str) -> str:
    for field in ("file_id", "file_url", "file_data", "data", "base64"):
        if _string_value(block.get(field)):
            return _normalized_source_field(field)
    value = block.get(field_name)
    if isinstance(value, dict):
        for field in ("file_id", "file_url", "url", "file_data", "data", "base64"):
            if _string_value(value.get(field)):
                return _normalized_source_field(field)
    elif isinstance(value, str):
        return "file_url"
    nested = block.get("file") or block.get("input_file")
    if not isinstance(nested, dict):
        nested = block.get("attachment") or block.get("source")
    if isinstance(nested, dict):
        for field in ("file_id", "file_url", "url", "file_data", "data", "base64"):
            if _string_value(nested.get(field)):
                return _normalized_source_field(field)
    return "file_data"


def _normalized_source_field(field: str) -> str:
    if field == "file_id":
        return "file_id"
    if field in {"file_url", "url"}:
        return "file_url"
    return "file_data"


def _source_value(value: Any) -> str:
    if isinstance(value, str):
        return value.strip()
    if not isinstance(value, dict):
        return ""
    for field in (
        "url",
        "audio_url",
        "video_url",
        "file_id",
        "file_url",
        "file_data",
        "data",
        "base64",
    ):
        result = _string_value(value.get(field))
        if result:
            return result
    nested = value.get("source")
    if nested is not value:
        result = _source_value(nested)
        if result:
            return result
    return ""


def _string_value(value: Any) -> str:
    return value.strip() if isinstance(value, str) else ""
