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

from ..lifecycle.account import flow_max_attempts
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
_ARENA_TERMS_POLL_ATTEMPTS = 8
_ARENA_TERMS_POLL_INTERVAL_MS = 250
_ARENA_TOU_CONSENT_PATH = "/api/me/update-tou-consent"
_ARENA_TOU_PROFILE_TIMEOUT_MS = 60_000
_ARENA_REGISTRATION_RETRY_STATUSES = frozenset({408, 425, 429, 500, 502, 503, 504})
_ARENA_REGISTRATION_RETRY_LIMIT = 3
_ARENA_PROVISIONAL_ID_TIMEOUT_MS = 12_000
_ARENA_NAVIGATION_RETRY_LIMIT = 3


def _arena_terms_script() -> str:
    """Find and accept Arena's first-use Terms/Privacy dialog only."""

    return r"""() => {
  const visible = element => {
    const style = window.getComputedStyle(element);
    const rect = element.getBoundingClientRect();
    return style.visibility !== 'hidden'
      && style.display !== 'none'
      && Number(style.opacity || 1) > 0
      && rect.width > 0
      && rect.height > 0;
  };
  const normalized = value => String(value || '').replace(/\s+/g, ' ').trim().toLowerCase();
  const dialogs = Array.from(document.querySelectorAll(
    '[role="dialog"], [aria-modal="true"], dialog'
  )).filter(visible);
  const dialog = dialogs.find(element => {
    const text = normalized(element.textContent);
    return text.includes('terms of use') && text.includes('privacy policy');
  });
  if (!dialog) return {status: 'absent'};
  const agree = Array.from(dialog.querySelectorAll('button, [role="button"]')).find(element => {
    const label = normalized(element.getAttribute('aria-label') || element.textContent);
    return label === 'agree';
  });
  if (!agree) return {status: 'missing_button'};
  if (agree.disabled || agree.getAttribute('aria-disabled') === 'true') {
    return {status: 'button_disabled'};
  }
  agree.click();
  return {status: 'accepted'};
}"""


async def _accept_arena_terms_if_present(page: Any) -> str:
    for attempt in range(_ARENA_TERMS_POLL_ATTEMPTS):
        result = await page.evaluate(_arena_terms_script())
        status = result.get("status") if isinstance(result, dict) else None
        if status == "accepted":
            await page.wait_for_timeout(_ARENA_TERMS_POLL_INTERVAL_MS)
            return "accepted"
        if status in {"missing_button", "button_disabled"}:
            raise RuntimeError(f"Arena Terms dialog {status}")
        if status not in {"absent"}:
            raise RuntimeError("Arena Terms dialog returned an unsupported state")
        if attempt + 1 < _ARENA_TERMS_POLL_ATTEMPTS:
            await page.wait_for_timeout(_ARENA_TERMS_POLL_INTERVAL_MS)
    return "absent"


def _arena_update_tou_consent_script() -> str:
    """Call the same consent endpoint used by Arena's Agree button."""

    return r"""async input => {
  const response = await fetch(input.path, {
    method: 'POST',
    credentials: 'include',
    headers: {'Content-Type': 'application/json'}
  });
  return {ok: response.ok, status: response.status};
}"""


def _arena_tou_consented(profile: dict[str, Any]) -> bool:
    return bool(profile.get("touConsentFieldPresent") and profile.get("touConsentTimestampPresent"))


async def _arena_tou_profile(page: Any) -> dict[str, Any]:
    result = await page.evaluate(
        _arena_me_script(),
        {"path": "/api/me", "timeoutMs": _ARENA_TOU_PROFILE_TIMEOUT_MS},
    )
    return result if isinstance(result, dict) else {"status": 502}


async def _wait_for_arena_tou_consent(page: Any) -> bool:
    for attempt in range(_ARENA_TERMS_POLL_ATTEMPTS):
        profile = await _arena_tou_profile(page)
        if _arena_tou_consented(profile):
            return True
        if attempt + 1 < _ARENA_TERMS_POLL_ATTEMPTS:
            await page.wait_for_timeout(_ARENA_TERMS_POLL_INTERVAL_MS)
    return False


