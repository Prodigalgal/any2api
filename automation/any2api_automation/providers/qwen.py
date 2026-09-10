import asyncio
import base64
import hashlib
import json
import random
import secrets
import time
import uuid
from typing import Any
from uuid import uuid4

from curl_cffi import requests as curl_requests

from ..lifecycle.account import credential, flow_max_attempts, prepare_registration, required
from ..lifecycle.browser import (
    BrowserContextProfile,
    BrowserFingerprintPolicy,
    BrowserFingerprintVariant,
    BrowserLaunchProfile,
    BrowserResult,
    credential_from_context,
    first_visible,
    run_browser_flow,
)
from ..lifecycle.registration import RegistrationStage, RegistrationTrace
from .base import AutomationProvider, AutomationProviderManifest, validate_semantic_command
from .multimodal import decode_inline_data_url, iter_media_blocks, media_source, text_content
from .qwen_challenge import QwenSignupChallenge, pace
from .qwen_fingerprint import (
    QwenFingerprintPlan,
    finalize_patchright_fingerprint,
    new_qwen_fingerprint,
    new_qwen_fingerprint_plan,
    normalize_qwen_fingerprint,
    patchright_cdp_commands,
    patchright_client_hints,
)
from .qwen_session import (
    browser_state_cookie_map,
    capture_browser_state,
    playwright_storage_state,
)
from .qwen_settings import settings
from .runtime_rules import RuntimePlan, parse_runtime_plan
from .transport_support import transport_frame, transport_proxy_lease


