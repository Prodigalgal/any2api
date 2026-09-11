from __future__ import annotations

import base64
import binascii
import json
import logging
import re
import secrets
import time
from collections.abc import AsyncIterator
from typing import Any
from urllib.parse import urlparse
from uuid import UUID

from ..lifecycle.browser import BrowserResult, credential_from_context
from ..lifecycle.mail import Mailbox, TempMailClient
from ..lifecycle.registration import RegistrationStage, RegistrationTrace
from ..observability import OperationFailure
from .arena_settings import settings as arena_settings
from .base import validate_semantic_command
from .page_fetch_browser import PageFetchBrowserRuntime
from .runtime_rules import RuntimePlan

logger = logging.getLogger("any2api_automation.providers.arena_browser")

_UUID = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
    re.IGNORECASE,
)
_INITIAL_MODELS = re.compile(r'"initialModels"\s*:')
_ESCAPED_INITIAL_MODELS = re.compile(r'\\"initialModels\\"\s*:')
_TEXT_BLOCK_TYPES = frozenset({"text", "input_text", "output_text"})
_MEDIA_BLOCK_TYPES = frozenset(
    {
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
)
_MAX_MODELS = 512
_SUPPORTED_IMAGE_MIME_TYPES = frozenset({"image/png", "image/jpeg", "image/webp"})
_SUPPORTED_DOCUMENT_MIME_TYPES = frozenset({"application/pdf"})
_SUPPORTED_ATTACHMENT_MIME_TYPES = _SUPPORTED_IMAGE_MIME_TYPES | _SUPPORTED_DOCUMENT_MIME_TYPES
_SUPPORTED_ATTACHMENT_BLOCK_TYPES = frozenset(
    {"image", "image_url", "input_image", "file", "input_file", "attachment"}
)


def _uuid7() -> str:
    """Create a UUIDv7 without adding a third-party dependency to the worker."""

    timestamp = int(time.time() * 1000) & ((1 << 48) - 1)
    random_bits = secrets.randbits(74)
    value = (
        (timestamp << 80)
        | (7 << 76)
        | ((random_bits >> 62) << 64)
        | (2 << 62)
        | (random_bits & ((1 << 62) - 1))
    )
    return str(UUID(int=value))


def build_arena_request(
    command: dict[str, Any],
    *,
    model_id: str | None = None,
    attachments: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Map the canonical semantic command to Arena's create-evaluation body."""

    validate_semantic_command(command, "Arena")
    for field in ("generation", "reasoning"):
        if command[field]:
            raise ValueError(f"Arena does not support semantic command field: {field}")
    if command["tools"]:
        raise ValueError("Arena does not support tools")

    options = command["providerOptions"]
    controls = command["controls"]
    unsupported = sorted(set(options) - {"mode", "model_id", "web_search"})
    if unsupported:
        raise ValueError("Arena provider option is unsupported: " + unsupported[0])
    unsupported_controls = sorted(set(controls) - {"web_search"})
    if unsupported_controls:
        raise ValueError("Arena control is unsupported: " + unsupported_controls[0])
    mode = str(options.get("mode") or "direct").strip().lower()
    if mode != "direct":
        raise ValueError("Arena only supports direct mode")
    web_search = _arena_search_enabled(options, controls)

    explicit_model_id = model_id or options.get("model_id")
    requested_model = str(command.get("model") or "").strip()
    resolved_model_id = str(explicit_model_id or requested_model).strip()
    if not _UUID.fullmatch(resolved_model_id):
        raise ValueError(
            "Arena requires a current provider model UUID; model discovery must resolve "
            f"{requested_model or '<empty>'}"
        )
    if explicit_model_id is not None and not _UUID.fullmatch(str(explicit_model_id).strip()):
        raise ValueError("Arena provider option model_id must be a UUID")

    media_present = _has_supported_media(command["messages"])
    if media_present:
        arena_media_sources(command["messages"])
    if media_present and not attachments:
        raise ValueError("Arena media must be uploaded through the page-world uploader first")
    prompt = _arena_prompt(command["messages"], allow_empty=media_present)
    normalized_attachments = _normalize_arena_attachments(attachments or [])
    return {
        "id": _uuid7(),
        "mode": "direct",
        "modelAId": resolved_model_id,
        "userMessageId": _uuid7(),
        "modelAMessageId": _uuid7(),
        "userMessage": {
            "content": prompt,
            "experimental_attachments": normalized_attachments,
            "metadata": {},
        },
        "modality": "search" if web_search else "chat",
    }


def _arena_prompt(messages: Any, *, allow_empty: bool = False) -> str:
    if not isinstance(messages, list):
        raise TypeError("Arena messages must be an array")
    sections: list[str] = []
    for message in messages:
        if not isinstance(message, dict):
            raise TypeError("Arena messages must contain objects")
        role = str(message.get("role") or "user").strip().lower()
        if role not in {"system", "developer", "user", "assistant"}:
            raise ValueError(f"Arena does not support message role: {role}")
        if message.get("tool_calls"):
            raise ValueError("Arena does not support assistant tool calls")
        content = _arena_content(message.get("content"))
        if content:
            sections.append(f"[{role}]\n{content}")
    prompt = "\n\n".join(sections)
    if not prompt.strip() and not allow_empty:
        raise ValueError("Arena requires non-empty text input")
    encoded = prompt.encode("utf-8")
    if len(encoded) > arena_settings().arena_max_prompt_bytes:
        raise ValueError("Arena prompt exceeds the configured byte limit")
    return prompt


def _arena_content(content: Any) -> str:
    if content is None:
        return ""
    if isinstance(content, str):
        return content
    if not isinstance(content, list):
        raise TypeError("Arena message content must be a string or text block array")
    parts: list[str] = []
    for block in content:
        if isinstance(block, str):
            parts.append(block)
            continue
        if not isinstance(block, dict):
            raise TypeError("Arena message content blocks must be objects")
        block_type = str(block.get("type") or "").strip().lower()
        if block_type in _SUPPORTED_ATTACHMENT_BLOCK_TYPES:
            continue
        if block_type in _MEDIA_BLOCK_TYPES:
            raise ValueError(f"Arena does not support media content block: {block_type}")
        if block_type not in _TEXT_BLOCK_TYPES:
            raise ValueError(f"Arena does not support content block: {block_type or '<empty>'}")
        text = block.get("text")
        if not isinstance(text, str):
            raise TypeError("Arena text content blocks require a string text field")
        parts.append(text)
    return "".join(parts)


def _arena_search_enabled(options: dict[str, Any], controls: dict[str, Any]) -> bool:
    values: list[bool] = []
    for source, name in ((options, "provider_options.arena.web_search"), (controls, "web_search")):
        if "web_search" not in source:
            continue
        value = source["web_search"]
        if not isinstance(value, bool):
            raise TypeError(f"{name} must be a boolean")
        values.append(value)
    if len(set(values)) > 1:
        raise ValueError("Arena web_search conflicts between provider options and controls")
    return values[0] if values else False


def _has_supported_media(messages: Any) -> bool:
    if not isinstance(messages, list):
        return False
    for message in messages:
        if not isinstance(message, dict):
            continue
        content = message.get("content")
        if not isinstance(content, list):
            continue
        for block in content:
            if (
                isinstance(block, dict)
                and str(block.get("type") or "").strip().lower()
                in _SUPPORTED_ATTACHMENT_BLOCK_TYPES
            ):
                return True
    return False


def arena_media_sources(messages: Any) -> list[dict[str, Any]]:
    """Return inline image/PDF inputs for the page-world uploader.

    Arena's current chat UI first uploads a local File through its own signed-upload
    action and only then sends ``experimental_attachments``. Remote URLs and opaque
    file IDs therefore fail closed at this boundary.
    """

    if not isinstance(messages, list):
        raise TypeError("Arena messages must be an array")
    config = arena_settings()
    sources: list[dict[str, Any]] = []
    total_bytes = 0
    for message in messages:
        if not isinstance(message, dict):
            raise TypeError("Arena messages must contain objects")
        role = str(message.get("role") or "user").strip().lower()
        content = message.get("content")
        if not isinstance(content, list):
            continue
        for block in content:
            if not isinstance(block, dict):
                continue
            block_type = str(block.get("type") or "").strip().lower()
            if block_type not in _SUPPORTED_ATTACHMENT_BLOCK_TYPES:
                continue
            if role != "user":
                raise ValueError("Arena media content is only supported on user messages")
            source = _find_data_url(block)
            if not source:
                raise ValueError("Arena media upload currently requires an inline base64 data URL")
            mime_type, payload = _decode_arena_data_url(source, block_type)
            if mime_type not in _SUPPORTED_ATTACHMENT_MIME_TYPES:
                raise ValueError(
                    "Arena supports image/png, image/jpeg, image/webp, and application/pdf"
                )
            if len(payload) > config.arena_max_attachment_bytes:
                raise ValueError("Arena attachment exceeds the configured per-file limit")
            total_bytes += len(payload)
            if total_bytes > config.arena_max_total_attachment_bytes:
                raise ValueError("Arena attachments exceed the configured total byte limit")
            if len(sources) >= config.arena_max_attachments:
                raise ValueError("Arena supports at most the configured attachment count")
            sources.append(
                {
                    "data_url": source,
                    "filename": _arena_filename(block, mime_type, len(sources) + 1),
                    "mime_type": mime_type,
                    "size_bytes": len(payload),
                }
            )
    return sources


def _find_data_url(value: Any, *, depth: int = 0) -> str | None:
    if depth > 5:
        return None
    if isinstance(value, str):
        candidate = value.strip()
        return candidate if candidate.lower().startswith("data:") else None
    if isinstance(value, dict):
        for key in (
            "url",
            "image_url",
            "image",
            "file",
            "input_file",
            "file_data",
            "file_url",
            "attachment",
            "source",
            "data",
        ):
            result = _find_data_url(value.get(key), depth=depth + 1)
            if result:
                return result
    return None


def _decode_arena_data_url(source: str, block_type: str) -> tuple[str, bytes]:
    match = re.fullmatch(r"data:([^;,\s]+);base64,([A-Za-z0-9+/=]+)", source.strip())
    if match is None:
        raise ValueError(f"Arena {block_type} upload requires data:<mime>;base64,<payload>")
    mime_type = match.group(1).lower()
    try:
        payload = base64.b64decode(match.group(2), validate=True)
    except (binascii.Error, ValueError) as error:
        raise ValueError("Arena media data URL is not valid base64") from error
    if not payload:
        raise ValueError("Arena media data URL must not be empty")
    return mime_type, payload


def _arena_filename(block: dict[str, Any], mime_type: str, index: int) -> str:
    candidates: list[Any] = [block.get("filename"), block.get("file_name"), block.get("name")]
    for key in ("file", "input_file", "attachment", "source", "image_url"):
        nested = block.get(key)
        if isinstance(nested, dict):
            candidates.extend(nested.get(name) for name in ("filename", "file_name", "name"))
    for candidate in candidates:
        value = re.sub(r"[^A-Za-z0-9._-]", "_", str(candidate or "").strip())
        if value and value not in {".", ".."}:
            return value[:128]
    if mime_type == "application/pdf":
        return f"attachment-{index}.pdf"
    extension = mime_type.rsplit("/", 1)[-1].replace("jpeg", "jpg")
    return f"image-{index}.{extension}"


def _normalize_arena_attachments(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        raise TypeError("Arena attachments must be an array")
    if len(value) > arena_settings().arena_max_attachments:
        raise ValueError("Arena supports at most the configured attachment count")
    normalized: list[dict[str, Any]] = []
    for item in value:
        if not isinstance(item, dict):
            raise TypeError("Arena attachments must contain objects")
        name = _arena_filename(item, str(item.get("contentType") or ""), len(normalized) + 1)
        content_type = str(item.get("contentType") or "").strip().lower()
        url = str(item.get("url") or "").strip()
        if content_type not in _SUPPORTED_ATTACHMENT_MIME_TYPES:
            raise ValueError("Arena uploader returned an unsupported attachment type")
        parsed = urlparse(url)
        if parsed.scheme != "https" or not parsed.hostname:
            raise ValueError("Arena uploader returned a non-HTTPS attachment URL")
        normalized.append({"name": name, "contentType": content_type, "url": url})
    return normalized


def parse_arena_models(document: str, *, max_models: int = _MAX_MODELS) -> list[dict[str, Any]]:
    """Extract the public text catalog from the current Next.js/RSC direct page."""

    if not isinstance(document, str) or not document:
        raise ValueError("Arena model page is empty")
    if max_models < 1 or max_models > _MAX_MODELS:
        raise ValueError("Arena model catalog limit is outside the allowed range")

    models: list[dict[str, Any]] = []
    seen: set[str] = set()
    for source in _next_data_sources(document):
        for raw in _initial_model_arrays(source):
            if not isinstance(raw, list):
                continue
            for item in raw:
                parsed = _arena_model(item)
                if parsed is None:
                    continue
                key = parsed["id"].casefold()
                if key in seen:
                    continue
                seen.add(key)
                models.append(parsed)
                if len(models) >= max_models:
                    return models
    if not models:
        raise ValueError("Arena direct page did not expose a usable text model catalog")
    return models


def _next_data_sources(document: str) -> list[str]:
    sources = [document]
    for script in re.findall(r"<script\b[^>]*>(.*?)</script>", document, re.IGNORECASE | re.DOTALL):
        for match in re.finditer(r"\[\s*1\s*,\s*(\"(?:\\.|[^\"\\])*\")\s*\]", script):
            try:
                decoded = json.loads(match.group(1))
            except json.JSONDecodeError:
                continue
            if isinstance(decoded, str):
                sources.append(decoded)
    return list(dict.fromkeys(sources))


def _initial_model_arrays(source: str) -> list[Any]:
    arrays: list[Any] = []
    for matcher in (_INITIAL_MODELS, _ESCAPED_INITIAL_MODELS):
        for match in matcher.finditer(source):
            value = _extract_json_value(source, match.end())
            if value is None:
                continue
            try:
                arrays.append(json.loads(value))
            except json.JSONDecodeError:
                continue
    return arrays


def _extract_json_value(source: str, start: int) -> str | None:
    index = start
    while index < len(source) and source[index].isspace():
        index += 1
    if index >= len(source) or source[index] not in "[{":
        return None
    opening = source[index]
    closing = "]" if opening == "[" else "}"
    stack = [closing]
    in_string = False
    escaped = False
    for position in range(index + 1, len(source)):
        character = source[position]
        if in_string:
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == '"':
                in_string = False
            continue
        if character == '"':
            in_string = True
        elif character in "[{":
            stack.append("]" if character == "[" else "}")
        elif character in "]}":
            if not stack or character != stack[-1]:
                return None
            stack.pop()
            if not stack:
                return source[index : position + 1]
    return None


def _arena_model(item: Any) -> dict[str, Any] | None:
    if not isinstance(item, dict):
        return None
    arena_id = str(item.get("id") or "").strip()
    if not _UUID.fullmatch(arena_id) or item.get("userSelectable") is False:
        return None
    capabilities = item.get("capabilities")
    if not isinstance(capabilities, dict):
        return None
    input_capabilities = capabilities.get("inputCapabilities")
    if not isinstance(input_capabilities, dict) or input_capabilities.get("text") is not True:
        return None
    public_name = _first_text(item, "publicName", "displayName", "name")
    if not public_name:
        return None
    display_name = _first_text(item, "displayName", "publicName", "name") or public_name
    metadata: dict[str, Any] = {"arena_model_id": arena_id}
    for source_key, target_key in (
        ("organization", "organization"),
        ("provider", "provider"),
        ("name", "name"),
        ("userSelectable", "user_selectable"),
        ("rank", "rank"),
    ):
        value = item.get(source_key)
        if isinstance(value, (str, int, float, bool)):
            metadata[target_key] = value
    bounded_capabilities = _bounded_json(capabilities)
    metadata["arena_capabilities"] = bounded_capabilities
    metadata["capabilities"] = bounded_capabilities
    if isinstance(item.get("rankByModality"), dict):
        metadata["rank_by_modality"] = _bounded_json(item["rankByModality"])
    return {"id": public_name, "display_name": display_name, "metadata": metadata}


def _first_text(source: dict[str, Any], *fields: str) -> str:
    for field in fields:
        value = source.get(field)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return ""


def _bounded_json(value: Any, *, depth: int = 0) -> Any:
    if depth > 4:
        return "<depth-limited>"
    if isinstance(value, str):
        return value[:512]
    if isinstance(value, (int, float, bool)) or value is None:
        return value
    if isinstance(value, list):
        return [_bounded_json(item, depth=depth + 1) for item in value[:32]]
    if isinstance(value, dict):
        return {
            str(key)[:64]: _bounded_json(item, depth=depth + 1)
            for key, item in list(value.items())[:64]
        }
    return str(value)[:128]


def resolve_arena_model_id(
    records: list[dict[str, Any]],
    requested_model: str,
    *,
    explicit_model_id: str | None = None,
) -> str:
    explicit = str(explicit_model_id or "").strip()
    if explicit:
        if not _UUID.fullmatch(explicit):
            raise ValueError("Arena provider option model_id must be a UUID")
        return explicit
    requested = str(requested_model or "").strip()
    if _UUID.fullmatch(requested):
        return requested
    folded = requested.casefold()
    for record in records:
        if not isinstance(record, dict):
            continue
        candidates = [record.get("id"), record.get("display_name")]
        metadata = record.get("metadata")
        if isinstance(metadata, dict):
            candidates.append(metadata.get("name"))
        if any(
            isinstance(value, str) and value.strip().casefold() == folded for value in candidates
        ):
            value = metadata.get("arena_model_id") if isinstance(metadata, dict) else None
            if isinstance(value, str) and _UUID.fullmatch(value.strip()):
                return value.strip()
    raise ValueError(f"Arena model is not present in the current catalog: {requested}")


def _arena_model_search_capability(records: list[dict[str, Any]], model_id: str) -> bool | None:
    for record in records:
        if not isinstance(record, dict):
            continue
        metadata = record.get("metadata")
        if not isinstance(metadata, dict) or metadata.get("arena_model_id") != model_id:
            continue
        capabilities = metadata.get("arena_capabilities") or metadata.get("capabilities")
        if not isinstance(capabilities, dict):
            return None
        output = capabilities.get("outputCapabilities")
        if not isinstance(output, dict) or "search" not in output:
            return None
        return _capability_enabled(output.get("search"))
    return None


def _capability_enabled(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, dict):
        return value.get("enabled") is not False
    return False


def _arena_ndjson_stream_script(binding_name: str) -> str:
    binding = json.dumps(binding_name)
    return rf"""async request => {{
  const emit = event => window[{binding}]({{requestId: request.requestId, ...event}});
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), request.timeoutMs);
  try {{
    const response = await fetch(request.url, {{
      method: request.method,
      credentials: 'include',
      headers: request.headers,
      body: request.body === '' ? undefined : request.body,
      signal: controller.signal
    }});
    await emit({{type: 'status', status: response.status,
      contentType: response.headers.get('content-type') || ''}});
    if (!response.ok) {{
      await emit({{type: 'error', data: (await response.text()).slice(0, 16384)}});
      return;
    }}
    const reader = response.body?.getReader();
    if (!reader) throw new Error('Arena response has no stream body');
    const decoder = new TextDecoder();
    let pending = '';
    const consume = async text => {{
      pending += text;
      const lines = pending.split(/\r?\n/);
      pending = lines.pop() || '';
      for (const line of lines) {{
        const value = line.trim();
        if (!value) continue;
        const normalized = value.startsWith('data:') ? value.slice(5).trimStart() : value;
        if (normalized) await emit({{type: 'data', data: normalized}});
      }}
    }};
    while (true) {{
      const {{done, value}} = await reader.read();
      if (done) break;
      await consume(decoder.decode(value, {{stream: true}}));
    }}
    await consume(decoder.decode());
    if (pending.trim()) await consume('\n');
  }} finally {{
    clearTimeout(timeout);
  }}
}}"""


def _arena_upload_script() -> str:
    """Upload media with Arena's page-owned signed-upload implementation.

    The action IDs are part of the changing Next.js build, so the page locates the
    exported ``uploadFile`` function by its stable implementation markers instead of
    hard-coding a private server-action URL.
    """

    return r"""async input => {
  const locateUploader = () => {
    const cached = window.__any2apiArenaUploadFile;
    if (typeof cached === 'function') return cached;
    const chunkNames = Object.keys(window).filter(name => name.startsWith('webpackChunk'));
    let runtimeCount = 0;
    let markerFactoryCount = 0;
    for (const chunkName of chunkNames) {
      const chunks = window[chunkName];
      if (!Array.isArray(chunks)) continue;
      let runtime;
      chunks.push([['any2api-arena-upload-' + Date.now()], {}, require => { runtime = require; }]);
      if (!runtime?.m) continue;
      runtimeCount++;
      for (const [id, factory] of Object.entries(runtime.m)) {
        const source = String(factory);
        if (!source.includes('generateUploadUrl') || !source.includes('getSignedUrl')) continue;
        markerFactoryCount++;
        let exports;
        try { exports = runtime(id); } catch (_) { continue; }
        const candidates = [];
        if (exports && typeof exports === 'object') {
          for (const [name, value] of Object.entries(exports)) {
            if (name === 'uploadFile' && typeof value === 'function') return value;
            candidates.push(value);
          }
          if (exports.default && typeof exports.default === 'object') {
            for (const [name, value] of Object.entries(exports.default)) {
              if (name === 'uploadFile' && typeof value === 'function') return value;
              candidates.push(value);
            }
          }
        }
        const uploader = candidates.find(candidate => typeof candidate === 'function'
          && (/uploadFile|generateUploadUrl|getSignedUrl/.test(String(candidate))));
        if (uploader) {
          window.__any2apiArenaUploadFile = uploader;
          return uploader;
        }
      }
    }
    throw new Error('Arena official media uploader was not found'
      + ' chunks=' + chunkNames.length
      + ' runtimes=' + runtimeCount
      + ' marker_factories=' + markerFactoryCount);
  };
  if (!Array.isArray(input.files) || input.files.length === 0) return [];
  const uploader = locateUploader();
  const output = [];
  for (const item of input.files) {
    const source = String(item.dataUrl || '').trim();
    const match = source.match(/^data:([^;,\s]+);base64,([A-Za-z0-9+/=]+)$/i);
    if (!match) throw new Error('Arena upload input is not an inline base64 data URL');
    const binary = atob(match[2]);
    const bytes = new Uint8Array(binary.length);
    for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
    const file = new File([bytes], String(item.filename || 'attachment'), {
      type: String(item.mimeType || match[1]).toLowerCase()
    });
    const uploaded = await uploader(file);
    const url = String(uploaded?.url || '').trim();
    const mimeType = String(uploaded?.mimeType || file.type || '').toLowerCase();
    if (!url || !/^https:\/\//i.test(url) || !mimeType) {
      throw new Error('Arena official media uploader returned an invalid result');
    }
    output.push({
      name: String(item.filename || uploaded?.key || 'attachment'),
      contentType: mimeType,
      url
    });
  }
  return output;
}"""


def _arena_signup_script() -> str:
    return r"""async input => {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), input.timeoutMs);
  try {
    const response = await fetch(input.path, {
      method: 'POST',
      credentials: 'include',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(input.body),
      signal: controller.signal
    });
    return {
      ok: response.ok,
      status: response.status,
      body: (await response.text()).slice(0, 4096)
    };
  } finally {
    clearTimeout(timeout);
  }
}"""


def _arena_me_script() -> str:
    return r"""async input => {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), input.timeoutMs);
  try {
    const response = await fetch(input.path, {
      method: 'GET',
      credentials: 'include',
      headers: {'Accept': 'application/json'},
      signal: controller.signal
    });
    let data = {};
    try { data = await response.json(); } catch (_) {}
    const user = data?.user || data?.data?.user || data;
    return {
      ok: response.ok,
      status: response.status,
      id: String(user?.id || user?.userId || ''),
      email: String(user?.email || '')
    };
  } finally {
    clearTimeout(timeout);
  }
}"""


def register_with_magic_link(
    page: Any,
    context: Any,
    backend: str,
    mail: TempMailClient,
    mailbox: Mailbox,
    seen_ids: set[str],
    payload: dict[str, Any],
    trace: RegistrationTrace,
) -> BrowserResult:
    config = _arena_config(payload)
    trace.mark(RegistrationStage.BROWSER_LAUNCHED)
    page.goto(
        f"{config['base_url']}{config['page_path']}",
        wait_until="domcontentloaded",
        timeout=90_000,
    )
    trace.mark(RegistrationStage.FORM_READY)
    full_name = str(payload.get("arena_full_name") or config["full_name"]).strip()
    if not full_name:
        raise _arena_registration_failure(
            trace, "arena_registration_invalid", "full name is required", retryable=False
        )
    request_body: dict[str, Any] = {
        "email": mailbox.address,
        "fullName": full_name,
        "shouldLinkHistory": False,
        "marketingConsent": False,
    }
    country = str(payload.get("registered_country_code") or "").strip()
    if country:
        if not re.fullmatch(r"[A-Za-z]{2,3}", country):
            raise _arena_registration_failure(
                trace,
                "arena_registration_invalid",
                "registered country code is invalid",
                retryable=False,
            )
        request_body["registeredCountryCode"] = country.upper()
    result = page.evaluate(
        _arena_signup_script(),
        {
            "path": config["registration_path"],
            "body": request_body,
            "timeoutMs": 60_000,
        },
    )
    if not isinstance(result, dict):
        raise _arena_registration_failure(
            trace, "arena_registration_failed", "Arena signup returned an invalid response"
        )
    status = int(result.get("status") or 502)
    if status < 200 or status >= 300:
        failure_class = _arena_error_class(status, str(result.get("body") or ""))
        raise _arena_registration_failure(
            trace,
            f"arena_{failure_class}",
            f"Arena signup rejected with HTTP {status} ({failure_class})",
            error_type="ArenaSignupRejected",
            retryable=False,
        )
    trace.mark(RegistrationStage.FORM_SUBMITTED)
    trace.mark(RegistrationStage.UPSTREAM_ACCEPTED)
    try:
        verification_link = mail.wait_for_link_sync(
            mailbox,
            host_pattern=r"(?<![A-Za-z0-9.-])(?:www\.)?arena\.ai(?:/|$)",
            timeout=float(config["mail_timeout_seconds"]),
            seen_ids=seen_ids,
        )
    except TimeoutError as error:
        raise _arena_registration_failure(
            trace,
            "arena_mail_verification_timeout",
            "Arena verification email was not received before the timeout",
            error_type=type(error).__name__,
            retryable=False,
        ) from error
    trace.mark(RegistrationStage.OTP_RECEIVED)
    verification_link = _validate_arena_link(verification_link)
    page.goto(verification_link, wait_until="domcontentloaded", timeout=90_000)
    trace.mark(RegistrationStage.ACTIVATED)
    profile = page.evaluate(
        _arena_me_script(),
        {"path": config["me_path"], "timeoutMs": 60_000},
    )
    if not isinstance(profile, dict) or not profile.get("ok") or not str(profile.get("id") or ""):
        status = int(profile.get("status") or 502) if isinstance(profile, dict) else 502
        failure_class = _arena_error_class(status, "profile probe failed")
        raise _arena_registration_failure(
            trace,
            f"arena_{failure_class}",
            f"Arena profile verification failed with HTTP {status} ({failure_class})",
            error_type="ArenaProfileProbeFailed",
            retryable=False,
        )
    email = str(profile.get("email") or mailbox.address).strip().lower()
    if email and email.casefold() != mailbox.address.casefold():
        raise _arena_registration_failure(
            trace,
            "arena_identity_mismatch",
            "Arena profile email does not match the temporary mailbox",
            error_type="ArenaIdentityMismatch",
            retryable=False,
        )
    value = credential_from_context(context, page, "", mailbox.jwt)
    value.pop("password", None)
    value.update(
        {
            "email": mailbox.address,
            "arena_user_id": str(profile["id"]),
            "registration_backend": backend,
            "authentication": "email_magic_link",
        }
    )
    trace.mark(RegistrationStage.CREDENTIAL_CAPTURED)
    return BrowserResult(
        external_id=str(profile["id"]),
        email=mailbox.address,
        credential=value,
        metadata={
            **trace.metadata(),
            "authentication": "email_magic_link",
            "registration_protocol": "arena_nextjs_magic_link",
            "inference_probe_required": True,
        },
        ready_for_inference=False,
    )


class ArenaOfficialBrowserTransport(PageFetchBrowserRuntime):
    def __init__(
        self,
        base_url: str,
        *,
        page_path: str,
        me_path: str,
        chat_path: str,
    ) -> None:
        self._arena_page_path = page_path
        self._arena_me_path = me_path
        self._arena_chat_path = chat_path
        super().__init__(
            "arena",
            base_url,
            allowed_domain_suffixes=("arena.ai",),
            identity_fields=("arena_user_id", "user_id", "email", "external_id"),
            cookie_fields=(
                "arena-auth-prod-v1",
                "arena-auth-prod-v1.0",
                "arena-auth-prod-v1.1",
            ),
            require_cookie=False,
            page_url=f"{base_url}{page_path}",
        )

    def stream_request_script(self) -> str:
        return _arena_ndjson_stream_script(self._binding_name)

    async def models(
        self,
        credential: dict[str, Any],
        proxy_url: str,
        plan: RuntimePlan,
    ) -> dict[str, Any]:
        response = await self.request(
            credential,
            proxy_url,
            plan,
            method="GET",
            path=self._arena_page_path,
            endpoint_key="models",
            headers={"Accept": "text/html,application/xhtml+xml"},
            timeout_ms=plan.active.rules.canary_timeout_seconds * 1000,
        )
        status = int(response.get("status") or 502)
        if status < 200 or status >= 300:
            return response
        try:
            records = parse_arena_models(str(response.get("body") or ""))
        except (TypeError, ValueError):
            return {**response, "status": 502, "body": "Arena model catalog is unavailable"}
        return {
            **response,
            "body": json.dumps(
                {"models": records, "source": "arena_direct_page"},
                ensure_ascii=False,
                separators=(",", ":"),
            ),
        }

    async def account_status(
        self,
        credential: dict[str, Any],
        proxy_url: str,
        plan: RuntimePlan,
    ) -> dict[str, Any]:
        return await self.request(
            credential,
            proxy_url,
            plan,
            method="GET",
            path=self._arena_me_path,
            endpoint_key="me",
            headers={"Accept": "application/json"},
            timeout_ms=plan.active.rules.canary_timeout_seconds * 1000,
        )

    async def upload_attachments(
        self,
        credential: dict[str, Any],
        sources: list[dict[str, Any]],
        proxy_url: str,
        plan: RuntimePlan,
    ) -> dict[str, Any]:
        if not sources:
            return {"attachments": []}
        async with self.account_operation(credential):
            session, _, reports = await self._select_session(credential, proxy_url, plan)
            result = await session.page.evaluate(
                _arena_upload_script(),
                {
                    "files": [
                        {
                            "dataUrl": source["data_url"],
                            "filename": source["filename"],
                            "mimeType": source["mime_type"],
                        }
                        for source in sources
                    ]
                },
            )
            attachments = _normalize_arena_attachments(result)
            return {
                "attachments": attachments,
                "credential_patch": await self.credential_patch(session, credential),
                "runtime_reports": reports,
            }

    async def chat_stream(
        self,
        credential: dict[str, Any],
        command: dict[str, Any],
        proxy_url: str,
        plan: RuntimePlan,
    ) -> AsyncIterator[dict[str, Any]]:
        catalog = await self.models(credential, proxy_url, plan)
        catalog_status = int(catalog.get("status") or 502)
        if catalog_status < 200 or catalog_status >= 300:
            yield {"type": "status", "status": catalog_status}
            yield {"type": "error", "data": "Arena model catalog is unavailable"}
            return
        for report in catalog.get("runtime_reports", []):
            if isinstance(report, dict):
                yield {"type": "runtime_canary", **report}
        catalog_patch = catalog.get("credential_patch")
        if isinstance(catalog_patch, dict) and catalog_patch:
            yield {"type": "credential_patch", "data": catalog_patch}
        try:
            catalog_body = json.loads(str(catalog.get("body") or "{}"))
            records = catalog_body.get("models")
            if not isinstance(records, list):
                raise TypeError("Arena model catalog is invalid")
            options = command.get("providerOptions")
            explicit = options.get("model_id") if isinstance(options, dict) else None
            arena_model_id = resolve_arena_model_id(
                records, str(command.get("model") or ""), explicit_model_id=explicit
            )
            controls = command.get("controls")
            web_search = _arena_search_enabled(
                options if isinstance(options, dict) else {},
                controls if isinstance(controls, dict) else {},
            )
            if web_search:
                search_capability = _arena_model_search_capability(records, arena_model_id)
                if search_capability is False:
                    raise ValueError("Arena selected model does not expose web search")
            sources = arena_media_sources(command.get("messages"))
            uploaded = await self.upload_attachments(credential, sources, proxy_url, plan)
            for report in uploaded.get("runtime_reports", []):
                if isinstance(report, dict):
                    yield {"type": "runtime_canary", **report}
            upload_patch = uploaded.get("credential_patch")
            if isinstance(upload_patch, dict) and upload_patch:
                yield {"type": "credential_patch", "data": upload_patch}
            body = json.dumps(
                build_arena_request(
                    command,
                    model_id=arena_model_id,
                    attachments=uploaded.get("attachments", []),
                ),
                ensure_ascii=False,
                separators=(",", ":"),
            )
        except (TypeError, ValueError, json.JSONDecodeError):
            yield {"type": "status", "status": 422}
            yield {"type": "error", "data": "Arena model or semantic command is invalid"}
            return
        async for event in self.stream(
            credential,
            proxy_url,
            plan,
            method="POST",
            path=self._arena_chat_path,
            endpoint_key="chat",
            headers={"Accept": "*/*", "Content-Type": "text/plain;charset=UTF-8"},
            body=body,
            timeout_ms=plan.active.rules.canary_timeout_seconds * 1000,
        ):
            yield event


def _arena_config(payload: dict[str, Any]) -> dict[str, Any]:
    options = payload.get("runtime_options")
    configured = str(options.get("base_url") or "") if isinstance(options, dict) else ""
    base_url = _allowlisted_base_url(configured or None)
    from .arena_settings import settings

    config = settings()
    return {
        "base_url": base_url,
        "page_path": _same_origin_path(config.arena_page_path),
        "registration_path": _same_origin_path(config.arena_registration_path),
        "me_path": _same_origin_path(config.arena_me_path),
        "chat_path": _same_origin_path(config.arena_chat_path),
        "full_name": config.arena_full_name,
        "mail_timeout_seconds": config.arena_registration_mail_timeout_seconds,
    }


def _allowlisted_base_url(value: str | None = None) -> str:
    from .arena_settings import settings

    base_url = str(value or settings().arena_base_url).strip().rstrip("/")
    parsed = urlparse(base_url)
    host = (parsed.hostname or "").lower().rstrip(".")
    if (
        parsed.scheme != "https"
        or parsed.username
        or parsed.password
        or not (host == "arena.ai" or host.endswith(".arena.ai"))
        or parsed.path not in {"", "/"}
        or parsed.query
        or parsed.fragment
    ):
        raise ValueError("Arena runtime base URL is not allowlisted")
    return f"https://{host}"


def _same_origin_path(value: str) -> str:
    normalized = str(value or "").strip()
    if (
        not normalized.startswith("/")
        or "//" in normalized[1:]
        or "://" in normalized
        or "\\" in normalized
        or "/../" in f"{normalized}/"
    ):
        raise ValueError("Arena endpoint must be a same-origin path")
    return normalized


def _validate_arena_link(value: str) -> str:
    parsed = urlparse(str(value or "").strip())
    host = (parsed.hostname or "").lower().rstrip(".")
    if (
        parsed.scheme != "https"
        or host not in {"arena.ai", "www.arena.ai"}
        or parsed.username
        or parsed.password
        or not parsed.path.startswith("/")
    ):
        raise ValueError("Arena verification link host is invalid")
    return value.strip()


def _arena_error_class(status: int, body: str) -> str:
    text = str(body or "").lower()
    if any(marker in text for marker in ("captcha", "recaptcha", "challenge", "verify", "bot")):
        return "captcha_rejected"
    if status == 429 or any(marker in text for marker in ("rate limit", "too many")):
        return "rate_limited"
    if any(marker in text for marker in ("already exists", "registered", "duplicate")):
        return "identity_exists"
    if status in {401, 403}:
        return "credential_rejected"
    if status in {400, 422}:
        return "invalid_request"
    if status >= 500:
        return "upstream_unavailable"
    return "registration_rejected"


def _arena_registration_failure(
    trace: RegistrationTrace,
    code: str,
    message: str,
    *,
    error_type: str = "ArenaRegistrationError",
    retryable: bool = True,
) -> OperationFailure:
    return OperationFailure(
        code=code,
        stage=trace.current,
        message=message,
        error_type=error_type,
        retryable=retryable,
    )


def account_status_is_healthy(body: str) -> bool:
    try:
        value = json.loads(body)
    except (TypeError, json.JSONDecodeError):
        return False
    if not isinstance(value, dict):
        return False
    user = value.get("user")
    if not isinstance(user, dict):
        nested = value.get("data")
        user = nested.get("user") if isinstance(nested, dict) else None
    return isinstance(user, dict) and bool(str(user.get("id") or user.get("userId") or "").strip())
