from __future__ import annotations

import json
import time
from typing import Any
from uuid import uuid4

from .longcat_settings import settings
from .multimodal import decode_inline_data_url, iter_media_blocks, media_source, text_content
from .page_fetch_browser import PageFetchBrowserRuntime
from .runtime_rules import RuntimePlan

_MODEL_MODES = {
    "longcat-flash": ("1", False, False),
    "longcat-default": ("1", False, False),
    "longcat-thinking": ("1", True, False),
    "longcat-reason": ("1", True, False),
    "longcat-search": ("1", False, True),
    "longcat-reason-search": ("1", True, True),
    "longcat-pro": ("2", True, True),
}

_LONGCAT_MAX_UPLOAD_BYTES = 10 * 1024 * 1024
_LONGCAT_IMAGE_EXTENSIONS = {"jpg", "jpeg", "png"}
_LONGCAT_FILE_EXTENSIONS = {
    "pdf",
    "doc",
    "docx",
    "xls",
    "xlsx",
    "pptx",
    "txt",
    "jpg",
    "jpeg",
    "png",
}
_LONGCAT_MIME_EXTENSIONS = {
    "image/jpeg": "jpg",
    "image/png": "png",
    "application/pdf": "pdf",
    "application/msword": "doc",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document": "docx",
    "application/vnd.ms-excel": "xls",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": "xlsx",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation": "pptx",
    "text/plain": "txt",
}

_UPLOAD_MEDIA = r"""async input => {
  const decode = value => {
    const match = /^data:([^;]+);base64,(.+)$/s.exec(String(value || ''));
    if (!match) throw new Error('LongCat media must be an inline base64 data URL');
    const binary = atob(match[2]);
    const bytes = new Uint8Array(binary.length);
    for (let index = 0; index < binary.length; index++) {
      bytes[index] = binary.charCodeAt(index);
    }
    if (!bytes.length || bytes.length > input.maximumBytes) {
      throw new Error('LongCat media exceeds the configured upload limit');
    }
    return {mime: match[1], bytes};
  };
  const output = [];
  for (const source of input.files || []) {
    const decoded = decode(source.dataUrl);
    const form = new FormData();
    const fileName = String(source.fileName || source.filename || 'upload.bin');
    form.append('file', new File([decoded.bytes], fileName, {
      type: decoded.mime
    }));
    const headers = {...(input.headers || {})};
    delete headers['Content-Type'];
    delete headers['content-type'];
    headers.Accept = 'application/json';
    const token = localStorage.getItem('accessToken') ||
      localStorage.getItem('access-token') || localStorage.getItem('token') || '';
    if (token) headers['access-token'] = token;
    const response = await fetch(input.uploadPath, {
      method: 'POST',
      credentials: 'include',
      headers,
      body: form
    });
    const raw = await response.text();
    let envelope;
    try {
      envelope = JSON.parse(raw);
    } catch (_) {
      throw new Error('LongCat media upload returned invalid JSON');
    }
    const data = envelope?.data ?? envelope;
    if (!response.ok || envelope?.code != null && Number(envelope.code) !== 0 ||
        !data || typeof data.url !== 'string' || typeof data.key !== 'string') {
      throw new Error('LongCat media upload was rejected status=' + response.status);
    }
    output.push({...source,
      fileName,
      fileUrl: data.url,
      fileKey: data.key,
      uploadingStatus: 'success',
      progress: 100
    });
  }
  return output;
}"""