async def _ensure_arena_tou_consent(page: Any) -> str:
    profile = await _arena_tou_profile(page)
    status = int(profile.get("status") or 502)
    if status in {401, 403}:
        return "unauthenticated"
    if status < 200 or status >= 300 or not str(profile.get("id") or ""):
        return "profile_unavailable"
    if _arena_tou_consented(profile):
        return "already_consented"

    dialog_state = "absent"
    try:
        dialog_state = await _accept_arena_terms_if_present(page)
    except RuntimeError:
        dialog_state = "dialog_error"
    if dialog_state == "accepted" and await _wait_for_arena_tou_consent(page):
        return "accepted_via_dialog"

    if not profile.get("touConsentFieldPresent"):
        return f"legacy_{dialog_state}"

    consent = await page.evaluate(
        _arena_update_tou_consent_script(), {"path": _ARENA_TOU_CONSENT_PATH}
    )
    consent_status = int(consent.get("status") or 502) if isinstance(consent, dict) else 502
    if 200 <= consent_status < 300 and await _wait_for_arena_tou_consent(page):
        return "accepted_via_api"
    return f"consent_update_http_{consent_status}"


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
        # Arena's current direct UI uses DIRECT_BATTLE (the wire value is
        # direct-battle) for a new session. The upstream rejects mode=direct
        # until an existing conversation exists.
        "mode": "direct-battle",
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