class QwenAutomationProvider(AutomationProvider):
    manifest = AutomationProviderManifest(
        id="qwen",
        browser_backend="camoufox",
        fallback_backend="patchright",
        isolation="process",
        challenge_types=("slider",),
        operations=("register", "reauthenticate", "keepalive"),
        realtime=True,
        inference_transport=True,
        inference_runtime="camoufox_browser_runtime",
        inference_actions=("model_discovery", "chat"),
    )

    async def register(self, payload: dict[str, Any]) -> dict[str, Any]:
        await asyncio.sleep(random.uniform(2.0, 4.0))
        mail, mailbox, password = await prepare_registration(payload)
        attempts = flow_max_attempts(payload, 1)
        last_error: RuntimeError | None = None
        for attempt in range(1, attempts + 1):
            trace = RegistrationTrace(self.manifest.id)
            try:
                trace.mark(RegistrationStage.MAILBOX_CREATED)
                await asyncio.sleep(random.uniform(2.0, 4.0))
                flow_payload = {**payload}
                affinity_key = str(flow_payload.get("proxy_affinity_key") or "").strip()
                if not affinity_key:
                    affinity_key = _qwen_proxy_affinity(mailbox.address, attempt)
                flow_payload["proxy_affinity_key"] = affinity_key
                flow_payload["strict_proxy_affinity"] = True
                flow_payload.setdefault(
                    "proxy_check_url", f"{settings().qwen_base_url.rstrip('/')}/auth?mode=register"
                )
                result = await asyncio.to_thread(
                    _register_with_fingerprint,
                    flow_payload,
                    mail,
                    mailbox,
                    password,
                    trace,
                )
                response = result.response()
                response.setdefault("metadata", {})["browser_attempt"] = attempt
                return response
            except Exception as error:  # noqa: BLE001 - same mailbox retry boundary
                last_error = trace.failure(error)
        assert last_error is not None
        raise last_error

    async def reauthenticate(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await asyncio.to_thread(
            _reauthenticate_sync,
            payload,
            credential(payload),
        )

    async def keepalive(self, payload: dict[str, Any]) -> dict[str, Any]:
        current = credential(payload)
        plan = parse_runtime_plan(payload.get("runtime_plan"), self.manifest.id)
        async with transport_proxy_lease(
            payload,
            check_url=settings().qwen_base_url,
        ) as proxy_url:
            result = await _qwen_models_request(current, proxy_url, plan, payload)
        status = int(result.get("status") or 502)
        body = _qwen_body_text(result)
        response: dict[str, Any] = {
            "healthy": 200 <= status < 300 and _qwen_model_catalog_available(body),
            "auth_expired": status in {401, 403},
            "ready_for_inference": False,
            "inference_probe_required": 200 <= status < 300,
        }
        if status not in {401, 403} and not response["healthy"]:
            response["error_class"] = "qwen_model_catalog_unavailable"
        patch = result.get("credential_patch")
        if isinstance(patch, dict) and patch:
            response["credential_patch"] = patch
        return response

    async def transport_request(self, payload: dict[str, Any]) -> dict[str, Any]:
        operation = str(payload.get("operation") or "")
        if operation not in {"models", "chat"}:
            raise ValueError("Qwen transport operation is not allowlisted")
        plan = parse_runtime_plan(payload.get("runtime_plan"), self.manifest.id)
        current = credential(payload)
        async with transport_proxy_lease(
            payload,
            check_url=settings().qwen_base_url,
        ) as proxy_url:
            if operation == "models":
                return await _qwen_models_request(current, proxy_url, plan, payload)
            command = payload.get("semantic_command")
            if not isinstance(command, dict):
                raise TypeError("Qwen semantic command must be an object")
            return await _qwen_chat_request(current, proxy_url, plan, command, payload)

    async def transport_stream(self, payload: dict[str, Any]):
        if str(payload.get("operation") or "") != "chat":
            raise ValueError("Qwen transport operation is not allowlisted")
        command = payload.get("semantic_command")
        if not isinstance(command, dict):
            raise TypeError("Qwen semantic command must be an object")
        plan = parse_runtime_plan(payload.get("runtime_plan"), self.manifest.id)
        current = credential(payload)
        async with transport_proxy_lease(
            payload,
            check_url=settings().qwen_base_url,
        ) as proxy_url:
            result = await _qwen_chat_request(current, proxy_url, plan, command, payload)
        status = int(result.get("status") or 502)
        yield transport_frame("status", status=status)
        if status < 400:
            body = _decode_qwen_body(result)
            for data in _qwen_sse_data(body):
                yield transport_frame("data", data=data)
        else:
            yield transport_frame("error", data=_qwen_body_excerpt(result))
        patch = result.get("credential_patch")
        if isinstance(patch, dict) and patch:
            yield transport_frame("credential_patch", data=patch)

    def routers(self) -> tuple[Any, ...]:
        from .qwen_risk import router

        return (router,)

    async def close(self) -> None:
        from .qwen_risk import native_transport

        await native_transport.close()

    def browser_context_profile(self) -> BrowserContextProfile:
        return BrowserContextProfile(
            ignore_https_errors=True,
            locale="zh-CN",
            timezone_id="Asia/Shanghai",
            viewport_width=1440,
            viewport_height=900,
            accept_language="zh-CN,zh;q=0.9",
            patchright_user_agent=settings().qwen_risk_user_agent,
        )

    def browser_launch_profile(self) -> BrowserLaunchProfile:
        return BrowserLaunchProfile(headless=False)

    def browser_fingerprint_policy(self) -> BrowserFingerprintPolicy:
        return BrowserFingerprintPolicy(
            variants=tuple(
                BrowserFingerprintVariant(
                    id=f"qwen-windows-{width}x{height}",
                    os="windows",
                    locale="zh-CN",
                    timezone_id="Asia/Shanghai",
                    viewport_width=width,
                    viewport_height=height,
                    accept_language="zh-CN,zh;q=0.9",
                )
                for width, height in ((1440, 900),)
            ),
            camoufox_mode="real",
        )


def _reauthenticate_sync(
    payload: dict[str, Any],
    current: dict[str, Any],
) -> dict[str, Any]:
    selected = current
    if _boolean(payload.get("rotate_fingerprint")):
        selected = _rotated_qwen_identity(current, "camoufox")
    try:
        result = _run_qwen_api_flow(payload, selected, "reauthenticate")
    except RuntimeError as error:
        recoverable_identity_errors = (
            "runtime identity drifted",
            "no browser backend available",
            "browser runtime is unavailable",
        )
        if not any(marker in str(error) for marker in recoverable_identity_errors):
            raise
        fingerprint = normalize_qwen_fingerprint(selected.get("browser_fingerprint"))
        fallback = "patchright" if fingerprint.get("backend") == "camoufox" else "camoufox"
        result = _run_qwen_api_flow(
            payload,
            _rotated_qwen_identity(current, fallback),
            "reauthenticate",
        )
    return result.metadata


async def _qwen_models_request(
    current: dict[str, Any],
    proxy_url: str,
    plan: RuntimePlan,
    payload: dict[str, Any],
) -> dict[str, Any]:
    return await _qwen_native_request(
        current,
        proxy_url,
        plan,
        payload,
        method="GET",
        endpoint_key="models",
        fallback_path="/api/v2/models/",
        referer_path="/",
        timeout_seconds=120,
    )


async def _qwen_chat_request(
    current: dict[str, Any],
    proxy_url: str,
    plan: RuntimePlan,
    command: dict[str, Any],
    payload: dict[str, Any],
) -> dict[str, Any]:
    session_path = _runtime_path(plan, "session", "/api/v2/chats/new")
    completion_path = _runtime_path(plan, "chat", "/api/v2/chat/completions")
    session_body = json.dumps(
        {
            "chatId": "",
            "project_id": "",
            "timestamp": int(time.time() * 1000),
            "chat_type": "t2t",
            "chat_mode": "normal",
            "models": [str(command.get("model") or "")],
        },
        ensure_ascii=False,
        separators=(",", ":"),
    )
    session = await _qwen_native_request(
        current,
        proxy_url,
        plan,
        payload,
        method="POST",
        path=session_path,
        body=session_body,
        referer_path="/c/new-chat",
        timeout_seconds=120,
    )
    session_status = int(session.get("status") or 502)
    if session_status < 200 or session_status >= 300:
        return session
    try:
        session_json = _qwen_json_body(session)
    except (UnicodeDecodeError, json.JSONDecodeError, RuntimeError):
        return {**session, "status": 502, "body": "Qwen chats/new returned invalid JSON"}
    chat_id = _qwen_chat_id(session_json)
    if not chat_id:
        return {**session, "status": 502, "body": "Qwen chats/new returned no chat id"}
    merged = {**current, **(session.get("credential_patch") or {})}
    media_sources = _qwen_media_sources(command.get("messages"))
    uploaded_files: list[dict[str, Any]] = []
    upload: dict[str, Any] = {}
    if media_sources:
        upload = await _qwen_media_upload(
            merged, proxy_url, plan, payload, media_sources, f"/c/{chat_id}"
        )
        upload_patch = upload.get("credential_patch")
        if isinstance(upload_patch, dict) and upload_patch:
            merged = {**merged, **upload_patch}
        uploaded = upload.get("files")
        if not isinstance(uploaded, list) or len(uploaded) != len(media_sources):
            return {
                **session,
                "status": 502,
                "body": "Qwen media upload returned an incomplete file list",
            }
        uploaded_files = [item for item in uploaded if isinstance(item, dict)]
    completion_body = json.dumps(
        build_qwen_request(command, chat_id, uploaded_files=uploaded_files),
        ensure_ascii=False,
        separators=(",", ":"),
    )
    completion = await _qwen_native_request(
        merged,
        proxy_url,
        plan,
        payload,
        method="POST",
        path=f"{completion_path}?chat_id={chat_id}",
        body=completion_body,
        referer_path=f"/c/{chat_id}",
        timeout_seconds=300,
    )
    patches = [
        session.get("credential_patch"),
        upload.get("credential_patch") if media_sources else None,
    ]
    patches.append(completion.get("credential_patch"))
    merged_patch: dict[str, Any] = {}
    for patch in patches:
        if isinstance(patch, dict) and patch:
            merged_patch.update(patch)
    if merged_patch:
        completion = {**completion, "credential_patch": merged_patch}
    return completion


async def _qwen_native_request(
    current: dict[str, Any],
    proxy_url: str,
    plan: RuntimePlan,
    payload: dict[str, Any],
    *,
    method: str,
    path: str = "",
    endpoint_key: str | None = None,
    fallback_path: str = "",
    body: str = "",
    referer_path: str = "/",
    timeout_seconds: int,
) -> dict[str, Any]:
    from .qwen_risk import NativeBrowserRequest, native_transport

    target_path = path or _runtime_path(plan, endpoint_key or "", fallback_path)
    token = _qwen_token(current)
    raw_account_id = str(payload.get("account_id") or "").strip()
    account_id = raw_account_id if _uuid(raw_account_id) else ""
    binding_id = hashlib.sha256(proxy_url.encode()).hexdigest()[:32] if proxy_url else ""
    request = NativeBrowserRequest(
        method=method,
        path=target_path,
        body=body,
        bearer_token=token,
        account_id=account_id,
        cookies=_qwen_cookie_map(current),
        browser_state=current.get("browser_state") or {},
        browser_fingerprint=current.get("browser_fingerprint") or {},
        referer_path=referer_path,
        timeout_seconds=max(1, min(300, timeout_seconds)),
        proxy_url=proxy_url,
        proxy_binding_id=binding_id,
    )
    return await native_transport.fetch(request)


async def _qwen_media_upload(
    current: dict[str, Any],
    proxy_url: str,
    plan: RuntimePlan,
    payload: dict[str, Any],
    sources: list[dict[str, Any]],
    referer_path: str,
) -> dict[str, Any]:
    from .qwen_risk import NativeBrowserRequest, native_transport

    token = _qwen_token(current)
    raw_account_id = str(payload.get("account_id") or "").strip()
    account_id = raw_account_id if _uuid(raw_account_id) else ""
    binding_id = hashlib.sha256(proxy_url.encode()).hexdigest()[:32] if proxy_url else ""
    request = NativeBrowserRequest(
        method="POST",
        path=_runtime_path(plan, "upload", "/api/v2/files/getstsToken"),
        bearer_token=token,
        account_id=account_id,
        cookies=_qwen_cookie_map(current),
        browser_state=current.get("browser_state") or {},
        browser_fingerprint=current.get("browser_fingerprint") or {},
        referer_path=referer_path,
        timeout_seconds=120,
        proxy_url=proxy_url,
        proxy_binding_id=binding_id,
    )
    return await native_transport.upload_media(
        request,
        sources,
        settings().qwen_max_upload_bytes,
        user_id=str(current.get("user_id") or current.get("userId") or ""),
    )


def build_qwen_request(
    command: dict[str, Any],
    chat_id: str,
    *,
    uploaded_files: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    _validate_qwen_command(command)
    media = list(iter_media_blocks(command["messages"], "Qwen"))
    unsupported_media = sorted({kind for _, kind, _ in media if kind != "image"})
    if unsupported_media:
        raise ValueError(
            "Qwen browser upload does not support media types: " + ", ".join(unsupported_media)
        )
    if any(
        str(command["messages"][index].get("role") or "user").lower() in {"system", "developer"}
        for index, _, _ in media
    ):
        raise ValueError("Qwen image input is supported only in user messages")
    messages = _qwen_messages(command)
    uploaded_files = uploaded_files if uploaded_files is not None else command.get("uploadedFiles")
    if uploaded_files is None:
        uploaded_files = []
    if not isinstance(uploaded_files, list):
        raise TypeError("Qwen uploadedFiles must be an array")
    expected_files = sum(
        1
        for message in messages
        for _, kind, _ in iter_media_blocks([message], "Qwen")
        if kind == "image"
    )
    if len(uploaded_files) != expected_files:
        if expected_files:
            raise ValueError(
                "Qwen image input must be uploaded in the same account browser session"
            )
        if uploaded_files:
            raise ValueError("Qwen uploadedFiles contains files without source content")
    timestamp = int(time.time())
    feature = _qwen_feature_config(command)
    upstream_messages: list[dict[str, Any]] = []
    message_ids = [str(uuid4()) for _ in messages]
    response_placeholder = str(uuid4())
    file_index = 0
    for index, source in enumerate(messages):
        role = _qwen_role(source.get("role"))
        content = _qwen_content(source.get("content"))
        if role == "assistant" and isinstance(source.get("tool_calls"), list):
            content += "\n" + json.dumps(
                source["tool_calls"], ensure_ascii=False, separators=(",", ":")
            )
        file_count = sum(1 for _, kind, _ in iter_media_blocks([source], "Qwen") if kind == "image")
        files = uploaded_files[file_index : file_index + file_count]
        if any(not isinstance(item, dict) for item in files):
            raise TypeError("Qwen uploadedFiles entries must be objects")
        file_index += file_count
        existing_files = source.get("files")
        if not isinstance(existing_files, list):
            existing_files = []
        merged_files = existing_files + files
        message: dict[str, Any] = {
            "id": None,
            "fid": message_ids[index],
            "role": role,
            "content": content,
            "user_action": "chat",
            "timestamp": timestamp,
            "model": str(command.get("model") or "") if role == "assistant" else "",
            "chat_type": "t2t",
            "sub_chat_type": "t2t",
            "feature_config": feature,
            "parentId": None if index == 0 else message_ids[index - 1],
            "parent_id": None if index == 0 else message_ids[index - 1],
            "childrenIds": [
                message_ids[index + 1] if index + 1 < len(messages) else response_placeholder
            ],
            "files": [json.loads(json.dumps(item, ensure_ascii=True)) for item in merged_files],
            "models": [str(command.get("model") or "")] if role == "user" else [],
            "extra": {"meta": {"subChatType": "t2t"}},
        }
        upstream_messages.append(message)
    result: dict[str, Any] = {
        "stream": True,
        "version": str((command.get("runtimeOptions") or {}).get("request_version") or "2.1"),
        "incremental_output": True,
        "model": str(command.get("model") or ""),
        "chatId": chat_id,
        "parentId": "",
        "chat_id": chat_id,
        "chat_mode": "normal",
        "parent_id": None,
        "timestamp": timestamp,
        "messages": upstream_messages,
    }
    generation = command.get("generation") or {}
    for source, target in (("temperature", "temperature"), ("top_p", "top_p")):
        if isinstance(generation.get(source), (int, float)):
            result[target] = generation[source]
    for source in ("max_completion_tokens", "max_output_tokens", "max_tokens"):
        if isinstance(generation.get(source), (int, float)):
            result["max_tokens"] = generation[source]
            break
    return result


def _validate_qwen_command(command: dict[str, Any]) -> None:
    validate_semantic_command(command, "Qwen")


def _qwen_messages(command: dict[str, Any]) -> list[dict[str, Any]]:
    source = [dict(item) for item in command["messages"] if isinstance(item, dict)]
    instructions = [
        _qwen_content(item.get("content"))
        for item in source
        if _qwen_role(item.get("role")) == "system" and _qwen_content(item.get("content"))
    ]
    messages = [item for item in source if _qwen_role(item.get("role")) != "system"]
    if not instructions:
        return messages
    prefix = "[System instructions]\n" + "\n\n".join(instructions)
    for index, message in enumerate(messages):
        if _qwen_role(message.get("role")) == "user":
            updated = {**message}
            content = message.get("content")
            if isinstance(content, list):
                updated["content"] = [{"type": "text", "text": prefix}, *content]
            else:
                text = _qwen_content(content)
                updated["content"] = prefix if not text else prefix + "\n\n" + text
            messages[index] = updated
            return messages
    return [{"role": "user", "content": prefix}, *messages]


def _qwen_feature_config(command: dict[str, Any]) -> dict[str, Any]:
    options = command.get("providerOptions") or {}
    controls = command.get("controls") or {}
    reasoning = command.get("reasoning") or {}
    effort = str(reasoning.get("effort") or controls.get("reasoning_effort") or "auto").lower()
    raw_mode = str(options.get("thinking_mode") or "").strip()
    mode = raw_mode or (
        "Fast" if effort in {"none", "minimal"} else "Auto" if effort == "auto" else "Thinking"
    )
    if mode.lower() in {"fast", "disabled", "off", "false", "none"}:
        mode = "Fast"
    elif mode.lower() == "auto":
        mode = "Auto"
    else:
        mode = "Thinking"
    explicit_search = options.get("web_search", controls.get("web_search"))
    if not isinstance(explicit_search, bool):
        explicit_search = any(
            isinstance(tool, dict)
            and str(tool.get("type") or "").lower()
            in {"web_search", "web_search_preview", "search"}
            for tool in command.get("tools") or []
        )
    result: dict[str, Any] = {
        "thinking_enabled": mode != "Fast",
        "output_schema": "phase",
        "research_mode": "normal",
        "auto_thinking": mode == "Auto",
        "thinking_mode": mode,
        "thinking_format": "summary",
        "auto_search": explicit_search,
    }
    budget = options.get("thinking_budget")
    if isinstance(budget, (int, float)) and budget > 0:
        result["thinking_budget"] = int(budget)
    return result


def _qwen_role(value: Any) -> str:
    role = str(value or "user").lower()
    return (
        "system"
        if role == "developer"
        else role
        if role in {"assistant", "system", "tool"}
        else "user"
    )


def _qwen_content(value: Any) -> str:
    if value is None:
        return ""
    return text_content(value, "Qwen", allow_media=True)


def _qwen_media_sources(messages: Any) -> list[dict[str, Any]]:
    sources: list[dict[str, Any]] = []
    for message_index, kind, part in iter_media_blocks(messages, "Qwen"):
        role = str(messages[message_index].get("role") or "user").lower()
        if role in {"system", "developer"}:
            raise ValueError("Qwen image input is supported only in user messages")
        if kind != "image":
            raise ValueError(f"Qwen browser upload does not support {kind} input")
        source = media_source(part)
        mime, content = decode_inline_data_url(
            source,
            "Qwen image",
            max_bytes=settings().qwen_max_upload_bytes,
            expected_prefix="image/",
        )
        filename = str(part.get("filename") or "").strip()
        filename = filename.replace("\\", "/").rsplit("/", 1)[-1][:255]
        if not filename:
            extension = {
                "jpeg": "jpg",
                "jpg": "jpg",
                "png": "png",
                "webp": "webp",
                "gif": "gif",
                "avif": "avif",
            }.get(mime.removeprefix("image/"), "bin")
            filename = f"upload-{uuid4().hex}.{extension}"
        sources.append(
            {
                "data_url": source,
                "filename": filename,
                "mime_type": mime,
                "size": len(content),
            }
        )
    return sources


def _runtime_path(plan: RuntimePlan, key: str, fallback: str) -> str:
    return str(plan.active.rules.endpoint_paths.get(key, fallback) or fallback)


def _qwen_token(current: dict[str, Any]) -> str:
    for name in ("token", "access_token", "jwt"):
        value = str(current.get(name) or "").strip()
        if value:
            return value
    raise ValueError("Qwen credential requires token")


def _qwen_cookie_map(current: dict[str, Any]) -> dict[str, str]:
    source = current.get("cookies")
    if not isinstance(source, dict):
        return {}
    return {
        str(key): str(value)
        for key, value in source.items()
        if str(key).strip() and str(value).strip()
    }


def _decode_qwen_body(result: dict[str, Any]) -> bytes:
    try:
        return base64.b64decode(str(result.get("body_base64") or ""), validate=True)
    except ValueError as error:
        raise RuntimeError("Qwen browser returned invalid response bytes") from error


def _qwen_json_body(result: dict[str, Any]) -> Any:
    return json.loads(_decode_qwen_body(result).decode("utf-8"))


def _qwen_sse_data(body: bytes) -> list[str]:
    text = body.decode("utf-8", errors="replace")
    output: list[str] = []
    for line in text.splitlines():
        if not line.lstrip().startswith("data:"):
            continue
        value = line.split(":", 1)[1].strip()
        if value:
            output.append(value)
    if not output and text.strip():
        output.append(text.strip())
    return output


def _qwen_body_excerpt(result: dict[str, Any]) -> str:
    return _qwen_body_text(result)[:16_384]


def _qwen_body_text(result: dict[str, Any]) -> str:
    try:
        return _decode_qwen_body(result).decode("utf-8", errors="replace")
    except RuntimeError:
        return str(result.get("body") or "Qwen browser request failed")


def _qwen_chat_id(value: Any) -> str:
    if not isinstance(value, dict):
        return ""
    for candidate in (
        value.get("id"),
        (value.get("data") or {}).get("id") if isinstance(value.get("data"), dict) else None,
        value.get("chat_id"),
    ):
        if str(candidate or "").strip():
            return str(candidate).strip()
    return ""


def _qwen_model_catalog_available(body: str) -> bool:
    try:
        value = json.loads(body)
    except json.JSONDecodeError:
        return False
    candidates = [
        value,
        value.get("models") if isinstance(value, dict) else None,
        value.get("data") if isinstance(value, dict) else None,
    ]
    return any(isinstance(candidate, (list, dict)) for candidate in candidates)


def _uuid(value: str) -> bool:
    try:
        uuid.UUID(value)
        return True
    except (ValueError, AttributeError):
        return False


def _rotated_qwen_identity(current: dict[str, Any], backend: str) -> dict[str, Any]:
    rotated = {**current, "browser_fingerprint": new_qwen_fingerprint(backend)}
    for field in (
        "browser_state",
        "cookies",
        "cookie",
        "user_agent",
        "browser_profile",
    ):
        rotated.pop(field, None)
    return rotated


def _boolean(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    return str(value or "").strip().lower() in {"1", "true", "yes", "on"}


def _run_qwen_api_flow(
    payload: dict[str, Any],
    current: dict[str, Any],
    operation: str,
) -> BrowserResult:
    fingerprint = normalize_qwen_fingerprint(current.get("browser_fingerprint"))
    if not fingerprint:
        fingerprint = new_qwen_fingerprint("camoufox")
    flow_payload = {**payload}
    flow_payload.setdefault("proxy_check_url", settings().qwen_base_url)
    return run_browser_flow(
        lambda page, context, backend, proxy_url: _qwen_api_browser(
            page, context, backend, proxy_url, current, operation, fingerprint
        ),
        preferred=str(fingerprint["backend"]),
        fallback=None,
        payload=flow_payload,
        context_profile=_context_profile_for_fingerprint(fingerprint, current),
        launch_profile=_launch_profile_for_fingerprint(fingerprint),
    )


def _qwen_api_browser(
    page,
    context,
    backend: str,
    proxy_url: str,
    current: dict[str, Any],
    operation: str,
    fingerprint: dict[str, Any],
) -> BrowserResult:
    config = settings()
    base_url = config.qwen_base_url.rstrip("/")
    _configure_qwen_patchright_context(context, page, backend, fingerprint)
    _restore_qwen_browser_state(context, current, base_url)
    page.goto(base_url, wait_until="domcontentloaded")
    _wait_qwen_risk_runtime(page)
    if operation == "reauthenticate":
        email = required(current, "email")
        password = required(current, "password")
        token = _signin_with_current_protocol(page, email, password, proxy_url, fingerprint)
        if not token:
            token = _signin_in_browser(page, email, password)
        if token:
            page.evaluate("token => localStorage.setItem('token', token)", token)
            session_patch = _qwen_session_patch(context, page, base_url, fingerprint)
            return BrowserResult(
                email,
                email,
                {},
                metadata={
                    "healthy": True,
                    "credential_patch": {"token": token, **session_patch},
                },
            )
        return BrowserResult(
            email,
            email,
            {},
            metadata={
                "healthy": False,
                "auth_expired": True,
                "terminal": False,
                "error_class": "QwenReauthenticationRequired",
            },
        )

    token = next(
        (
            str(current.get(key) or "").strip()
            for key in ("token", "access_token", "jwt")
            if str(current.get(key) or "").strip()
        ),
        "",
    )
    if not token:
        raise ValueError("credential requires token")
    page.evaluate("token => localStorage.setItem('token', token)", token)
    url = f"{base_url}/api/v2/models/"
    response = _send_qwen_protocol_request(
        page,
        url,
        "GET",
        "",
        token=token,
        proxy_url=proxy_url,
        fingerprint=fingerprint,
    )
    if _qwen_antibot_response(response):
        metadata = {
            "healthy": False,
            "auth_expired": False,
            "error_class": "QwenAntiBotChallenge",
        }
    elif response.status_code in {401, 403}:
        metadata = {"healthy": False, "auth_expired": True}
    else:
        response.raise_for_status()
        metadata = {"healthy": True, "auth_expired": False}
    metadata["credential_patch"] = _qwen_session_patch(context, page, base_url, fingerprint)
    return BrowserResult("qwen", str(current.get("email") or ""), {}, metadata=metadata)


def _register_with_fingerprint(
    payload: dict[str, Any], mail, mailbox, password: str, trace: RegistrationTrace
) -> BrowserResult:
    plan: QwenFingerprintPlan | None = None

    def resolve_profiles(
        proxy_url: str,
    ) -> tuple[BrowserContextProfile, BrowserLaunchProfile]:
        nonlocal plan
        plan = new_qwen_fingerprint_plan(camoufox_proxy_url=proxy_url)
        return (
            _context_profile_for_fingerprint(plan.patchright, {}),
            _launch_profile_for_fingerprint(plan.camoufox),
        )

    def register(page, context, backend: str, proxy_url: str) -> BrowserResult:
        if plan is None:
            raise RuntimeError("Qwen registration fingerprint plan was not initialized")
        return _register_browser(
            page,
            context,
            backend,
            proxy_url,
            mail,
            mailbox,
            password,
            trace,
            plan,
            str(payload.get("proxy_affinity_key") or ""),
        )

    return run_browser_flow(
        register,
        preferred="camoufox",
        fallback="patchright",
        payload=payload,
        launch_profile=BrowserLaunchProfile(
            headless=False,
            humanize=False,
            camoufox_os="windows",
            block_webrtc=True,
        ),
        profile_resolver=resolve_profiles,
    )


def _register_browser(
    page,
    context,
    backend,
    proxy_url,
    mail,
    mailbox,
    password,
    trace: RegistrationTrace,
    fingerprint_plan: QwenFingerprintPlan,
    proxy_affinity_key: str,
) -> BrowserResult:
    config = settings()
    fingerprint = fingerprint_plan.for_backend(backend)
    _configure_qwen_patchright_context(context, page, backend, fingerprint)
    trace.mark(RegistrationStage.BROWSER_LAUNCHED)
    challenge = QwenSignupChallenge()
    challenge.attach(page)
    pace(page, 3_000, 6_000)
    page.goto(f"{config.qwen_base_url.rstrip('/')}/auth?mode=register", wait_until="commit")
    try:
        page.wait_for_load_state("domcontentloaded", timeout=60_000)
    except Exception:  # noqa: BLE001,S110 - form readiness remains the authoritative gate
        pass
    pace(page, 4_000, 8_000)
    page.wait_for_selector("input", timeout=60_000)
    pace(page, 1_000, 2_000)
    register_tab = page.get_by_text("注册", exact=False).first
    if register_tab.count() and register_tab.is_visible():
        register_tab.click()
        pace(page, 1_000, 2_000)
    trace.mark(RegistrationStage.FORM_READY)
    _human_type_first(
        page,
        (
            'input[name="username"]',
            'input[name="name"]',
            'input[placeholder*="名称"]',
            'input[autocomplete="username"]',
        ),
        _random_display_name(),
    )
    _human_type_first(
        page,
        (
            'input[name="email"]',
            'input[type="email"]',
            'input[placeholder*="邮"]',
            'input[autocomplete="email"]',
        ),
        mailbox.address,
    )
    pace(page, 600, 1_200)
    passwords = page.locator('input[type="password"]')
    if passwords.count() < 1:
        raise RuntimeError("Qwen password field is unavailable")
    _human_type(page, passwords.first, password)
    confirmation = page.locator(
        'input[name="checkPassword"], input[name="confirmPassword"], input[placeholder*="再次"]'
    ).first
    if confirmation.count() and confirmation.is_visible():
        _human_type(page, confirmation, password)
    elif passwords.count() > 1:
        _human_type(page, passwords.nth(1), password)
    agreements = page.locator("input.ant-checkbox-input, input[type='checkbox']")
    if agreements.count() and not agreements.first.is_checked():
        try:
            agreements.first.check(force=True)
        except Exception:  # noqa: BLE001 - Ant Design can require a forced click
            agreements.first.click(force=True)
        pace(page, 800, 1_500)
        try:
            if not agreements.first.is_checked():
                page.get_by_text("我同意用户条款", exact=False).first.click()
        except Exception:  # noqa: BLE001,S110 - localized builds may omit this label
            pass
    pace(page, 2_000, 5_000)
    submit = first_visible(
        page,
        (
            'button[type="submit"]',
            'button:has-text("创建账号")',
            'button:has-text("注册")',
            'button:has-text("Sign up")',
        ),
    )
    if submit is None:
        raise RuntimeError("Qwen registration submit action is unavailable")
    try:
        if submit.is_disabled():
            agreements.first.check(force=True)
            page.wait_for_timeout(800)
    except Exception:  # noqa: BLE001,S110 - click will provide the final actionable failure
        pass
    trace.mark(RegistrationStage.FORM_SUBMITTED)
    challenge.submit_and_solve(page, submit)
    trace.mark(RegistrationStage.CHALLENGE_CLEARED)
    trace.mark(RegistrationStage.UPSTREAM_ACCEPTED)
    pace(page, 2_000, 4_000)
    activate_url = mail.wait_for_link_sync(mailbox, host_pattern=r"qwen\.ai")
    trace.mark(RegistrationStage.OTP_RECEIVED)
    pace(page, 2_000, 4_000)
    page.goto(activate_url, wait_until="domcontentloaded")
    page.wait_for_timeout(2500)
    _wait_qwen_risk_runtime(page)
    credential_value = credential_from_context(context, page, password, mailbox.jwt)
    credential_value.update({"email": mailbox.address, "registration_backend": backend})
    token = challenge.token or _signin_sync(page, mailbox.address, password, proxy_url, fingerprint)
    page.evaluate("token => localStorage.setItem('token', token)", token)
    credential_value["token"] = token
    credential_value.update(_qwen_session_patch(context, page, config.qwen_base_url, fingerprint))
    if proxy_url and proxy_affinity_key:
        credential_value["proxy_affinity_key"] = proxy_affinity_key
    if challenge.user_id:
        credential_value["user_id"] = challenge.user_id
    trace.mark(RegistrationStage.ACTIVATED)
    trace.mark(RegistrationStage.CREDENTIAL_CAPTURED)
    return BrowserResult(
        mailbox.address,
        mailbox.address,
        credential_value,
        metadata={**trace.metadata(), **challenge.diagnostics()},
    )


def _qwen_proxy_affinity(email: str, attempt: int) -> str:
    if attempt < 1:
        raise ValueError("Qwen proxy affinity attempt must be positive")
    normalized = email.strip().lower()
    if not normalized:
        raise ValueError("Qwen proxy affinity requires an email address")
    digest = hashlib.sha256(f"{normalized}\0{attempt}".encode()).hexdigest()[:32]
    return f"qwen-{digest}"


def _restore_qwen_browser_state(context, current: dict[str, Any], base_url: str) -> None:
    storage_state = playwright_storage_state(current.get("browser_state"), base_url)
    legacy_cookies = current.get("cookies")
    if not storage_state and isinstance(legacy_cookies, dict):
        cookies = [
            {"name": str(name), "value": str(value), "url": base_url}
            for name, value in legacy_cookies.items()
            if str(name).strip() and str(value).strip()
        ]
        if cookies:
            context.add_cookies(cookies)


def _context_profile_for_fingerprint(
    fingerprint: dict[str, Any], current: dict[str, Any]
) -> BrowserContextProfile:
    normalized = normalize_qwen_fingerprint(fingerprint)
    storage_state = playwright_storage_state(current.get("browser_state"), settings().qwen_base_url)
    if normalized["backend"] == "camoufox":
        return BrowserContextProfile(
            ignore_https_errors=True,
            storage_state=storage_state or None,
            camoufox_managed_fingerprint=True,
        )
    viewport = normalized["viewport"]
    screen = normalized["screen"]
    return BrowserContextProfile(
        ignore_https_errors=True,
        locale=str(normalized["locale"]),
        timezone_id=str(normalized["timezone_id"]),
        viewport_width=int(viewport["width"]),
        viewport_height=int(viewport["height"]),
        screen_width=int(screen["width"]),
        screen_height=int(screen["height"]),
        accept_language=str(normalized["accept_language"]),
        color_scheme=str(normalized["color_scheme"]),
        patchright_user_agent=str(normalized["user_agent"]),
        device_scale_factor=float(normalized["device_scale_factor"]),
        storage_state=storage_state or None,
        camoufox_managed_fingerprint=True,
    )


def _launch_profile_for_fingerprint(
    fingerprint: dict[str, Any],
) -> BrowserLaunchProfile:
    normalized = normalize_qwen_fingerprint(fingerprint)
    if normalized["backend"] != "camoufox":
        return BrowserLaunchProfile(headless=False)
    return BrowserLaunchProfile(
        headless=False,
        humanize=False,
        camoufox_os=str(normalized["os"]),
        block_webrtc=True,
        camoufox_config=normalized["camoufox_config"],
        camoufox_firefox_user_prefs=normalized["firefox_user_prefs"],
    )


def _configure_qwen_patchright_context(
    context, page, backend: str, fingerprint: dict[str, Any]
) -> None:
    if backend != "patchright":
        return
    cdp = context.new_cdp_session(page)
    for method, parameters in patchright_cdp_commands(fingerprint):
        cdp.send(method, parameters)
    context.set_extra_http_headers(
        {
            "Accept-Language": str(fingerprint["accept_language"]),
            **patchright_client_hints(fingerprint),
        }
    )


def _qwen_session_patch(
    context, page, base_url: str, fingerprint: dict[str, Any]
) -> dict[str, Any]:
    try:
        raw_state = context.storage_state(indexed_db=True)
    except TypeError:
        raw_state = context.storage_state()
    browser_state = capture_browser_state(raw_state, base_url)
    cookies = browser_state_cookie_map(browser_state, base_url)
    user_agent = str(page.evaluate("() => navigator.userAgent") or "")
    normalized_fingerprint = normalize_qwen_fingerprint(fingerprint)
    if normalized_fingerprint["backend"] == "patchright":
        normalized_fingerprint = finalize_patchright_fingerprint(
            normalized_fingerprint,
            page.evaluate(
                """() => {
                  const gl = document.createElement('canvas').getContext('webgl');
                  const debug = gl?.getExtension('WEBGL_debug_renderer_info');
                  return {
                    device_memory: navigator.deviceMemory,
                    webgl_vendor: debug ? gl.getParameter(debug.UNMASKED_VENDOR_WEBGL) : '',
                    webgl_renderer: debug ? gl.getParameter(debug.UNMASKED_RENDERER_WEBGL) : ''
                  };
                }"""
            ),
        )
    return {
        "browser_state": browser_state,
        "cookies": cookies,
        "cookie": "; ".join(f"{name}={value}" for name, value in cookies.items()),
        "user_agent": user_agent,
        "browser_profile": str(normalized_fingerprint["browser_profile"]),
        "browser_fingerprint": normalized_fingerprint,
    }


def _human_type_first(page, selectors: tuple[str, ...], value: str) -> None:
    locator = first_visible(page, selectors)
    if locator is None:
        raise RuntimeError("required registration field is unavailable")
    _human_type(page, locator, value)


def _human_type(page, locator, value: str) -> None:
    locator.click()
    pace(page, 300, 800)
    locator.fill("")
    for character in value:
        page.keyboard.type(character, delay=60 + secrets.randbelow(161))
    pace(page, 1_500, 3_500)


def _random_display_name() -> str:
    prefixes = ("alex", "mira", "kyle", "nova", "reed", "luna", "owen", "iris", "zane", "elio")
    return f"{random.choice(prefixes)}{random.randint(10, 99)}"


def _signin_sync(
    page,
    email: str,
    password: str,
    proxy_url: str = "",
    fingerprint: dict[str, Any] | None = None,
) -> str:
    token = _signin_with_current_protocol(page, email, password, proxy_url, fingerprint)
    if not token:
        token = _signin_in_browser(page, email, password)
    if token:
        return token
    raise RuntimeError("Qwen post-registration sign-in failed")


def _signin_in_browser(page, email: str, password: str) -> str | None:
    config = settings()
    challenge = QwenSignupChallenge()
    challenge.attach(page)
    page.goto(
        f"{config.qwen_base_url.rstrip('/')}/auth?mode=login",
        wait_until="domcontentloaded",
    )
    page.wait_for_selector("input", timeout=60_000)
    try:
        page.evaluate("() => localStorage.removeItem('token')")
    except Exception:  # noqa: BLE001,S110 - storage can be absent before login
        pass
    login_tab = page.get_by_text("登录", exact=False).first
    if login_tab.count() and login_tab.is_visible():
        login_tab.click()
        pace(page, 500, 1_000)
    _human_type_first(
        page,
        (
            'input[name="email"]',
            'input[type="email"]',
            'input[placeholder*="邮"]',
            'input[autocomplete="email"]',
            'input[autocomplete="username"]',
        ),
        email,
    )
    password_input = first_visible(
        page,
        ('input[type="password"]', 'input[autocomplete="current-password"]'),
    )
    if password_input is None:
        raise RuntimeError("Qwen sign-in password field is unavailable")
    _human_type(page, password_input, password)
    submit = first_visible(
        page,
        (
            'button[type="submit"]',
            'button:has-text("登录")',
            'button:has-text("Sign in")',
            'button:has-text("Log in")',
        ),
    )
    if submit is None:
        raise RuntimeError("Qwen sign-in submit action is unavailable")
    challenge.submit_and_solve(page, submit)
    if challenge.token:
        return challenge.token
    for _ in range(20):
        token = page.evaluate(
            "() => localStorage.getItem('token') || localStorage.getItem('access_token') || ''"
        )
        if token:
            return str(token)
        page.wait_for_timeout(250)
    return None


def _signin_with_current_protocol(
    page,
    email: str,
    password: str,
    proxy_url: str = "",
    fingerprint: dict[str, Any] | None = None,
) -> str | None:
    config = settings()
    base_url = config.qwen_base_url.rstrip("/")
    candidates = (hashlib.sha256(password.encode()).hexdigest(), password)
    for path in ("/api/v2/auths/signin", "/api/v1/auths/signin"):
        url = base_url + path
        for candidate in candidates:
            body = json.dumps(
                {"email": email, "password": candidate},
                ensure_ascii=True,
                separators=(",", ":"),
            )
            response = _send_qwen_protocol_request(
                page,
                url,
                "POST",
                body,
                proxy_url=proxy_url,
                fingerprint=fingerprint,
            )
            if 200 <= response.status_code < 300:
                data = response.json()
                token = data.get("token") or (data.get("data") or {}).get("token")
                if token:
                    return str(token)
            if response.status_code not in {400, 401, 403, 404, 405}:
                response.raise_for_status()
            if response.status_code in {404, 405}:
                break
    return None


def _send_qwen_protocol_request(
    page,
    url: str,
    method: str,
    body: str,
    *,
    token: str = "",
    proxy_url: str = "",
    fingerprint: dict[str, Any] | None = None,
):
    config = settings()
    base_url = config.qwen_base_url.rstrip("/")
    risk = _risk_headers_from_page(page, url, method, body)
    headers = {
        "Accept": "application/json",
        "Content-Type": "application/json",
        "Origin": base_url,
        "Referer": f"{base_url}/",
        "source": config.qwen_source,
        **risk,
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"
    normalized_fingerprint = normalize_qwen_fingerprint(fingerprint)
    browser_profile = (
        str(normalized_fingerprint["browser_profile"])
        if normalized_fingerprint
        else config.qwen_risk_browser_profile
    )
    with curl_requests.Session(
        impersonate=browser_profile,
        http_version="v2",
        default_headers=False,
    ) as client:
        return client.request(
            method,
            url,
            headers=headers,
            data=None if method == "GET" else body,
            proxy=proxy_url or None,
            timeout=60,
            allow_redirects=False,
        )


def _qwen_antibot_response(response) -> bool:
    content_type = str(response.headers.get("content-type", "")).lower()
    if response.status_code in {302, 403} and "text/html" in content_type:
        return True
    body = bytes(response.content[:8_192])
    return b"FAIL_SYS_USER_VALIDATE" in body or b"/punish?" in body


def _risk_headers_from_page(page, url: str, method: str, body: str) -> dict[str, str]:
    payload = json.dumps(
        {"url": url, "method": method, "body": body},
        ensure_ascii=True,
        separators=(",", ":"),
    )
    script = f"""(() => {{
      const request = {payload};
      const options = {{
        method: request.method,
        headers: {{'Content-Type': 'application/json'}}
      }};
      if (request.method !== 'GET' && request.method !== 'HEAD') options.body = request.body;
      fetch(request.url, options).catch(() => {{}});
      document.currentScript?.remove();
    }})();"""
    for _ in range(3):
        page.route(url, lambda route: route.abort(), times=1)
        try:
            with page.expect_request(
                lambda request: request.url == url and request.method.upper() == method,
                timeout=20_000,
            ) as request_info:
                page.add_script_tag(content=script)
            captured = {
                key.lower(): value for key, value in request_info.value.all_headers().items()
            }
        finally:
            page.unroute(url)
        allowed = (
            "bx-ua",
            "bx-umidtoken",
            "bx-v",
            "version",
            "user-agent",
            "sec-ch-ua",
            "sec-ch-ua-mobile",
            "sec-ch-ua-platform",
            "cookie",
        )
        result = {key: captured[key] for key in allowed if captured.get(key)}
        if result.get("bx-v"):
            if not result.get("version"):
                result["version"] = _qwen_frontend_version(page)
            return result
        page.wait_for_timeout(1500)
    raise RuntimeError("Qwen registration page did not attach current Baxia headers")


def _wait_qwen_risk_runtime(page) -> None:
    page.wait_for_function(
        r"""() => performance.getEntriesByType('resource').some(
          entry => /\/sd\/baxia\/[\d.]+\/baxiaCommon\.js/.test(entry.name))""",
        timeout=45_000,
    )
    page.wait_for_timeout(2_000)


def _qwen_frontend_version(page) -> str:
    value = page.evaluate(
        r"""() => {
          for (const entry of performance.getEntriesByType('resource')) {
            const match = entry.name.match(/qwen-chat-fe\/([^/]+)\/js\/main\.js/);
            if (match) return match[1];
          }
          return '';
        }"""
    )
    if not value:
        raise RuntimeError("Qwen frontend version could not be derived")
    return str(value)