class LongcatOfficialBrowserTransport(PageFetchBrowserRuntime):
    """Runs LongCat session creation and SSE completion in the account page."""

    def __init__(self, base_url: str) -> None:
        super().__init__(
            "longcat",
            base_url,
            allowed_domain_suffixes=("longcat.chat",),
            identity_fields=(
                "email",
                "cookie",
                "passport_token_key",
                "passport_token",
            ),
            cookie_fields=("passport_token_key", "_lxsdk_cuid", "_lxsdk_s"),
            require_cookie=True,
            page_url=base_url.rstrip("/") + "/t",
        )

    async def session_request(
        self,
        credential: dict[str, Any],
        proxy_url: str,
        plan: RuntimePlan,
        agent_id: str,
        *,
        runtime_options: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        return await self.request(
            credential,
            proxy_url,
            plan,
            method="POST",
            endpoint_key="session",
            headers=_headers(runtime_options),
            body=json.dumps(
                {"model": "", "agentId": agent_id},
                ensure_ascii=True,
                separators=(",", ":"),
            ),
            timeout_ms=plan.active.rules.canary_timeout_seconds * 1000,
        )

    async def chat_stream(
        self,
        credential: dict[str, Any],
        command: dict[str, Any],
        proxy_url: str,
        plan: RuntimePlan,
        *,
        runtime_options: dict[str, Any] | None = None,
    ):
        upload_sources = _longcat_upload_sources(command.get("messages"))
        uploaded_files = await self.upload_media(
            credential,
            upload_sources,
            proxy_url,
            plan,
            runtime_options=runtime_options,
        )
        prepared = build_longcat_request(command, uploaded_files=uploaded_files)
        session = await self.session_request(
            credential,
            proxy_url,
            plan,
            prepared["agent_id"],
            runtime_options=runtime_options,
        )
        conversation_id = _conversation_id(session)
        options = runtime_options if isinstance(runtime_options, dict) else {}
        headers = _headers(options)
        body = {
            "content": prepared["content"],
            "conversationId": conversation_id,
            "agentId": prepared["agent_id"],
            "reasonEnabled": 1 if prepared["reason_enabled"] else 0,
            "searchEnabled": 1 if prepared["search_enabled"] else 0,
            "regenerate": 0,
            "parentMessageId": 0,
            "files": prepared["files"],
        }
        async for event in self.stream(
            credential,
            proxy_url,
            plan,
            method="POST",
            endpoint_key="chat",
            headers=headers,
            body=json.dumps(body, ensure_ascii=True, separators=(",", ":")),
            timeout_ms=300_000,
        ):
            yield event

    async def upload_media(
        self,
        credential: dict[str, Any],
        sources: list[dict[str, Any]],
        proxy_url: str,
        plan: RuntimePlan,
        *,
        runtime_options: dict[str, Any] | None = None,
    ) -> list[dict[str, Any]]:
        if not sources:
            return []
        async with self.account_operation(credential):
            session, selection, _reports = await self._select_session(credential, proxy_url, plan)
            result = await session.page.evaluate(
                _UPLOAD_MEDIA,
                {
                    "files": sources,
                    "uploadPath": selection.rules.endpoint_paths.get(
                        "upload", "/api/v1/appendix-upload"
                    ),
                    "headers": _headers(runtime_options),
                    "maximumBytes": _LONGCAT_MAX_UPLOAD_BYTES,
                },
            )
        if not isinstance(result, list):
            raise TypeError("LongCat media upload returned an invalid result")
        if len(result) != len(sources) or any(not isinstance(item, dict) for item in result):
            raise RuntimeError("LongCat media upload returned an incomplete result")
        self._logger.info(
            "official_browser_upload endpoint=%s files=%s bytes=%s completed=%s",
            selection.rules.endpoint_paths.get("upload", "/api/v1/appendix-upload"),
            len(result),
            sum(int(item.get("fileSize") or 0) for item in result),
            sum(bool(item.get("fileUrl") and item.get("fileKey")) for item in result),
        )
        return list(result)


def build_longcat_request(
    command: dict[str, Any], *, uploaded_files: list[dict[str, Any]] | None = None
) -> dict[str, Any]:
    _validate_command(command)
    media_blocks = _longcat_media_blocks(command["messages"])
    if uploaded_files is None:
        uploaded_files = command.get("uploadedFiles") or []
    if not isinstance(uploaded_files, list):
        raise TypeError("LongCat uploadedFiles must be an array")
    if len(uploaded_files) != len(media_blocks):
        if media_blocks:
            raise ValueError(
                "LongCat image/file input must be uploaded in the same browser session"
            )
        if uploaded_files:
            raise ValueError("LongCat uploadedFiles contains files without source content")
    files = _normalize_uploaded_files(uploaded_files)
    model = str(command["model"])
    default_agent, default_reason, default_search = _MODEL_MODES.get(model, ("1", False, False))
    options = command["providerOptions"]
    raw = command.get("rawRequest") if isinstance(command.get("rawRequest"), dict) else {}
    agent_id = _string(options.get("agent_id"), str(raw.get("agent_id") or default_agent))
    reason = _bool(
        options.get("reason_enabled"),
        raw.get("reason_enabled")
        if isinstance(raw.get("reason_enabled"), bool)
        else _reasoning(command, default_reason),
    )
    search = _bool(
        options.get("search_enabled"),
        raw.get("search_enabled")
        if isinstance(raw.get("search_enabled"), bool)
        else default_search,
    )
    tools = _normalize_tools(command.get("tools"))
    choice = raw.get("tool_choice")
    if choice == "none":
        tools = []
    content = _prompt(command.get("messages"), allow_media=bool(media_blocks))
    if tools:
        content = _append_tool_contract(content, tools, choice, raw.get("parallel_tool_calls"))
    return {
        "content": content,
        "agent_id": agent_id,
        "reason_enabled": reason,
        "search_enabled": search,
        "files": files,
    }


def _headers(runtime_options: dict[str, Any] | None) -> dict[str, str]:
    options = runtime_options if isinstance(runtime_options, dict) else {}
    config = settings()
    return {
        "Accept": "application/json, text/event-stream",
        "Content-Type": "application/json",
        "m-appkey": str(options.get("app_key") or config.longcat_app_key),
        "m-traceid": str(options.get("trace_id") or int(time.time() * 1000)),
        "x-client-language": str(options.get("language") or config.longcat_language),
        "x-requested-with": str(options.get("requested_with") or config.longcat_requested_with),
    }


def _conversation_id(result: dict[str, Any]) -> str:
    status = int(result.get("status") or 502)
    if status >= 400:
        raise RuntimeError(f"LongCat session-create returned HTTP {status}")
    try:
        body = json.loads(str(result.get("body") or ""))
    except json.JSONDecodeError as error:
        raise RuntimeError("LongCat session-create returned invalid JSON") from error
    code_value = body.get("code") if isinstance(body, dict) else None
    if not isinstance(body, dict) or code_value is None or int(code_value) != 0:
        if not isinstance(body, dict):
            raise RuntimeError("LongCat session-create was rejected: invalid response object")
        code = str(body.get("code") if body.get("code") is not None else "missing")
        message = str(body.get("message") or body.get("msg") or body.get("error") or "")
        message = " ".join(message.split())[:160]
        detail = f" code={code}"
        if message:
            detail += f" message={message}"
        raise RuntimeError(f"LongCat session-create was rejected{detail}")
    value = str((body.get("data") or {}).get("conversationId") or "").strip()
    if not value:
        raise RuntimeError("LongCat session-create returned no conversationId")
    return value


def _prompt(messages: Any, *, allow_media: bool = False) -> str:
    if not isinstance(messages, list):
        raise TypeError("LongCat messages must be an array")
    blocks: list[str] = []
    for message in messages:
        if not isinstance(message, dict):
            continue
        role = str(message.get("role") or "user").upper()
        content = _text(message.get("content"), allow_media=allow_media)
        if role == "ASSISTANT" and isinstance(message.get("tool_calls"), list):
            content += "\n" + json.dumps(message["tool_calls"], ensure_ascii=True)
        if content.strip():
            blocks.append(f"[{role}]\n{content}")
    if not blocks:
        raise ValueError("LongCat prompt is empty")
    return "\n\n".join(blocks)


def _append_tool_contract(
    prompt: str,
    tools: list[dict[str, Any]],
    choice: Any,
    parallel: Any,
) -> str:
    if choice == "none":
        return prompt
    label = "required" if choice in {"required", "any"} else "auto"
    if isinstance(choice, dict):
        function = choice.get("function") if isinstance(choice.get("function"), dict) else choice
        label = str(function.get("name") or "").strip() or "required"
    definitions = [
        {
            "name": tool["name"],
            "description": tool.get("description", ""),
            "parameters": tool.get("parameters") or {"type": "object", "properties": {}},
        }
        for tool in tools
    ]
    contract = (
        "[Tool calling contract]\n"
        f"Available tools: {json.dumps(definitions, ensure_ascii=True, separators=(',', ':'))}\n"
        f"Tool choice: {label}. Parallel calls allowed: {parallel is not False}.\n"
        "When a tool is needed, output only this JSON object and no prose:\n"
        '{"tool_calls":[{"name":"tool_name","arguments":{}}]}\n'
        "When no tool is needed, answer normally without a tool_calls object."
    )
    return f"{prompt.strip()}\n\n{contract}" if prompt.strip() else contract


def _normalize_tools(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    output: list[dict[str, Any]] = []
    names: set[str] = set()
    for raw in value:
        if not isinstance(raw, dict):
            continue
        definition = raw.get("function") if isinstance(raw.get("function"), dict) else raw
        name = str(definition.get("name") or "").strip()
        if not name or name in names:
            if name:
                raise ValueError(f"duplicate LongCat function tool: {name}")
            raise ValueError("LongCat function tool name is invalid")
        if definition.get("strict") is True:
            raise ValueError("LongCat emulated tools do not support strict=true")
        names.add(name)
        output.append(
            {
                "name": name,
                "description": str(definition.get("description") or ""),
                "parameters": definition.get("parameters") or {"type": "object", "properties": {}},
            }
        )
    return output


def _reasoning(command: dict[str, Any], fallback: bool) -> bool:
    value = (
        command["providerOptions"].get("reasoning_effort")
        or command["reasoning"].get("effort")
        or (command.get("rawRequest") or {}).get("reasoning_effort")
    )
    return fallback if value in {None, ""} else str(value).lower() not in {"none", "minimal"}


def _text(value: Any, *, allow_media: bool = False) -> str:
    if value is None:
        return ""
    return text_content(value, "LongCat", allow_media=allow_media)


def _string(value: Any, fallback: str) -> str:
    normalized = str(value or "").strip()
    return normalized or fallback


def _bool(value: Any, fallback: Any) -> bool:
    return value if isinstance(value, bool) else bool(fallback)


def _validate_command(command: dict[str, Any]) -> None:
    if not isinstance(command, dict) or command.get("schemaVersion") != 1:
        raise ValueError("LongCat semantic command schema is unsupported")
    if not str(command.get("model") or "").strip():
        raise ValueError("LongCat semantic command requires a model")
    if not isinstance(command.get("messages"), list):
        raise TypeError("LongCat semantic command messages must be an array")
    for field in ("reasoning", "providerOptions", "controls"):
        if not isinstance(command.get(field), dict):
            raise TypeError(f"LongCat semantic command {field} must be an object")


def _longcat_media_blocks(messages: Any) -> list[tuple[int, str, dict[str, Any]]]:
    media_blocks = list(iter_media_blocks(messages, "LongCat"))
    unsupported = sorted({kind for _, kind, _ in media_blocks if kind not in {"image", "file"}})
    if unsupported:
        raise ValueError(
            "LongCat chat upload does not support media types: " + ", ".join(unsupported)
        )
    if not media_blocks:
        return []
    last_user_index = max(
        (
            index
            for index, message in enumerate(messages)
            if isinstance(message, dict)
            and str(message.get("role") or "user").strip().lower() == "user"
        ),
        default=-1,
    )
    if last_user_index < 0 or any(index != last_user_index for index, _, _ in media_blocks):
        raise ValueError("LongCat media must be attached to the last user message")
    image_count = sum(kind == "image" for _, kind, _ in media_blocks)
    file_count = sum(kind == "file" for _, kind, _ in media_blocks)
    if image_count > 9:
        raise ValueError("LongCat chat accepts at most 9 images")
    if file_count > 1:
        raise ValueError("LongCat chat accepts at most 1 document")
    if image_count and file_count:
        raise ValueError("LongCat chat accepts images or one document, not both")
    return media_blocks


def _longcat_upload_sources(messages: Any) -> list[dict[str, Any]]:
    sources: list[dict[str, Any]] = []
    for _, kind, part in _longcat_media_blocks(messages):
        source = media_source(part)
        mime, content = decode_inline_data_url(
            source,
            f"LongCat {kind}",
            max_bytes=_LONGCAT_MAX_UPLOAD_BYTES,
            expected_prefix="image/" if kind == "image" else None,
        )
        filename, extension = _longcat_filename(part, mime, kind)
        sources.append(
            {
                "fileId": uuid4().hex,
                "fileName": filename,
                "fileExt": extension,
                "dataUrl": source,
                "fileSize": len(content),
                "width": 0,
                "height": 0,
            }
        )
    return sources


def _longcat_filename(part: dict[str, Any], mime: str, kind: str) -> tuple[str, str]:
    filename = str(part.get("filename") or "").strip()
    for key in ("file", "input_file", "attachment", "source"):
        nested = part.get(key)
        if not filename and isinstance(nested, dict):
            filename = str(nested.get("filename") or nested.get("name") or "").strip()
    filename = filename.replace("\\", "/").rsplit("/", 1)[-1]
    extension = filename.rsplit(".", 1)[-1].lower() if "." in filename else ""
    inferred = _LONGCAT_MIME_EXTENSIONS.get(mime.lower())
    allowed = _LONGCAT_IMAGE_EXTENSIONS if kind == "image" else _LONGCAT_FILE_EXTENSIONS
    if extension not in allowed:
        extension = inferred or ""
        if extension not in allowed:
            raise ValueError(f"LongCat {kind} upload format is not supported")
        filename = ""
    if not filename:
        filename = f"upload-{uuid4().hex}.{extension}"
    return filename, extension


def _normalize_uploaded_files(value: list[dict[str, Any]]) -> list[dict[str, Any]]:
    output: list[dict[str, Any]] = []
    for item in value:
        if not isinstance(item, dict):
            raise TypeError("LongCat uploaded file metadata must be an object")
        if not str(item.get("fileName") or "").strip():
            raise ValueError("LongCat uploaded file metadata requires fileName")
        if not str(item.get("fileUrl") or "").strip() or not str(item.get("fileKey") or "").strip():
            raise ValueError("LongCat uploaded file metadata requires fileUrl and fileKey")
        output.append(dict(item))
    return output