def _arena_ndjson_stream_script(
    binding_name: str,
    recaptcha_site_key: str,
    recaptcha_v2_site_key: str,
    recaptcha_v2_timeout_ms: int,
) -> str:
    binding = json.dumps(binding_name)
    v3_site_key = json.dumps(recaptcha_site_key)
    v2_site_key = json.dumps(recaptcha_v2_site_key)
    return rf"""async request => {{
  const emit = event => window[{binding}]({{requestId: request.requestId, ...event}});
  const getRecaptchaV3Token = async () => {{
    const deadline = Date.now() + 10_000;
    let enterprise;
    while (Date.now() < deadline) {{
      enterprise = window.grecaptcha?.enterprise;
      if (typeof enterprise?.ready === 'function'
          && typeof enterprise?.execute === 'function') break;
      await new Promise(resolve => setTimeout(resolve, 100));
    }}
    if (typeof enterprise?.ready !== 'function' || typeof enterprise?.execute !== 'function') {{
      return {{token: '', available: false}};
    }}
    const execute = new Promise(resolve => enterprise.ready(async () => {{
      try {{
        const token = await enterprise.execute({v3_site_key}, {{action: 'chat_submit'}});
        resolve({{token: typeof token === 'string' ? token : '', available: true}});
      }} catch (_) {{
        resolve({{token: '', available: true}});
      }}
    }}));
    const timeout = new Promise(resolve =>
      setTimeout(() => resolve({{token: '', available: true}}), 10_000));
    return await Promise.race([execute, timeout]);
  }};
  const getRecaptchaV2Token = async triggerReason => {{
    const deadline = Date.now() + 10_000;
    let enterprise;
    while (Date.now() < deadline) {{
      enterprise = window.grecaptcha?.enterprise;
      if (typeof enterprise?.render === 'function') break;
      await new Promise(resolve => setTimeout(resolve, 100));
    }}
    if (typeof enterprise?.render !== 'function') {{
      await emit({{type: 'recaptcha', version: 'v2', state: 'unavailable', available: false,
        tokenLength: 0, triggerReason}});
      return '';
    }}
    const container = document.createElement('div');
    container.setAttribute('data-any2api-arena-recaptcha', 'v2');
    Object.assign(container.style, {{
      position: 'fixed', right: '16px', bottom: '16px', zIndex: '2147483647',
      padding: '8px', background: '#fff', borderRadius: '4px'
    }});
    const host = document.body || document.documentElement;
    if (!host) return '';
    host.appendChild(container);
    await emit({{type: 'recaptcha', version: 'v2', state: 'required', available: true,
      tokenLength: 0, triggerReason}});
    let token = '';
    try {{
      token = await new Promise(resolve => {{
        let settled = false;
        const finish = value => {{
          if (settled) return;
          settled = true;
          resolve(typeof value === 'string' ? value : '');
        }};
        const render = () => {{
          try {{
            enterprise.render(container, {{
              sitekey: {v2_site_key},
              callback: finish,
              'error-callback': () => finish(''),
              'expired-callback': () => finish(''),
              theme: 'light'
            }});
          }} catch (_) {{
            finish('');
          }}
        }};
        try {{
          if (typeof enterprise.ready === 'function') enterprise.ready(render);
          else render();
        }} catch (_) {{
          finish('');
        }}
        setTimeout(() => finish(''), {recaptcha_v2_timeout_ms});
      }});
    }} finally {{
      await emit({{type: 'recaptcha', version: 'v2',
        state: token ? 'solved' : 'timeout', available: true,
        tokenLength: String(token || '').length, triggerReason}});
      try {{ container.remove(); }} catch (_) {{}}
    }}
    return token;
  }};
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), request.timeoutMs);
  try {{
    let parsedBody;
    if (request.method === 'POST' && request.body !== '') {{
      try {{
        const candidate = JSON.parse(request.body);
        if (candidate && typeof candidate === 'object' && !Array.isArray(candidate)) {{
          parsedBody = candidate;
        }}
      }} catch (_) {{ /* keep the provider's original body */ }}
    }}
    const requestBody = async v2Token => {{
      if (!parsedBody) return request.body === '' ? undefined : request.body;
      if (v2Token) {{
        const retryBody = {{...parsedBody, recaptchaV2Token: v2Token}};
        delete retryBody.recaptchaV3Token;
        return JSON.stringify(retryBody);
      }}
      const recaptcha = await getRecaptchaV3Token();
      await emit({{type: 'recaptcha', available: recaptcha.available === true,
        tokenLength: String(recaptcha.token || '').length}});
      if (!recaptcha.token) return JSON.stringify(parsedBody);
      return JSON.stringify({{...parsedBody, recaptchaV3Token: recaptcha.token}});
    }};
    const send = async (attempt, v2Token = '') => {{
      const response = await fetch(request.url, {{
        method: request.method,
        credentials: 'include',
        headers: request.headers,
        body: await requestBody(v2Token),
        signal: controller.signal
      }});
      if (!response.ok) {{
        const errorBody = (await response.text()).slice(0, 16384);
        const triggerReason = response.status === 403
            && /recaptcha validation failed/i.test(errorBody)
          ? 'recaptcha_escalation'
          : response.status === 429 && /prompt failed/i.test(errorBody)
            ? 'prompt_rate_limit'
            : '';
        if (attempt === 0 && triggerReason) {{
          const token = await getRecaptchaV2Token(triggerReason);
          if (token) return send(1, token);
          await emit({{type: 'status', status: response.status,
            contentType: response.headers.get('content-type') || ''}});
          await emit({{type: 'error', data: JSON.stringify({{
            error: 'recaptcha v2 interactive verification required',
            code: 'recaptcha_v2_required', triggerReason
          }})}});
          return;
        }}
        await emit({{type: 'status', status: response.status,
          contentType: response.headers.get('content-type') || ''}});
        await emit({{type: 'error', data: errorBody}});
        return;
      }}
      await emit({{type: 'status', status: response.status,
        contentType: response.headers.get('content-type') || ''}});
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
    }};
    await send(0);
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
  if (typeof window.__any2apiArenaUploadFile !== 'function') {
    await window.__any2apiArenaLoadUploadChunk?.();
  }
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


def _arena_turbopack_uploader_init_script() -> str:
    """Capture Arena's exported uploader while Turbopack evaluates its module."""

    return r"""(() => {
  const wrapFactory = factory => {
    if (typeof factory !== 'function') return factory;
    const source = String(factory);
    if (!source.includes('generateUploadUrl') || !source.includes('getSignedUrl')) {
      return factory;
    }
    if (factory.__any2apiArenaWrapped) return factory;
    const wrapped = function(moduleContext, ...args) {
      if (moduleContext && typeof moduleContext.s === 'function') {
        const defineExports = moduleContext.s;
        moduleContext.s = function(definitions, ...defineArgs) {
          if (Array.isArray(definitions)) {
            for (let index = 0; index + 2 < definitions.length; index += 3) {
              if (definitions[index] === 'uploadFile'
                  && typeof definitions[index + 2] === 'function') {
                window.__any2apiArenaUploadFile = definitions[index + 2];
              }
            }
          }
          return defineExports.call(this, definitions, ...defineArgs);
        };
      }
      return factory.call(this, moduleContext, ...args);
    };
    wrapped.__any2apiArenaWrapped = true;
    return wrapped;
  };
  const captureChunk = chunk => {
    if (!Array.isArray(chunk) || chunk.length < 3) return chunk;
    for (let index = 1; index + 1 < chunk.length; index += 2) {
      chunk[index + 1] = wrapFactory(chunk[index + 1]);
    }
    return chunk;
  };
  const wrapRuntime = runtime => {
    if (!runtime || typeof runtime.push !== 'function' || runtime.push.__any2apiWrapped) {
      return;
    }
    const push = runtime.push;
    const wrappedPush = function(...chunks) {
      return push.apply(this, chunks.map(captureChunk));
    };
    wrappedPush.__any2apiWrapped = true;
    runtime.push = wrappedPush;
  };
  globalThis.__any2apiArenaLoadUploadChunk = async () => {
    if (typeof globalThis.__any2apiArenaUploadFile === 'function') return true;
    const state = globalThis.__any2apiArenaUploadDiscovery || {
      scanned: new Set(),
      loaded: new Set(),
      inFlight: null,
    };
    globalThis.__any2apiArenaUploadDiscovery = state;
    if (state.inFlight) return state.inFlight;
    const normalizeSource = value => {
      try {
        const url = new URL(String(value || ''), location.href);
        if (url.origin !== location.origin
            || !url.pathname.startsWith('/_next/static/chunks/')
            || !url.pathname.endsWith('.js')) return '';
        return url.href;
      } catch (_) {
        return '';
      }
    };
    const candidateSources = () => {
      const sources = new Set();
      for (const script of Array.from(document.scripts)) {
        const source = normalizeSource(script.src);
        if (source) sources.add(source);
      }
      for (const entry of performance.getEntriesByType('resource')) {
        const source = normalizeSource(entry.name);
        if (source) sources.add(source);
      }
      const html = document.documentElement?.outerHTML || '';
      const matches = html.matchAll(
        /(?:https?:\/\/[^"'\s<>]+)?\/_next\/static\/chunks\/[^"'\s<>]+/g,
      );
      for (const match of matches) {
        const source = normalizeSource(match[0]);
        if (source) sources.add(source);
      }
      return sources;
    };
    const load = async source => {
      if (state.scanned.has(source) || state.loaded.has(source)) return false;
      state.scanned.add(source);
      try {
        const response = await fetch(source, {credentials: 'include'});
        const body = await response.text();
        if (!response.ok || !body.includes('generateUploadUrl')
            || !body.includes('getSignedUrl')) return false;
        await new Promise((resolve, reject) => {
          const script = document.createElement('script');
          script.src = source;
          script.onload = resolve;
          script.onerror = reject;
          document.head.appendChild(script);
        });
        state.loaded.add(source);
        return typeof globalThis.__any2apiArenaUploadFile === 'function';
      } catch (_) {
        return false;
      }
    };
    state.inFlight = (async () => {
      try {
        for (let attempt = 0; attempt < 60; attempt++) {
          for (const source of candidateSources()) {
            if (await load(source)) return true;
          }
          if (typeof globalThis.__any2apiArenaUploadFile === 'function') return true;
          await new Promise(resolve => setTimeout(resolve, 250));
        }
        return false;
      } finally {
        state.inFlight = null;
      }
    })();
    return state.inFlight;
  };
  let current;
  try {
    current = globalThis.TURBOPACK;
    wrapRuntime(current);
    Object.defineProperty(globalThis, 'TURBOPACK', {
      configurable: true,
      get: () => current,
      set: value => {
        current = value;
        wrapRuntime(value);
      }
    });
  } catch (_) {
    wrapRuntime(globalThis.TURBOPACK);
  }
})();"""


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


