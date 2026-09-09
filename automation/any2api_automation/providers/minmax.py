from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import re
import time
import uuid
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import replace
from datetime import datetime
from typing import Any
from urllib.parse import parse_qsl, quote, urlencode, urljoin, urlparse

from ..config import settings as core_settings
from ..lifecycle.account import (
    credential,
    flow_max_attempts,
    mail_client,
    prepare_registration,
    required,
)
from ..lifecycle.browser import (
    BrowserResult,
    click_first,
    credential_from_context,
    enter_code,
    fill_first,
    first_visible,
    run_browser_flow,
)
from ..lifecycle.mail import Mailbox
from ..lifecycle.proxy import proxy_lease, proxy_parameters
from .base import (
    API_TRANSPORT,
    CAMOUFOX_BROWSER_RUNTIME,
    AutomationProvider,
    AutomationProviderManifest,
)
from .minmax_browser import MinmaxOfficialBrowserTransport
from .minmax_daily_checkin import MinmaxDailyCheckin
from .minmax_settings import settings
from .multimodal import decode_inline_data_url, iter_media_blocks, media_source, text_content
from .runtime_rules import RuntimePlan, parse_runtime_plan
from .transport_support import transport_frame


class MinmaxAutomationProvider(AutomationProvider):
    manifest = AutomationProviderManifest(
        id="minmax",
        browser_backend="camoufox",
        fallback_backend="patchright",
        isolation="process",
        challenge_types=("slider",),
        operations=("register", "reauthenticate", "keepalive", "daily_checkin"),
        inference_transport=True,
        inference_runtime=CAMOUFOX_BROWSER_RUNTIME,
        inference_modes=(API_TRANSPORT, CAMOUFOX_BROWSER_RUNTIME),
    )

    def action_bindings(self):
        from .minmax_api_actions import action_bindings

        return action_bindings(self)

    async def register(self, payload: dict[str, Any]) -> dict[str, Any]:
        config = settings()
        browser_payload = {
            **payload,
            "dynamic_proxy": payload.get("dynamic_proxy", config.minmax_require_dynamic_proxy),
            "proxy_check_url": config.minmax_base_url,
            "proxy_reject_redirect_hosts": ["minimaxi.com"],
        }
        if (
            browser_payload["dynamic_proxy"]
            and not browser_payload.get("proxy_pool")
            and not core_settings().dynamic_proxy_subscription_url
        ):
            raise RuntimeError("MinMax overseas registration requires the CF dynamic proxy")
        mail, mailbox, password = await prepare_registration(payload)
        attempts = flow_max_attempts(payload, 1)
        last_error: Exception | None = None
        for attempt in range(1, attempts + 1):
            try:
                attempt_payload = {**browser_payload}
                if not str(attempt_payload.get("proxy_affinity_key") or "").strip():
                    attempt_payload["proxy_affinity_key"] = _minmax_proxy_affinity(
                        mailbox.address, attempt
                    )
                if attempt_payload.get("dynamic_proxy") or attempt_payload.get("proxy_pool"):
                    attempt_payload["strict_proxy_affinity"] = True
                result = await asyncio.to_thread(
                    run_browser_flow,
                    lambda page, context, backend, proxy_url: _account_browser_flow(
                        page, context, backend, mail, mailbox, password
                    ),
                    preferred=self.manifest.browser_backend,
                    fallback=self.manifest.fallback_backend,
                    payload=attempt_payload,
                )
                result = replace(
                    result,
                    credential={
                        **result.credential,
                        "proxy_affinity_key": attempt_payload["proxy_affinity_key"],
                    },
                )
                return result.response()
            except Exception as error:  # noqa: BLE001 - same mailbox retry boundary
                last_error = error
        assert last_error is not None
        raise last_error

    async def reauthenticate(self, payload: dict[str, Any]) -> dict[str, Any]:
        config = settings()
        browser_payload = {
            **payload,
            "proxy_check_url": config.minmax_base_url,
            "proxy_reject_redirect_hosts": ["minimaxi.com"],
        }
        current = credential(payload)
        mail = mail_client(payload)
        mailbox = Mailbox(required(current, "email"), required(current, "mail_jwt"))
        result = await asyncio.to_thread(
            run_browser_flow,
            lambda page, context, backend, proxy_url: _account_browser_flow(
                page, context, backend, mail, mailbox, required(current, "password")
            ),
            preferred=self.manifest.browser_backend,
            fallback=self.manifest.fallback_backend,
            payload=browser_payload,
        )
        return {"healthy": True, "credential_patch": result.credential}

    async def keepalive(self, payload: dict[str, Any]) -> dict[str, Any]:
        current = credential(payload)
        plan = parse_runtime_plan(payload.get("runtime_plan"), self.manifest.id)
        async with _transport_proxy_lease(payload) as proxy_url:
            result = await official_browser_transport.request(
                current,
                "GET",
                _runtime_path(plan, "models", "/archon/api/v1/config"),
                "",
                proxy_url,
            )
        status = int(result.get("status") or 502)
        body = str(result.get("body") or "")
        healthy = 200 <= status < 300 and _minmax_catalog_available(body)
        response: dict[str, Any] = {
            "healthy": healthy,
            "auth_expired": status in {401, 403},
            "ready_for_inference": False,
            "inference_probe_required": 200 <= status < 300,
        }
        if status not in {401, 403} and not healthy:
            response["error_class"] = "minmax_model_catalog_unavailable"
        patch = result.get("credential_patch")
        if isinstance(patch, dict) and patch:
            response["credential_patch"] = patch
        return response

    async def daily_checkin(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await minmax_daily_checkin.execute(payload)

    async def transport_request(self, payload: dict[str, Any]) -> dict[str, Any]:
        current = credential(payload)
        operation = str(payload.get("operation") or "")
        if operation and operation not in {"models", "agents", "files_policy", "files_callback"}:
            raise ValueError("MinMax transport operation is not allowlisted")
        plan_value = payload.get("runtime_plan")
        plan = (
            parse_runtime_plan(plan_value, self.manifest.id)
            if isinstance(plan_value, dict)
            else None
        )
        if operation:
            method, path, body = _semantic_request_input(payload, plan)
        else:
            method, path, body = _transport_input(payload, stream=False)
        async with _transport_proxy_lease(payload) as proxy_url:
            return await official_browser_transport.request(current, method, path, body, proxy_url)

    async def transport_stream(self, payload: dict[str, Any]) -> AsyncIterator[bytes]:
        current = credential(payload)
        operation = str(payload.get("operation") or "")
        if operation and operation != "chat":
            raise ValueError("MinMax transport operation is not allowlisted")
        plan_value = payload.get("runtime_plan")
        plan = (
            parse_runtime_plan(plan_value, self.manifest.id)
            if isinstance(plan_value, dict)
            else None
        )
        async with _transport_proxy_lease(payload) as proxy_url:
            if operation:
                command = payload.get("semantic_command")
                if not isinstance(command, dict):
                    raise TypeError("MinMax semantic command must be an object")
                method, path, body = await _semantic_chat_input(
                    current, command, plan or _default_runtime_plan(), proxy_url
                )
            else:
                method, path, body = _transport_input(payload, stream=True)
            async for event in official_browser_transport.stream(
                current, method, path, body, proxy_url
            ):
                event_type = str(event.get("type") or "error")
                details = {key: value for key, value in event.items() if key != "type"}
                yield transport_frame(event_type, **details)

    async def close(self) -> None:
        await official_browser_transport.close()


def _runtime_path(plan: RuntimePlan, key: str, fallback: str) -> str:
    return str(plan.active.rules.endpoint_paths.get(key) or fallback)


def _default_runtime_plan() -> RuntimePlan:
    from .runtime_rules import RuntimeRule, RuntimeRuleSelection

    rule = RuntimeRule(
        schema_version=1,
        session_max_age_seconds=900,
        canary_timeout_seconds=60,
        build_asset_markers=("agent.minimax.io",),
        discovery_markers={"requestModule": ("x-signature", "hasSearchParamsPath")},
        capabilities={},
        endpoint_paths={
            "models": "/archon/api/v1/config",
            "agents": "/archon/api/v1/agent?limit=20",
            "filesPolicy": "/v1/api/files/request_policy",
            "filesCallback": "/v1/api/files/policy_callback",
            "signinStatus": "/minimax-cloud/api/v1/signin/status",
            "signinClaim": "/minimax-cloud/api/v1/signin/claim",
        },
    )
    return RuntimePlan(RuntimeRuleSelection("minmax", 1, rule), None, "", "")


def _semantic_request_input(
    payload: dict[str, Any], plan: RuntimePlan | None
) -> tuple[str, str, str]:
    selected = plan or _default_runtime_plan()
    operation = str(payload.get("operation") or "")
    paths = {
        "models": ("GET", _runtime_path(selected, "models", "/archon/api/v1/config"), ""),
        "agents": ("GET", _runtime_path(selected, "agents", "/archon/api/v1/agent?limit=20"), ""),
        "files_policy": (
            "GET",
            _runtime_path(selected, "filesPolicy", "/v1/api/files/request_policy"),
            "",
        ),
    }
    if operation in paths:
        return paths[operation]
    if operation == "files_callback":
        command = payload.get("semantic_command")
        if not isinstance(command, dict):
            raise TypeError("MinMax files callback command must be an object")
        body = command.get("body")
        if not isinstance(body, str):
            raise TypeError("MinMax files callback body must be a string")
        return (
            "POST",
            _runtime_path(selected, "filesCallback", "/v1/api/files/policy_callback"),
            body,
        )
    raise ValueError("MinMax transport operation is not allowlisted")


async def _semantic_chat_input(
    current: dict[str, Any],
    command: dict[str, Any],
    plan: RuntimePlan,
    proxy_url: str,
) -> tuple[str, str, str]:
    prepared = build_minmax_request(command)
    if prepared["attachments"]:
        prepared = {
            **prepared,
            "attachments": await official_browser_transport.upload_media(
                current,
                prepared["attachments"],
                plan,
                proxy_url,
                settings().minmax_max_upload_bytes,
            ),
        }
    agent_id = prepared["agent_id"]
    if not agent_id:
        agents = await official_browser_transport.request(
            current,
            "GET",
            _runtime_path(plan, "agents", "/archon/api/v1/agent?limit=20"),
            "",
            proxy_url,
        )
        agent_id = _select_agent(agents, prepared["agent_role"])
    session = await official_browser_transport.request(
        current,
        "POST",
        f"/archon/api/v1/agent/{agent_id}/session",
        json.dumps({"model": prepared["session_model"]}, separators=(",", ":")),
        proxy_url,
    )
    session_id = _session_id(session)
    return (
        "POST",
        f"/archon/api/v1/session/{session_id}/message",
        json.dumps(
            {
                "content": prepared["content"],
                "model": prepared["model"],
                "turn_id": str(uuid.uuid4()),
                "enable_team": prepared["enable_team"],
                "worktreeMode": prepared["worktree_mode"],
                "attachments": prepared["attachments"],
            },
            ensure_ascii=False,
            separators=(",", ":"),
        ),
    )


def build_minmax_request(command: dict[str, Any]) -> dict[str, Any]:
    _validate_semantic_command(command)
    options = command["providerOptions"]
    raw = command.get("rawRequest") if isinstance(command.get("rawRequest"), dict) else {}
    model_id = str(command["model"]).strip()
    variant = str(options.get("variant") or "").strip()
    if not variant:
        effort = str(command.get("reasoning", {}).get("effort") or "").lower()
        variant = "thinking" if effort not in {"", "none", "minimal"} else ""
    model = {"provider_id": "minimax", "model_id": model_id}
    if variant:
        model["variant"] = variant
    agent_id = str(options.get("agent_id") or raw.get("agent_id") or "").strip()
    role = str(options.get("agent_role") or "mavis").strip() or "mavis"
    return {
        "content": _minmax_prompt(command.get("messages")),
        "model": model,
        "session_model": "minimax/" + model_id,
        "agent_id": agent_id,
        "agent_role": role,
        "enable_team": _boolean_option(options.get("enable_team"), True),
        "worktree_mode": _boolean_option(options.get("worktree_mode"), False),
        "attachments": _minmax_attachments(command.get("messages")),
    }


def _validate_semantic_command(command: dict[str, Any]) -> None:
    if command.get("schemaVersion") != 1 or not str(command.get("model") or "").strip():
        raise ValueError("MinMax semantic command schema is unsupported")
    if not isinstance(command.get("messages"), list):
        raise TypeError("MinMax semantic command messages must be an array")
    for field in ("reasoning", "providerOptions", "controls"):
        if not isinstance(command.get(field), dict):
            raise TypeError(f"MinMax semantic command {field} must be an object")
    if command.get("tools"):
        raise ValueError("MinMax does not support tools")


def _minmax_prompt(messages: Any) -> str:
    if not isinstance(messages, list):
        raise TypeError("MinMax messages must be an array")
    media_blocks = list(iter_media_blocks(messages, "MinMax"))
    unsupported_media = sorted({kind for _, kind, _ in media_blocks if kind != "image"})
    if unsupported_media:
        raise ValueError(
            "MinMax browser upload does not support media types: " + ", ".join(unsupported_media)
        )
    blocks: list[str] = []
    for message in messages:
        if not isinstance(message, dict):
            continue
        role = str(message.get("role") or "user").upper()
        content = _message_text(message.get("content"))
        if role == "ASSISTANT" and isinstance(message.get("tool_calls"), list):
            content += "\n" + json.dumps(message["tool_calls"], ensure_ascii=False)
        if role == "TOOL":
            role = "TOOL " + str(message.get("tool_call_id") or "")
        if content.strip():
            blocks.append(f"[{role}]\n{content}")
    if not blocks:
        raise ValueError("MinMax prompt is empty")
    return "\n\n".join(blocks)


def _message_text(value: Any) -> str:
    return text_content(value, "MinMax", allow_media=True)


def _minmax_attachments(messages: Any) -> list[dict[str, Any]]:
    media_blocks = list(iter_media_blocks(messages, "MinMax"))
    attachments: list[dict[str, Any]] = []
    for _, kind, part in media_blocks:
        if kind != "image":
            raise ValueError(f"MinMax browser upload does not support {kind} input")
        source = media_source(part)
        mime, content = decode_inline_data_url(
            source,
            "MinMax image",
            max_bytes=settings().minmax_max_upload_bytes,
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
            filename = f"upload-{uuid.uuid4().hex}.{extension}"
        attachments.append(
            {
                "type": "image",
                "file_name": filename,
                "mime_type": mime,
                "file_md5": hashlib.md5(content).hexdigest(),
                "data_url": source,
            }
        )
    return attachments


def _boolean_option(value: Any, fallback: bool) -> bool:
    return value if isinstance(value, bool) else fallback


def _select_agent(response: dict[str, Any], role: str) -> str:
    try:
        body = json.loads(str(response.get("body") or ""))
    except json.JSONDecodeError as error:
        raise RuntimeError("MinMax agent list returned invalid JSON") from error
    for agent in body.get("agents", []) if isinstance(body, dict) else []:
        if isinstance(agent, dict) and str(agent.get("agent_role") or "").lower() == role.lower():
            value = str(agent.get("name") or "").strip()
            if value:
                return value
    raise RuntimeError(f"MinMax agent list has no role {role}")


def _session_id(response: dict[str, Any]) -> str:
    status = int(response.get("status") or 502)
    if status < 200 or status >= 300:
        raise RuntimeError(f"MinMax session creation returned HTTP {status}")
    try:
        body = json.loads(str(response.get("body") or ""))
    except json.JSONDecodeError as error:
        raise RuntimeError("MinMax session creation returned invalid JSON") from error
    value = str(body.get("session_id") or "") if isinstance(body, dict) else ""
    if not value:
        raise RuntimeError("MinMax session creation returned no session_id")
    return value


def _minmax_catalog_available(body: str) -> bool:
    try:
        value = json.loads(body)
    except json.JSONDecodeError:
        return False
    return isinstance(value, dict) and (
        isinstance(value.get("models"), list)
        or isinstance(value.get("data"), (dict, list))
        or isinstance(value.get("statusInfo"), dict)
    )


def _account_browser_flow(page, context, backend, mail, mailbox, password) -> BrowserResult:
    config = settings()
    account_events: list[str] = []
    session_values: dict[str, str] = {}
    profile_identity: dict[str, str] = {}
    profile_error: list[str] = []

    def capture_session(request) -> None:
        parsed = urlparse(request.url)
        if parsed.hostname != urlparse(config.minmax_base_url).hostname:
            return
        query = dict(parse_qsl(parsed.query, keep_blank_values=True))
        for name in ("token", "user_id", "device_id", "uuid", "op_ticket"):
            if query.get(name):
                session_values[name] = query[name]
        token_header = request.headers.get("token", "")
        if token_header:
            session_values["token"] = token_header

    def observe(response) -> None:
        parsed = urlparse(response.url)
        if parsed.hostname not in {
            urlparse(config.minmax_account_url).hostname,
            urlparse(config.minmax_base_url).hostname,
        }:
            return
        if not (parsed.path.startswith("/v1/") or parsed.path.startswith("/oauth2/")):
            return
        code = ""
        try:
            body = response.json()
            code = str((body.get("statusInfo") or {}).get("code", ""))
            if parsed.path == "/v1/api/user/info":
                _observe_profile_identity(body, mailbox.address, profile_identity, profile_error)
            elif parsed.path == "/v1/api/user/renewal":
                _extract_session_values(body, session_values)
        except Exception:  # noqa: BLE001,S110 - diagnostics never require a response body
            pass
        account_events.append(f"{parsed.path}:{response.status}:{code}")

    page.on("response", observe)
    page.on("request", capture_session)
    state = base64.b64encode(
        json.dumps(
            {
                "redirect_uri": config.minmax_base_url + "/",
                "csrf": str(uuid.uuid4()),
            },
            separators=(",", ":"),
        ).encode()
    ).decode()
    login_redirect = "/oauth2/authorize?" + urlencode(
        {
            "client_id": config.minmax_client_id,
            "redirect_uri": config.minmax_base_url + "/auth/callback",
            "response_type": "code",
            "source": "agent_web",
            "state": state,
        }
    )
    redirect = quote(login_redirect, safe="")
    page.goto(
        f"{config.minmax_account_url}/unified-login?login_redirect={redirect}",
        wait_until="domcontentloaded",
    )
    page.locator('input[type="email"], input[placeholder*="email" i]').first.wait_for(
        state="visible", timeout=45_000
    )
    fill_first(page, ('input[type="email"]', 'input[placeholder*="email" i]'), mailbox.address)
    _agree_if_needed(page)
    click_first(page, ('button:has-text("Continue")', 'button[type="submit"]'))
    password_field = first_visible(page, ('input[type="password"]',), timeout_ms=20_000)
    if password_field is not None:
        password_field.fill(password)
        click_first(page, ('button:has-text("Continue")', 'button[type="submit"]'))
    otp_field = first_visible(
        page,
        (
            'input[autocomplete="one-time-code"]',
            'input[maxlength="1"]',
            'input[inputmode="numeric"]',
        ),
        timeout_ms=20_000,
    )
    if otp_field is not None:
        code = mail.wait_for_code_sync(mailbox)
        enter_code(page, code)
        click_first(page, ('button:has-text("Continue")', 'button[type="submit"]'))
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline and not page.url.startswith(config.minmax_base_url):
        page.wait_for_timeout(500)
    if not page.url.startswith(config.minmax_base_url):
        parsed = urlparse(page.url)
        raise RuntimeError(
            f"MinMax overseas OAuth stopped at {parsed.hostname or 'unknown'}{parsed.path}; "
            f"events={','.join(account_events[-6:])}"
        )
    session_deadline = time.monotonic() + 45
    while time.monotonic() < session_deadline and not (
        session_values.get("token")
        and session_values.get("user_id")
        and profile_identity.get("external_id")
    ):
        page.wait_for_timeout(500)
    if profile_error and not profile_identity.get("external_id"):
        raise RuntimeError(profile_error[0])
    if not profile_identity.get("external_id"):
        raise RuntimeError("MinMax overseas login did not expose a verified account profile")
    value = credential_from_context(context, page, password, mailbox.jwt)
    value.update({"email": mailbox.address, "registration_backend": backend})
    value.update(session_values)
    value.update(
        {
            "account_user_id": profile_identity["account_user_id"],
            "real_user_id": profile_identity.get("real_user_id", ""),
            "profile_email_verified": True,
        }
    )
    _normalize_minmax_storage(value)
    profile = _profile_from_browser(context, page)
    if profile:
        value["request_profile"] = profile
    if not value.get("token") or not value.get("user_id"):
        raise RuntimeError("MinMax overseas login completed without token and user_id")
    return BrowserResult(profile_identity["external_id"], mailbox.address, value)


def _observe_profile_identity(
    body: Any,
    expected_email: str,
    profile_identity: dict[str, str],
    profile_error: list[str],
) -> None:
    try:
        profile_identity.update(_verified_profile_identity(body, expected_email))
        profile_error.clear()
    except (RuntimeError, TypeError) as error:
        profile_error[:] = [str(error)]


def _verified_profile_identity(value: Any, expected_email: str) -> dict[str, str]:
    if not isinstance(value, dict):
        raise TypeError("MinMax account profile response is invalid")
    status = value.get("statusInfo")
    if isinstance(status, dict) and status.get("code") not in {None, 0, "0"}:
        raise RuntimeError("MinMax account profile request was rejected")
    data = value.get("data")
    user_info = data.get("userInfo") if isinstance(data, dict) else None
    if not isinstance(user_info, dict):
        raise TypeError("MinMax account profile response is incomplete")
    actual_email = str(user_info.get("email") or "").strip()
    if not actual_email or actual_email.casefold() != expected_email.strip().casefold():
        raise RuntimeError(
            "MinMax account profile email does not match the registration mailbox "
            f"(expected={_email_shape(expected_email)}, actual={_email_shape(actual_email)})"
        )
    account_user_id = str(
        user_info.get("userID") or user_info.get("userId") or user_info.get("user_id") or ""
    ).strip()
    real_user_id = str(
        user_info.get("realUserID")
        or user_info.get("realUserId")
        or user_info.get("real_user_id")
        or ""
    ).strip()
    external_id = real_user_id or account_user_id
    if not external_id or not account_user_id:
        raise RuntimeError("MinMax account profile did not expose a stable identity")
    return {
        "external_id": external_id,
        "account_user_id": account_user_id,
        "real_user_id": real_user_id,
    }


def _email_shape(value: str) -> str:
    normalized = value.strip().casefold()
    local, separator, domain = normalized.partition("@")
    return (
        f"present={bool(normalized)} length={len(normalized)} "
        f"local_length={len(local)} domain_length={len(domain) if separator else 0} "
        f"local_hash={hashlib.sha256(local.encode()).hexdigest()[:10]} "
        f"domain_hash={hashlib.sha256(domain.encode()).hexdigest()[:10]}"
    )


def _extract_session_values(value: Any, target: dict[str, str]) -> None:
    aliases = {
        "token": "token",
        "access_token": "token",
        "accessToken": "token",
        "user_id": "user_id",
        "userId": "user_id",
        "userID": "user_id",
        "device_id": "device_id",
        "deviceId": "device_id",
        "uuid": "uuid",
        "op_ticket": "op_ticket",
        "opTicket": "op_ticket",
    }
    if isinstance(value, dict):
        for key, item in value.items():
            if key in aliases and isinstance(item, (str, int)) and str(item):
                target[aliases[key]] = str(item)
            elif isinstance(item, (dict, list)):
                _extract_session_values(item, target)
    elif isinstance(value, list):
        for item in value:
            _extract_session_values(item, target)


def _agree_if_needed(page) -> None:
    checkbox = page.locator('input[type="checkbox"]').first
    if checkbox.count() and checkbox.is_visible() and not checkbox.is_checked():
        checkbox.check(force=True)
        return
    page.evaluate("""() => {
        const terms = [...document.querySelectorAll('p')].find((item) =>
            /terms of service/i.test(item.textContent || ''));
        const button = terms?.parentElement?.querySelector('button');
        if (button) button.click();
    }""")


def _normalize_minmax_storage(value: dict[str, Any]) -> None:
    for key, raw in tuple(value.items()):
        if not isinstance(raw, str):
            continue
        try:
            parsed = json.loads(raw)
        except (ValueError, TypeError):
            continue
        if not isinstance(parsed, dict):
            continue
        for source, target in (
            ("token", "token"),
            ("access_token", "token"),
            ("user_id", "user_id"),
            ("userId", "user_id"),
            ("device_id", "device_id"),
            ("deviceId", "device_id"),
            ("uuid", "uuid"),
            ("op_ticket", "op_ticket"),
        ):
            if parsed.get(source) and not value.get(target):
                value[target] = parsed[source]


def _signed_get(path: str, current: dict[str, Any], proxy_url: str) -> tuple[str, dict[str, str]]:
    return _signed_request(path, "GET", "", current, stream=False, proxy_url=proxy_url)


def _signed_request(
    path: str,
    method: str,
    body: str,
    current: dict[str, Any],
    *,
    stream: bool,
    proxy_url: str = "",
) -> tuple[str, dict[str, str]]:
    config = settings()
    signature_salt, yy_salt, version_code = _official_profile(proxy_url, current)
    unix = int(time.time()) * 1000
    device = (
        current.get("device_profile") if isinstance(current.get("device_profile"), dict) else {}
    )
    user_agent = str(current.get("user_agent") or core_settings().provider_user_agent)
    browser_name = _browser_name(user_agent, str(device.get("browser_name") or ""))
    offset = int(
        device.get("timezone_offset") or datetime.now().astimezone().utcoffset().total_seconds()
    )
    query: list[tuple[str, Any]] = [
        ("device_platform", config.minmax_profile_device_platform),
        ("biz_id", config.minmax_profile_biz_id),
        ("app_id", config.minmax_profile_app_id),
        ("version_code", version_code),
        ("unix", unix),
        ("timezone_offset", offset),
        ("sys_language", config.minmax_profile_language),
        ("lang", config.minmax_profile_language),
        ("uuid", current.get("uuid")),
        ("device_id", required(current, "device_id")),
        ("os_name", device.get("os_name") or "Linux"),
        ("browser_name", browser_name),
        ("device_memory", device.get("device_memory") or "8"),
        ("cpu_core_num", device.get("cpu_core_num") or "8"),
        ("browser_language", device.get("browser_language") or "en-US"),
        ("browser_platform", device.get("browser_platform") or "Linux aarch64"),
        ("user_id", required(current, "user_id")),
        ("op_ticket", current.get("op_ticket")),
        ("screen_width", device.get("screen_width") or "1920"),
        ("screen_height", device.get("screen_height") or "1080"),
        ("token", required(current, "token", "access_token")),
        ("client", "web"),
        ("region", "global"),
    ]
    actual = "&".join(f"{quote(str(k))}={quote(str(v), safe='')}" for k, v in query if v)
    signed = "&".join(
        f"{quote(str(k))}={quote(str(v if v is not None else ('undefined' if k == 'op_ticket' else 'null')), safe='')}"
        for k, v in query
    )
    separator = "&" if "?" in path else "?"
    signed_path = f"{path}{separator}{signed}"
    base_url = config.minmax_stream_base_url if stream else config.minmax_base_url
    yy_resource = base_url + signed_path if stream else signed_path
    yy_body = json.dumps(body, ensure_ascii=False, separators=(",", ":")) if body else "{}"
    yy = _md5(quote(yy_resource, safe="~()*!.'") + "_" + yy_body + _md5(str(unix)) + yy_salt)
    seconds = unix // 1000
    headers = {
        "accept": "*/*",
        "accept-language": _accept_language(str(device.get("browser_language") or "en-US")),
        "content-type": "application/json",
        "origin": config.minmax_base_url,
        "priority": "u=1, i",
        "referer": config.minmax_base_url + "/",
        **_client_hint_headers(user_agent, device),
        "sec-fetch-dest": "empty",
        "sec-fetch-mode": "cors",
        "sec-fetch-site": "same-site" if stream else "same-origin",
        "token": required(current, "token", "access_token"),
        "user-agent": user_agent,
        "x-signature": _md5(str(seconds) + signature_salt + body),
        "x-timestamp": str(seconds),
        "yy": yy,
    }
    return f"{base_url}{path}{separator}{actual}", headers


def _transport_input(payload: dict[str, Any], *, stream: bool) -> tuple[str, str, str]:
    method = str(payload.get("method") or "").upper()
    path = str(payload.get("path") or "")
    body = str(payload.get("body") or "")
    parsed = urlparse(path)
    if parsed.scheme or parsed.netloc or parsed.fragment or not path.startswith("/"):
        raise ValueError("MinMax transport path is invalid")
    clean_path = parsed.path
    query = parse_qsl(parsed.query, keep_blank_values=True)
    allowed = (
        (
            method == "GET"
            and not stream
            and (
                (clean_path == "/archon/api/v1/config" and not query)
                or (clean_path == "/archon/api/v1/agent" and query == [("limit", "20")])
            )
        )
        or (
            method == "POST"
            and not stream
            and re.fullmatch(r"/archon/api/v1/agent/[0-9]+/session", clean_path)
            and not query
        )
        or (
            method == "POST"
            and stream
            and re.fullmatch(r"/archon/api/v1/session/[0-9]+/message", clean_path)
            and not query
        )
        or (
            method == "GET"
            and not stream
            and clean_path == "/v1/api/files/request_policy"
            and not query
        )
        or (
            method == "POST"
            and not stream
            and clean_path == "/v1/api/files/policy_callback"
            and not query
        )
    )
    if not allowed:
        raise ValueError("MinMax transport operation is not allowlisted")
    return method, path, body


def _transport_frame(kind: str, **payload: object) -> bytes:
    return (json.dumps({"type": kind, **payload}, separators=(",", ":")) + "\n").encode()


def _optional_proxy_parameters(payload: dict[str, Any]) -> dict[str, Any]:
    if not any(key in payload for key in ("proxy_pool", "proxy_url", "dynamic_proxy")):
        return {
            "explicit_url": "",
            "dynamic": False,
            "subscription_url": "",
            "node_urls": None,
            "affinity_key": "",
            "strict_affinity": False,
        }
    return proxy_parameters(payload)


@asynccontextmanager
async def _transport_proxy_lease(payload: dict[str, Any]):
    config = settings()
    lease = proxy_lease(
        check_url=config.minmax_base_url,
        reject_redirect_hosts=("minimaxi.com",),
        **_optional_proxy_parameters(payload),
    )
    proxy_url = await asyncio.to_thread(lease.__enter__)
    try:
        yield proxy_url
    finally:
        await asyncio.to_thread(lease.__exit__, None, None, None)


def _browser_name(user_agent: str, captured: str) -> str:
    if "Firefox/" in user_agent:
        return "Firefox"
    if "Edg/" in user_agent:
        return "Edge"
    if "Chrome/" in user_agent:
        return "Chrome"
    return captured.strip() or "Unknown"


def _impersonate(current: dict[str, Any]) -> str:
    user_agent = str(current.get("user_agent") or "")
    if "Firefox/" in user_agent:
        return "firefox"
    return "chrome146"


def _accept_language(language: str) -> str:
    normalized = language.strip() or "en-US"
    base = normalized.split("-", 1)[0]
    return normalized if base == normalized else f"{normalized},{base};q=0.9"


def _client_hint_headers(user_agent: str, device: dict[str, Any]) -> dict[str, str]:
    match = re.search(r"(?:Chrome|Chromium)/(\d+)", user_agent)
    if not match:
        return {}
    version = match.group(1)
    platform = str(device.get("os_name") or "Windows").split()[0]
    return {
        "sec-ch-ua": (
            f'"Not;A=Brand";v="8", "Chromium";v="{version}", "Google Chrome";v="{version}"'
        ),
        "sec-ch-ua-mobile": "?0",
        "sec-ch-ua-platform": f'"{platform}"',
    }


def _md5(value: str) -> str:
    return hashlib.md5(value.encode(), usedforsecurity=False).hexdigest()


_profile_cache: tuple[float, tuple[str, str, str]] | None = None


def _official_profile(
    proxy_url: str, current: dict[str, Any] | None = None
) -> tuple[str, str, str]:
    global _profile_cache
    del proxy_url
    config = settings()
    configured = (
        config.minmax_signature_salt,
        config.minmax_yy_salt,
        config.minmax_version_code,
    )
    if all(configured):
        return configured
    if _profile_cache and time.monotonic() - _profile_cache[0] < 21_600:
        return _profile_cache[1]
    signature = config.minmax_signature_salt
    yy_salt = config.minmax_yy_salt
    version = config.minmax_version_code
    if not signature or not yy_salt or not version:
        fallback = (current or {}).get("request_profile")
        if isinstance(fallback, dict):
            signature = signature or str(fallback.get("signature_salt") or "")
            yy_salt = yy_salt or str(fallback.get("yy_salt") or "")
            version = version or str(fallback.get("version_code") or "")
    if not signature or not yy_salt or not version:
        raise RuntimeError(
            "MinMax request profile is unavailable; use the Camoufox page bridge to refresh it"
        )
    _profile_cache = (time.monotonic(), (signature, yy_salt, version))
    return _profile_cache[1]


def _signature_salt(script: str) -> str:
    offset = 0
    while (marker := script.find("x-signature", offset)) >= 0:
        first = script.find("${", marker)
        first_end = script.find("}", first + 2) if first >= 0 else -1
        second = script.find("${", first_end + 1) if first_end >= 0 else -1
        if first >= 0 and first - marker < 300 and first_end >= 0 and second >= 0:
            candidate = script[first_end + 1 : second]
            if _valid_literal(candidate, 6, 80):
                return candidate
        offset = marker + 1
    return ""


def _yy_salt(script: str) -> str:
    offset = 0
    while (marker := script.find("hasSearchParamsPath", offset)) >= 0:
        call = script.find("toString())}", marker)
        end = script.find("`", call) if call >= 0 else -1
        brace = script.find("}", call) if call >= 0 else -1
        if call >= 0 and call - marker < 800 and end > call and brace >= 0:
            candidate = script[brace + 1 : end]
            if _valid_literal(candidate, 2, 32):
                return candidate
        offset = marker + 1
    return ""


def _valid_literal(value: str, minimum: int, maximum: int) -> bool:
    return minimum <= len(value) <= maximum and not any(
        character in "${}`" or character.isspace() for character in value
    )


def _script_urls(base_url: str, html: str, allowed: set[str | None]) -> list[str]:
    scripts = [
        urljoin(base_url, source)
        for source in re.findall(
            r'<script[^>]+src=["\']([^"\']+\.js(?:\?[^"\']*)?)["\']',
            html,
            re.IGNORECASE,
        )
    ]
    return [
        url
        for url in scripts[:40]
        if urlparse(url).scheme == "https" and urlparse(url).hostname in allowed
    ]


def _profile_from_browser(context, page) -> dict[str, str]:
    config = settings()
    allowed = {
        urlparse(config.minmax_base_url).hostname,
        *(host.strip().lower() for host in config.minmax_profile_asset_hosts.split(",")),
    }
    sources = page.locator("script[src]").evaluate_all(
        "elements => elements.map(element => element.src).filter(Boolean)"
    )
    signature = yy_salt = version = ""
    for source in [url for url in sources[:40] if urlparse(url).hostname in allowed]:
        try:
            response = context.request.get(source, timeout=20_000)
            if not response.ok:
                continue
            script = response.text()
        except Exception:  # noqa: BLE001,S112 - individual official assets are optional
            continue
        signature = signature or _signature_salt(script)
        yy_salt = yy_salt or _yy_salt(script)
        match = re.search(r'version_code\s*:\s*["\']([0-9]{3,12})["\']', script)
        version = version or (match.group(1) if match else "")
        if signature and yy_salt and version:
            return {"signature_salt": signature, "yy_salt": yy_salt, "version_code": version}
    return {}


official_browser_transport = MinmaxOfficialBrowserTransport(settings().minmax_base_url)
minmax_daily_checkin = MinmaxDailyCheckin(
    lambda: official_browser_transport,
    lambda payload: _transport_proxy_lease(payload),
)


def _minmax_proxy_affinity(email: str, attempt: int) -> str:
    if attempt < 1:
        raise ValueError("MinMax proxy affinity attempt must be positive")
    normalized = email.strip().lower()
    if not normalized:
        raise ValueError("MinMax proxy affinity requires an email address")
    digest = hashlib.sha256(f"{normalized}\0{attempt}".encode()).hexdigest()[:32]
    return f"minmax-{digest}"