def _arena_registration_attempts(payload: dict[str, Any]) -> int:
    return min(
        _ARENA_REGISTRATION_RETRY_LIMIT,
        flow_max_attempts(payload, _ARENA_REGISTRATION_RETRY_LIMIT),
    )


def _arena_transient_status(status: int) -> bool:
    return status in _ARENA_REGISTRATION_RETRY_STATUSES


def _arena_retry_delay_ms(attempt: int) -> int:
    return 500 * (2 ** min(max(0, attempt - 1), 2))


def _arena_verification_page_active(page: Any) -> bool:
    current_url = str(getattr(page, "url", "") or "")
    parsed = urlparse(current_url)
    host = (parsed.hostname or "").lower().rstrip(".")
    return (
        parsed.scheme == "https"
        and host in {"arena.ai", "www.arena.ai"}
        and parsed.path.startswith("/auth/verify")
        and "token=" in parsed.query
    )


def _arena_goto(page: Any, url: str, *, retries: int = _ARENA_NAVIGATION_RETRY_LIMIT) -> None:
    last_error: Exception | None = None
    for attempt in range(1, retries + 1):
        try:
            page.goto(url, wait_until="domcontentloaded", timeout=90_000)
            return
        except Exception as error:  # noqa: BLE001 - browser navigation is retried below
            last_error = error
            if _arena_verification_page_active(page):
                return
            if attempt < retries:
                page.wait_for_timeout(_arena_retry_delay_ms(attempt))
    if last_error is not None:
        raise last_error
    raise RuntimeError("Arena browser navigation attempts were exhausted")


def _arena_anonymous_signup_with_retry(
    page: Any,
    config: dict[str, Any],
    attempts: int,
) -> dict[str, Any] | None:
    result: dict[str, Any] | None = None
    for attempt in range(1, attempts + 1):
        if attempt > 1:
            _arena_goto(page, f"{config['base_url']}{config['page_path']}")
        try:
            candidate = page.evaluate(
                _arena_anonymous_signup_script(),
                {
                    "path": "/nextjs-api/sign-up",
                    "timeoutMs": 60_000,
                    "provisionalIdTimeoutMs": _ARENA_PROVISIONAL_ID_TIMEOUT_MS,
                },
            )
        except Exception:
            if attempt >= attempts:
                raise
            page.wait_for_timeout(_arena_retry_delay_ms(attempt))
            continue
        result = candidate if isinstance(candidate, dict) else None
        if result and result.get("ok") and str(result.get("userId") or ""):
            return result
        status = int(result.get("status") or 502) if result else 502
        code = str(result.get("code") or "") if result else ""
        if attempt >= attempts or (
            code != "PROVISIONAL_ID_MISSING" and not _arena_transient_status(status)
        ):
            return result
        page.wait_for_timeout(_arena_retry_delay_ms(attempt))
    return result


def _arena_signup_with_retry(
    page: Any,
    *,
    path: str,
    body: dict[str, Any],
    timeout_ms: int,
    attempts: int,
) -> dict[str, Any] | None:
    result: dict[str, Any] | None = None
    for attempt in range(1, attempts + 1):
        try:
            candidate = page.evaluate(
                _arena_signup_script(),
                {"path": path, "body": body, "timeoutMs": timeout_ms},
            )
        except Exception:
            if attempt >= attempts:
                raise
            page.wait_for_timeout(_arena_retry_delay_ms(attempt))
            continue
        result = candidate if isinstance(candidate, dict) else None
        status = int(result.get("status") or 502) if result else 502
        if result and 200 <= status < 300:
            return result
        if attempt >= attempts or not _arena_transient_status(status):
            return result
        page.wait_for_timeout(_arena_retry_delay_ms(attempt))
    return result


def _arena_set_password_with_retry(
    page: Any,
    *,
    path: str,
    password: str,
    timeout_ms: int,
    attempts: int,
) -> dict[str, Any] | None:
    result: dict[str, Any] | None = None
    for attempt in range(1, attempts + 1):
        try:
            candidate = page.evaluate(
                _arena_set_password_script(),
                {"path": path, "password": password, "timeoutMs": timeout_ms},
            )
        except Exception:
            if attempt >= attempts:
                raise
            page.wait_for_timeout(_arena_retry_delay_ms(attempt))
            continue
        result = candidate if isinstance(candidate, dict) else None
        status = int(result.get("status") or 502) if result else 502
        if result and result.get("ok") and result.get("success"):
            return result
        if attempt >= attempts or not _arena_transient_status(status):
            return result
        page.wait_for_timeout(_arena_retry_delay_ms(attempt))
    return result


def _arena_anonymous_signup_script() -> str:
    return r"""async input => {
  const uuidPattern = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;
  const keyedUuidPattern = /(?:provisionalUserId|userId|user_id)[^0-9a-f]{0,80}([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/i;
  const uuidFromValue = value => String(value || '').match(uuidPattern)?.[0] || '';
  const uuidFromKeyedValue = value => String(value || '').match(keyedUuidPattern)?.[1] || '';
  const storageValues = storage => {
    const values = [];
    try {
      for (const key of ['provisionalUserId', 'userId', 'user_id', 'anonymousUserId']) {
        const value = storage.getItem(key);
        if (value) values.push(value);
      }
      for (let index = 0; index < storage.length; index++) {
        const key = storage.key(index) || '';
        if (/user|anonymous|session/i.test(key)) values.push(storage.getItem(key) || '');
      }
    } catch (_) {}
    return values;
  };
  const findProvisionalUserId = () => {
    const sources = [document.cookie || '', document.documentElement?.innerHTML || ''];
    for (const script of Array.from(document.scripts || [])) {
      sources.push(script.textContent || '');
    }
    for (const source of sources) {
      const value = uuidFromKeyedValue(source);
      if (value) return value;
    }
    const selectors = [
      '[data-user-id]', '[data-userid]', '[name="userId"]', '[name="user_id"]',
      '[id="userId"]', '[id="user_id"]'
    ];
    for (const element of document.querySelectorAll(selectors.join(','))) {
      for (const value of [
        element.getAttribute('data-user-id'), element.getAttribute('data-userid'),
        element.getAttribute('value'), element.textContent
      ]) {
        const id = uuidFromValue(value);
        if (id) return id;
      }
    }
    try {
      for (const storage of [window.localStorage, window.sessionStorage]) {
        for (const value of storageValues(storage)) {
          const id = uuidFromKeyedValue(value) || uuidFromValue(value);
          if (id) return id;
        }
      }
    } catch (_) {}
    return '';
  };
  const deadline = Date.now() + Math.max(
    1_000, Math.min(30_000, Number(input.provisionalIdTimeoutMs || 12_000))
  );
  let provisionalUserId = '';
  while (!provisionalUserId && Date.now() < deadline) {
    provisionalUserId = findProvisionalUserId();
    if (!provisionalUserId) await new Promise(resolve => setTimeout(resolve, 250));
  }
  if (!provisionalUserId) {
    return {ok: false, status: 400, code: 'PROVISIONAL_ID_MISSING',
      diagnostic: {htmlHasUserId: /userId|user_id/i.test(document.documentElement?.innerHTML || ''),
        cookieHasUserId: /(?:^|;)\s*(?:userId|user_id)=/i.test(document.cookie || '')}};
  }
  const countryCookie = document.cookie.split(';')
    .map(value => value.trim())
    .find(value => value.startsWith('user_country_code=')) || '';
  let registeredCountryCode = '';
  try {
    registeredCountryCode = decodeURIComponent(countryCookie.split('=').slice(1).join('='))
      .trim().toUpperCase();
  } catch (_) {}
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), input.timeoutMs);
  try {
    const response = await fetch(input.path, {
      method: 'POST',
      credentials: 'include',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({recaptchaToken: '', provisionalUserId}),
      signal: controller.signal
    });
    let data = {};
    try { data = await response.json(); } catch (_) {}
    return {
      ok: response.ok,
      status: response.status,
      userId: String(data?.user?.id || ''),
      registeredCountryCode
    };
  } finally {
    clearTimeout(timeout);
  }
}"""


def _arena_set_password_script() -> str:
    return r"""async input => {
  const token = new URL(window.location.href).searchParams.get('token');
  if (!token) return {ok: false, status: 400, code: 'TOKEN_MISSING'};
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), input.timeoutMs);
  try {
    const response = await fetch(input.path, {
      method: 'POST',
      credentials: 'include',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({password: input.password, token}),
      signal: controller.signal
    });
    let data = {};
    try { data = await response.json(); } catch (_) {}
    return {
      ok: response.ok,
      status: response.status,
      success: data?.success === true,
      redirectTo: String(data?.redirectTo || ''),
      code: String(data?.code || ''),
      error: String(data?.error || '')
    };
  } finally {
    clearTimeout(timeout);
  }
}"""


def _arena_sign_in_script() -> str:
    return r"""async input => {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), input.timeoutMs);
  try {
    const response = await fetch(input.path, {
      method: 'POST',
      credentials: 'include',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({
        email: input.email,
        password: input.password,
        shouldLinkHistory: false
      }),
      signal: controller.signal
    });
    let data = {};
    try { data = await response.json(); } catch (_) {}
    return {
      ok: response.ok,
      status: response.status,
      success: data?.success === true,
      emailConfirmed: data?.user?.emailConfirmed === true,
      requiresVerification: data?.requiresVerification === true,
      code: String(data?.code || ''),
      error: String(data?.error || '')
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
      cache: 'no-store',
      headers: {'Accept': 'application/json'},
      signal: controller.signal
    });
    let data = {};
    try { data = await response.json(); } catch (_) {}
    const user = data?.user || data?.data?.user || data;
    const touConsentTimestamp = user?.touConsentTimestamp;
    return {
      ok: response.ok,
      status: response.status,
      id: String(user?.id || user?.userId || ''),
      email: String(user?.email || ''),
      touConsentFieldPresent: Object.prototype.hasOwnProperty.call(
        user || {}, 'touConsentTimestamp'
      ),
      touConsentTimestampPresent: touConsentTimestamp !== null
        && touConsentTimestamp !== undefined
        && String(touConsentTimestamp).trim() !== ''
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
    password: str,
    payload: dict[str, Any],
    trace: RegistrationTrace,
) -> BrowserResult:
    config = _arena_config(payload)
    attempts = _arena_registration_attempts(payload)
    trace.mark(RegistrationStage.BROWSER_LAUNCHED)
    _arena_goto(page, f"{config['base_url']}{config['page_path']}")
    trace.mark(RegistrationStage.FORM_READY)
    full_name = str(payload.get("arena_full_name") or config["full_name"]).strip()
    if not full_name:
        raise _arena_registration_failure(
            trace, "arena_registration_invalid", "full name is required", retryable=False
        )
    anonymous_result = _arena_anonymous_signup_with_retry(
        page,
        config,
        attempts,
    )
    if (
        not isinstance(anonymous_result, dict)
        or not anonymous_result.get("ok")
        or not str(anonymous_result.get("userId") or "")
    ):
        anonymous_status = (
            int(anonymous_result.get("status") or 502)
            if isinstance(anonymous_result, dict)
            else 502
        )
        anonymous_code = (
            str(anonymous_result.get("code") or "anonymous_signup_rejected")
            if isinstance(anonymous_result, dict)
            else "anonymous_signup_rejected"
        )
        raise _arena_registration_failure(
            trace,
            "arena_anonymous_signup_failed",
            f"Arena anonymous signup failed with HTTP {anonymous_status} ({anonymous_code})",
            error_type="ArenaAnonymousSignupFailed",
            retryable=_arena_transient_status(anonymous_status)
            or anonymous_code == "PROVISIONAL_ID_MISSING",
        )
    request_body: dict[str, Any] = {
        "email": mailbox.address,
        "fullName": full_name,
        "shouldLinkHistory": False,
        "marketingConsent": False,
    }
    country = str(
        payload.get("registered_country_code")
        or anonymous_result.get("registeredCountryCode")
        or ""
    ).strip()
    if country:
        if not re.fullmatch(r"[A-Za-z]{2,3}", country):
            raise _arena_registration_failure(
                trace,
                "arena_registration_invalid",
                "registered country code is invalid",
                retryable=False,
            )
        request_body["registeredCountryCode"] = country.upper()
    result = _arena_signup_with_retry(
        page,
        path=config["registration_path"],
        body=request_body,
        timeout_ms=60_000,
        attempts=attempts,
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
            retryable=_arena_transient_status(status),
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
    _arena_goto(page, verification_link)
    password_result = _arena_set_password_with_retry(
        page,
        path=config["password_path"],
        password=password,
        timeout_ms=60_000,
        attempts=attempts,
    )
    if (
        not isinstance(password_result, dict)
        or not password_result.get("ok")
        or not password_result.get("success")
    ):
        status = (
            int(password_result.get("status") or 502) if isinstance(password_result, dict) else 502
        )
        error_code = (
            str(password_result.get("code") or "password_setup_rejected")
            if isinstance(password_result, dict)
            else "password_setup_rejected"
        )
        raise _arena_registration_failure(
            trace,
            "arena_password_setup_failed",
            f"Arena password setup failed with HTTP {status} ({error_code})",
            error_type="ArenaPasswordSetupFailed",
            retryable=_arena_transient_status(status),
        )
    redirect_to = str(password_result.get("redirectTo") or "").strip()
    activation_redirect_path = ""
    if redirect_to:
        activation_redirect_path = urlparse(redirect_to).path or "/"
        _arena_goto(page, f"{config['base_url']}{_same_origin_path(redirect_to)}")
    else:
        _arena_goto(page, f"{config['base_url']}{config['page_path']}")
    trace.mark(RegistrationStage.ACTIVATED)
    profile: dict[str, Any] | None = None
    session_exchange: dict[str, Any] | None = None
    for attempt in range(3):
        page.wait_for_timeout(1_000 if attempt else 500)
        candidate = page.evaluate(
            _arena_me_script(),
            {"path": config["me_path"], "timeoutMs": 60_000},
        )
        if isinstance(candidate, dict):
            profile = candidate
            if candidate.get("ok") and str(candidate.get("id") or ""):
                break
    if isinstance(profile, dict) and int(profile.get("status") or 502) in {401, 403}:
        sign_in = page.evaluate(
            _arena_sign_in_script(),
            {
                "path": "/nextjs-api/sign-in/email",
                "email": mailbox.address,
                "password": password,
                "timeoutMs": 60_000,
            },
        )
        session_exchange = sign_in if isinstance(sign_in, dict) else None
        if (
            isinstance(sign_in, dict)
            and sign_in.get("ok")
            and sign_in.get("success")
            and sign_in.get("emailConfirmed")
        ):
            page.reload(
                wait_until="domcontentloaded",
                timeout=90_000,
            )
            profile = None
            for attempt in range(3):
                page.wait_for_timeout(1_000 if attempt else 500)
                candidate = page.evaluate(
                    _arena_me_script(),
                    {"path": config["me_path"], "timeoutMs": 60_000},
                )
                if isinstance(candidate, dict):
                    profile = candidate
                    if candidate.get("ok") and str(candidate.get("id") or ""):
                        break
    if not isinstance(profile, dict) or not profile.get("ok") or not str(profile.get("id") or ""):
        status = int(profile.get("status") or 502) if isinstance(profile, dict) else 502
        failure_class = _arena_error_class(status, "profile probe failed")
        page_path = str(getattr(page, "url", "") or "").split("?", 1)[0]
        cookie_descriptors = sorted(
            ":".join(
                (
                    str(item.get("name") or ""),
                    str(item.get("domain") or ""),
                    str(item.get("path") or ""),
                    "secure" if item.get("secure") else "plain",
                    "http" if item.get("httpOnly") else "script",
                )
            )
            for item in context.cookies()
            if isinstance(item, dict) and item.get("name")
        )
        raise _arena_registration_failure(
            trace,
            f"arena_{failure_class}",
            "Arena profile verification failed with "
            f"HTTP {status} ({failure_class}); page_path={page_path or '<unknown>'}; "
            f"redirect_path={activation_redirect_path or '<none>'}; "
            "session_exchange="
            f"status={int(session_exchange.get('status') or 0) if session_exchange else 0},"
            f"success={bool(session_exchange.get('success')) if session_exchange else False},"
            "email_confirmed="
            f"{bool(session_exchange.get('emailConfirmed')) if session_exchange else False},"
            "requires_verification="
            f"{bool(session_exchange.get('requiresVerification')) if session_exchange else False},"
            f"code={str(session_exchange.get('code') or '')[:80] if session_exchange else '<none>'},"
            f"error={str(session_exchange.get('error') or '')[:120] if session_exchange else '<none>'}; "
            f"cookie_descriptors={','.join(cookie_descriptors) or '<none>'}",
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
    value = credential_from_context(context, page, password, mailbox.jwt)
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
            "password_setup": True,
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
        config = arena_settings()
        return _arena_ndjson_stream_script(
            self._binding_name,
            config.arena_recaptcha_site_key,
            config.arena_recaptcha_v2_site_key,
            config.arena_recaptcha_v2_timeout_seconds * 1000,
        )

    async def configure_page(
        self,
        session: Any,
        credential: dict[str, Any],
    ) -> None:
        await super().configure_page(session, credential)
        await session.page.add_init_script(_arena_turbopack_uploader_init_script())

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

    async def wait_until_ready(self, page: Any, rule: Any) -> None:
        del rule
        try:
            terms_state = await _ensure_arena_tou_consent(page)
        except Exception as error:  # noqa: BLE001 - readiness must preserve auth errors
            self._logger.warning("arena_terms state=error error_type=%s", type(error).__name__)
            return
        self._logger.info("arena_terms state=%s", terms_state)

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
            try:
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
            except Exception as error:
                reason = re.sub(r"https?://\S+", "<url>", " ".join(str(error).split()))
                reason = reason[:200] or "<empty>"
                self._logger.warning(
                    "arena_media_upload_failed error_type=%s reason=%s",
                    type(error).__name__,
                    reason,
                )
                raise
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
        "password_path": _same_origin_path(config.arena_password_path),
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
