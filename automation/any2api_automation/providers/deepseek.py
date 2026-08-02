from __future__ import annotations

import asyncio
import logging
import random
import re
import time
from types import TracebackType
from typing import Any
from urllib.parse import parse_qs, urlparse

from curl_cffi.requests import Session as CurlSession

from ..lifecycle.account import (
    RegistrationPasswordPolicy,
    credential,
    prepare_registration,
    required,
    strong_password,
)
from ..lifecycle.browser import (
    BrowserContextProfile,
    BrowserFingerprintPolicy,
    BrowserFingerprintVariant,
    BrowserLaunchProfile,
    BrowserResult,
    run_browser_flow,
)
from ..lifecycle.mail import Mailbox, TempMailClient
from ..lifecycle.proxy import (
    proxy_attempt_payload,
    proxy_lease,
    proxy_parameters,
)
from ..lifecycle.registration import RegistrationStage, RegistrationTrace
from .base import AutomationProvider, AutomationProviderManifest
from .deepseek_challenge import DeepseekHcaptchaChallenge
from .deepseek_settings import settings

logger = logging.getLogger("any2api_automation.providers.deepseek")


class _BrowserReauthenticationRequired(RuntimeError):
    pass


class DeepseekAutomationProvider(AutomationProvider):
    manifest = AutomationProviderManifest(
        id="deepseek",
        browser_backend="patchright",
        fallback_backend="camoufox",
        isolation="process",
        challenge_types=(
            "hcaptcha_area_select",
            "hcaptcha_grid",
            "hcaptcha_semantic_drag",
        ),
        operations=("register", "reauthenticate", "keepalive"),
        realtime=True,
        registration_attempt_mode="single_identity",
    )

    async def register(self, payload: dict[str, Any]) -> dict[str, Any]:
        trace = RegistrationTrace(self.manifest.id)
        try:
            mail, mailbox, password = await prepare_registration(
                payload,
                password_policy=RegistrationPasswordPolicy(
                    lambda: strong_password(16), min_length=8, max_length=64
                ),
            )
            trace.mark(RegistrationStage.MAILBOX_CREATED)
            flow_payload = {**payload}
            flow_payload.setdefault("proxy_check_url", settings().deepseek_base_url)
            result = await _run_registration_browser(
                self,
                flow_payload,
                mail,
                mailbox,
                password,
                trace,
            )
            return result.response()
        except Exception as error:
            raise trace.failure(error) from error

    async def reauthenticate(self, payload: dict[str, Any]) -> dict[str, Any]:
        current = credential(payload)
        try:
            return await asyncio.to_thread(_reauthenticate_sync, payload, current)
        except _BrowserReauthenticationRequired:
            logger.info("DeepSeek password login requires browser WAF clearance")
        flow_payload = {**payload}
        flow_payload.setdefault("proxy_check_url", settings().deepseek_base_url)
        result = await asyncio.to_thread(
            run_browser_flow,
            lambda page, context, backend, proxy_url: _reauthenticate_browser(page, current),
            preferred=self.manifest.browser_backend,
            fallback=self.manifest.fallback_backend,
            payload=flow_payload,
            context_profile=self.browser_context_profile(),
            launch_profile=self.browser_launch_profile(),
            fingerprint_policy=self.browser_fingerprint_policy(),
        )
        return {
            "healthy": True,
            "ready_for_inference": False,
            "credential_patch": result.credential,
            "metadata_patch": {
                **result.metadata,
                "authentication": "password_browser",
                "birthday": result.credential["birthday_status"],
                "inference_probe_required": True,
            },
        }

    async def keepalive(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await asyncio.to_thread(_keepalive_sync, payload, credential(payload))

    def browser_context_profile(self) -> BrowserContextProfile:
        return BrowserContextProfile(
            ignore_https_errors=True,
            locale="en-US",
            timezone_id="Asia/Tokyo",
            viewport_width=1440,
            viewport_height=900,
            accept_language="en-US,en;q=0.9",
        )

    def browser_launch_profile(self) -> BrowserLaunchProfile:
        return BrowserLaunchProfile(headless=False, humanize=True, camoufox_os="windows")

    def browser_fingerprint_policy(self) -> BrowserFingerprintPolicy:
        return BrowserFingerprintPolicy(
            variants=tuple(
                BrowserFingerprintVariant(
                    id=f"deepseek-windows-{width}x{height}",
                    os="windows",
                    locale="en-US",
                    timezone_id="Asia/Tokyo",
                    viewport_width=width,
                    viewport_height=height,
                    accept_language="en-US,en;q=0.9",
                )
                for width, height in ((1365, 900), (1440, 900), (1536, 864))
            ),
            camoufox_mode="synthetic",
        )


async def _run_registration_browser(
    provider: DeepseekAutomationProvider,
    payload: dict[str, Any],
    mail: TempMailClient,
    mailbox: Mailbox,
    password: str,
    trace: RegistrationTrace,
) -> BrowserResult:
    attempts = settings().deepseek_registration_browser_attempts
    for attempt in range(1, attempts + 1):
        try:
            return await asyncio.to_thread(
                run_browser_flow,
                lambda page, context, backend, proxy_url: _register_browser(
                    page, backend, mail, mailbox, password, trace
                ),
                preferred=provider.manifest.browser_backend,
                fallback=provider.manifest.fallback_backend,
                payload=proxy_attempt_payload(
                    payload,
                    identity=mailbox.address,
                    attempt=attempt,
                ),
                context_profile=provider.browser_context_profile(),
                launch_profile=provider.browser_launch_profile(),
                fingerprint_policy=provider.browser_fingerprint_policy(),
            )
        except Exception as error:
            accepted = trace.current in {
                RegistrationStage.UPSTREAM_ACCEPTED.value,
                RegistrationStage.ACTIVATED.value,
                RegistrationStage.CREDENTIAL_CAPTURED.value,
            }
            if accepted or attempt >= attempts:
                raise
            diagnostic = str(error)
            if not diagnostic.startswith("DeepSeek hCaptcha failed"):
                diagnostic = "unavailable"
            logger.warning(
                "DeepSeek registration browser retry attempt=%s/%s error_type=%s diagnostic=%s",
                attempt,
                attempts,
                type(error).__name__,
                diagnostic[:1000],
            )
    raise RuntimeError("DeepSeek registration browser attempts were exhausted")


def _register_browser(
    page: Any,
    backend: str,
    mail: TempMailClient,
    mailbox: Mailbox,
    password: str,
    trace: RegistrationTrace,
) -> BrowserResult:
    base_url = settings().deepseek_base_url.rstrip("/")
    trace.mark(RegistrationStage.BROWSER_LAUNCHED)
    _warm_up_hcaptcha(page, base_url)
    page.goto(f"{base_url}/sign_up", wait_until="domcontentloaded", timeout=90_000)
    page.wait_for_timeout(1500)
    if page.get_by_text(re.compile("only phone number registration", re.IGNORECASE)).count():
        raise RuntimeError("DeepSeek email registration is unavailable for the current egress")
    email = _visible(page, ('input[type="email"]', 'input[placeholder*="email" i]'))
    if email is None:
        raise RuntimeError("DeepSeek email registration form is unavailable")
    trace.mark(RegistrationStage.FORM_READY)
    email.fill(mailbox.address)
    send = _visible(
        page,
        (
            '[role="button"]:has-text("Send code")',
            'button:has-text("Send code")',
            '[role="button"]:has-text("发送验证码")',
        ),
    )
    if send is None:
        raise RuntimeError("DeepSeek send-code control is unavailable")
    challenge = DeepseekHcaptchaChallenge()
    seen = mail.message_ids_sync(mailbox)
    code_responses: list[Any] = []

    def observe_code_response(response: Any) -> None:
        if urlparse(response.url).path == "/api/v0/users/create_email_verification_code":
            code_responses.append(response)

    page.on("response", observe_code_response)
    try:
        send.click()
        try:
            challenge.solve(page, completed=lambda: bool(code_responses))
        except Exception as error:
            raise RuntimeError(
                "DeepSeek hCaptcha failed "
                f"type={type(error).__name__} diagnostic={challenge.last_diagnostic}"
            ) from error
        deadline = time.monotonic() + 15
        while not code_responses and time.monotonic() < deadline:
            page.wait_for_timeout(250)
    finally:
        page.remove_listener("response", observe_code_response)
    if not code_responses:
        raise RuntimeError("DeepSeek hCaptcha completed without an email verification response")
    code_response = code_responses[-1]
    code_body = _json_response(code_response, "email verification")
    _require_success(code_body, "email verification")
    trace.mark(RegistrationStage.CHALLENGE_CLEARED)
    request_body = code_response.request.post_data_json or {}
    request_headers = code_response.request.all_headers()
    device_id = str(request_body.get("device_id") or "").strip()
    if not device_id:
        raise RuntimeError("DeepSeek email verification omitted the device id")
    otp = mail.wait_for_code_sync(mailbox, seen_ids=seen)
    trace.mark(RegistrationStage.OTP_RECEIVED)
    password_fields = page.locator('input[type="password"]')
    if password_fields.count() < 2:
        raise RuntimeError("DeepSeek password fields are unavailable")
    password_fields.nth(0).fill(password)
    password_fields.nth(1).fill(password)
    code_field = _visible(
        page,
        ('input[placeholder="Code"]', 'input[autocomplete="one-time-code"]'),
    )
    if code_field is None:
        raise RuntimeError("DeepSeek OTP field is unavailable")
    code_field.fill(otp)
    signup = _visible(
        page,
        ('[role="button"]:has-text("Sign up")', 'button:has-text("Sign up")'),
    )
    if signup is None:
        raise RuntimeError("DeepSeek signup control is unavailable")
    with page.expect_response(
        lambda response: urlparse(response.url).path == "/api/v0/users/register",
        timeout=120_000,
    ) as register_info:
        signup.click()
    trace.mark(RegistrationStage.FORM_SUBMITTED)
    register_body = _json_response(register_info.value, "registration")
    _require_success(register_body, "registration")
    user = _registration_user(register_body)
    profile = _request_profile(request_headers)
    if not str(user.get("token") or "").strip() or not str(user.get("id") or "").strip():
        user = _recover_registered_user(page, mailbox.address, password, device_id, profile)
    token = str(user.get("token") or "").strip()
    external_id = str(user.get("id") or "").strip()
    email_value = str(user.get("email") or "").strip().lower()
    if (
        not token
        or not external_id
        or (email_value and "*" not in email_value and email_value != mailbox.address.lower())
    ):
        raise RuntimeError("DeepSeek registration returned an invalid account identity")
    trace.mark(RegistrationStage.UPSTREAM_ACCEPTED)
    birthday = _set_birthday(page, token, profile)
    trace.mark(RegistrationStage.ACTIVATED)
    user_agent = str(page.evaluate("() => navigator.userAgent") or "")
    value = {
        "email": mailbox.address,
        "password": password,
        "mail_jwt": mailbox.jwt,
        "token": token,
        "device_id": device_id,
        "user_agent": user_agent,
        "browser_profile": "chrome146",
        "birthday_status": birthday,
        **profile,
    }
    trace.mark(RegistrationStage.CREDENTIAL_CAPTURED)
    return BrowserResult(
        external_id=external_id,
        email=mailbox.address,
        credential=value,
        metadata={
            **trace.metadata(),
            "captcha": challenge.last_diagnostic,
            "birthday": birthday,
            "registration_backend": backend,
            "inference_probe_required": True,
        },
        ready_for_inference=False,
    )


def _reauthenticate_sync(payload: dict[str, Any], current: dict[str, Any]) -> dict[str, Any]:
    email = required(current, "email")
    password = required(current, "password")
    device_id = required(current, "device_id")
    birthday_status = str(current.get("birthday_status") or "pending").strip().lower()
    with _session(payload, current) as client:
        response = client.post(
            f"{settings().deepseek_base_url.rstrip('/')}/api/v0/users/login",
            headers=_headers(current, with_token=False),
            json={
                "email": email,
                "mobile": "",
                "password": password,
                "area_code": "",
                "device_id": device_id,
                "os": "web",
            },
            timeout=90,
        )
        if _is_waf_challenge(response):
            raise _BrowserReauthenticationRequired
        if response.status_code in {401, 403}:
            return {
                "healthy": False,
                "auth_expired": True,
                "ready_for_inference": False,
                "error_class": "deepseek_login_rejected",
            }
        response.raise_for_status()
        body = response.json()
        _require_success(body, "login")
        user = ((body.get("data") or {}).get("biz_data") or {}).get("user") or {}
        token = str(user.get("token") or "").strip()
        external_id = str(user.get("id") or "").strip()
        if not token or not external_id:
            raise RuntimeError("DeepSeek login returned no account token")
        if birthday_status != "set" or bool(user.get("need_birthday")):
            birthday_status = _set_birthday_http(client, current, token)
    patch = {"token": token, "birthday_status": birthday_status}
    if birthday_status != "set":
        return {
            "healthy": False,
            "auth_expired": False,
            "ready_for_inference": False,
            "error_class": "deepseek_birthday_activation_pending",
            "credential_patch": patch,
            "metadata_patch": {"birthday": birthday_status},
        }
    return {
        "healthy": True,
        "ready_for_inference": False,
        "credential_patch": patch,
        "metadata_patch": {
            "authentication": "password",
            "birthday": birthday_status,
            "inference_probe_required": True,
        },
    }


def _reauthenticate_browser(page: Any, current: dict[str, Any]) -> BrowserResult:
    email = required(current, "email")
    password = required(current, "password")
    device_id = required(current, "device_id")
    base_url = settings().deepseek_base_url.rstrip("/")
    _open_sign_in(page, base_url)
    profile = _request_profile(_headers(current, with_token=False))
    user = _recover_registered_user(page, email, password, device_id, profile)
    token = str(user.get("token") or "").strip()
    external_id = str(user.get("id") or "").strip()
    if not token or not external_id:
        raise RuntimeError("DeepSeek browser login returned no account token")
    birthday_status = str(current.get("birthday_status") or "pending").strip().lower()
    if birthday_status != "set" or bool(user.get("need_birthday")):
        birthday_status = _set_birthday(page, token, profile)
    if birthday_status != "set":
        raise RuntimeError("DeepSeek birthday activation remains pending")
    return BrowserResult(
        external_id=external_id,
        email=email,
        credential={"token": token, "birthday_status": birthday_status},
        metadata={"reauthentication_transport": "browser"},
        ready_for_inference=False,
    )


def _keepalive_sync(payload: dict[str, Any], current: dict[str, Any]) -> dict[str, Any]:
    token = required(current, "token", "access_token")
    device_id = required(current, "device_id")
    with _session(payload, current) as client:
        response = client.get(
            f"{settings().deepseek_base_url.rstrip('/')}/api/v0/client/settings",
            headers=_headers(current, with_token=True, token=token),
            params={"did": device_id, "scope": "model"},
            timeout=60,
        )
    if response.status_code in {401, 403}:
        return {"healthy": False, "auth_expired": True, "ready_for_inference": False}
    response.raise_for_status()
    body = response.json()
    healthy = (
        int(body.get("code") or 0) == 0
        and int((body.get("data") or {}).get("biz_code") or 0) == 0
        and isinstance(
            (((body.get("data") or {}).get("biz_data") or {}).get("settings") or {}).get(
                "model_configs"
            ),
            dict,
        )
    )
    return {
        "healthy": healthy,
        "auth_expired": False,
        "ready_for_inference": healthy,
        "error_class": "" if healthy else "deepseek_profile_unavailable",
    }


class _SessionLease:
    def __init__(self, payload: dict[str, Any], current: dict[str, Any]) -> None:
        self.payload = payload
        self.current = current
        self.proxy_context = None
        self.client = None

    def __enter__(self) -> CurlSession:
        self.proxy_context = proxy_lease(
            check_url=settings().deepseek_base_url,
            **proxy_parameters(self.payload),
        )
        proxy_url = self.proxy_context.__enter__()
        self.client = CurlSession(
            impersonate=str(self.current.get("browser_profile") or "chrome146")
        )
        if proxy_url:
            self.client.proxies = {"http": proxy_url, "https": proxy_url}
        return self.client

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        if self.client is not None:
            self.client.close()
        if self.proxy_context is not None:
            self.proxy_context.__exit__(exc_type, exc, traceback)


def _session(payload: dict[str, Any], current: dict[str, Any]) -> _SessionLease:
    return _SessionLease(payload, current)


def _is_waf_challenge(response: Any) -> bool:
    headers = getattr(response, "headers", {})
    waf_action = str(headers.get("x-amzn-waf-action") or "").strip().lower()
    content_type = str(headers.get("content-type") or "").strip().lower()
    return waf_action == "challenge" or (
        response.status_code == 202
        and not getattr(response, "content", b"")
        and content_type.startswith("text/html")
    )


def _headers(current: dict[str, Any], *, with_token: bool, token: str = "") -> dict[str, str]:
    config = settings()
    headers = {
        "Accept": "application/json, */*",
        "Content-Type": "application/json",
        "Origin": config.deepseek_base_url,
        "Referer": config.deepseek_base_url.rstrip("/") + "/",
        "X-Client-Bundle-Id": str(current.get("bundle_id") or config.deepseek_bundle_id),
        "X-Client-Platform": str(current.get("platform") or config.deepseek_platform),
        "X-Client-Version": str(
            current.get("client_version") or config.deepseek_client_version_fallback
        ),
        "X-Client-Locale": str(current.get("locale") or config.deepseek_locale),
        "X-Client-Timezone-Offset": str(
            current.get("timezone_offset") or config.deepseek_timezone_offset_seconds
        ),
    }
    if with_token:
        headers["Authorization"] = f"Bearer {token}"
    return headers


def _request_profile(headers: dict[str, str]) -> dict[str, Any]:
    config = settings()
    lowered = {str(key).lower(): str(value) for key, value in headers.items()}
    return {
        "bundle_id": lowered.get("x-client-bundle-id") or config.deepseek_bundle_id,
        "platform": lowered.get("x-client-platform") or config.deepseek_platform,
        "client_version": lowered.get("x-client-version")
        or config.deepseek_client_version_fallback,
        "locale": lowered.get("x-client-locale") or config.deepseek_locale,
        "timezone_offset": int(
            lowered.get("x-client-timezone-offset") or config.deepseek_timezone_offset_seconds
        ),
    }


def _registration_user(body: dict[str, Any]) -> dict[str, Any]:
    data = body.get("data") or {}
    biz_data = data.get("biz_data") or {} if isinstance(data, dict) else {}
    if not isinstance(biz_data, dict):
        raise TypeError("DeepSeek registration returned invalid business data")
    nested_code = int(biz_data.get("code") or 0)
    if nested_code != 0:
        raise RuntimeError(f"DeepSeek registration was rejected nested_code={nested_code}")
    user = biz_data.get("user") or {}
    return user if isinstance(user, dict) else {}


def _recover_registered_user(
    page: Any,
    email: str,
    password: str,
    device_id: str,
    profile: dict[str, Any],
) -> dict[str, Any]:
    result = page.evaluate(
        """async args => {
          const response = await fetch('/api/v0/users/login', {
            method: 'POST',
            credentials: 'include',
            headers: {
              'Content-Type': 'application/json',
              'X-Client-Bundle-Id': args.profile.bundle_id,
              'X-Client-Platform': args.profile.platform,
              'X-Client-Version': args.profile.client_version,
              'X-Client-Locale': args.profile.locale,
              'X-Client-Timezone-Offset': String(args.profile.timezone_offset)
            },
            body: JSON.stringify({
              email: args.email,
              mobile: '',
              password: args.password,
              area_code: '',
              device_id: args.device_id,
              os: 'web'
            })
          });
          let body = {};
          try { body = await response.json(); } catch (_) {}
          return {
            status: response.status,
            body,
            wafAction: response.headers.get('x-amzn-waf-action') || '',
            contentType: response.headers.get('content-type') || ''
          };
        }""",
        {
            "email": email,
            "password": password,
            "device_id": device_id,
            "profile": profile,
        },
    )
    if not isinstance(result, dict) or int(result.get("status") or 0) >= 400:
        status = int(result.get("status") or 0) if isinstance(result, dict) else 0
        raise RuntimeError(f"DeepSeek post-registration login returned HTTP {status}")
    body = result.get("body") or {}
    if str(result.get("wafAction") or "").strip() or not body:
        raise RuntimeError("DeepSeek browser login remained behind WAF")
    if not isinstance(body, dict):
        raise TypeError("DeepSeek post-registration login returned invalid JSON")
    _require_success(body, "post-registration login")
    user = ((body.get("data") or {}).get("biz_data") or {}).get("user") or {}
    return user if isinstance(user, dict) else {}


def _warm_up_hcaptcha(page: Any, base_url: str) -> None:
    body = _open_sign_in(page, base_url)
    feature = (
        (((body.get("data") or {}).get("biz_data") or {}).get("settings") or {})
        .get("chat_hcaptcha", {})
        .get("value")
    )
    if feature is not True:
        raise RuntimeError("DeepSeek official hCaptcha feature is unavailable")


def _open_sign_in(page: Any, base_url: str) -> dict[str, Any]:
    def is_main_settings(response: Any) -> bool:
        parsed = urlparse(response.url)
        return parsed.path == "/api/v0/client/settings" and parse_qs(parsed.query).get("scope") == [
            "main"
        ]

    with page.expect_response(is_main_settings, timeout=120_000) as response_info:
        page.goto(f"{base_url}/sign_in", wait_until="domcontentloaded", timeout=120_000)
    body = _json_response(response_info.value, "main client settings")
    _require_success(body, "main client settings")
    return body


def _set_birthday(page: Any, token: str, profile: dict[str, Any]) -> str:
    birthday = {
        "year": random.randint(1985, 2000),
        "month": random.randint(1, 12),
    }
    for attempt in range(1, 4):
        try:
            result = page.evaluate(
                """async args => {
          const response = await fetch('/api/v0/users/set_birthday', {
            method: 'POST',
            credentials: 'include',
            headers: {
              'Authorization': `Bearer ${args.token}`,
              'Content-Type': 'application/json',
              'X-Client-Bundle-Id': args.profile.bundle_id,
              'X-Client-Platform': args.profile.platform,
              'X-Client-Version': args.profile.client_version,
              'X-Client-Locale': args.profile.locale,
              'X-Client-Timezone-Offset': String(args.profile.timezone_offset)
            },
            body: JSON.stringify({year: args.year, month: args.month})
          });
          let body = {};
          try { body = await response.json(); } catch (_) {}
          return {
            status: response.status,
            code: Number(body.code || 0),
            bizCode: Number(body.data?.biz_code || 0)
          };
        }""",
                {"token": token, "profile": profile, **birthday},
            )
            if (
                isinstance(result, dict)
                and int(result.get("status") or 0) < 400
                and int(result.get("bizCode") or 0) == 0
            ):
                return "set"
        except Exception:  # noqa: BLE001 - preserve an already-created upstream account
            logger.warning(
                "DeepSeek birthday activation attempt failed attempt=%s/3",
                attempt,
            )
        if attempt < 3:
            page.wait_for_timeout(750 * attempt)
    logger.warning("DeepSeek birthday activation remains pending after bounded retries")
    return "pending"


def _set_birthday_http(client: Any, current: dict[str, Any], token: str) -> str:
    for attempt in range(1, 4):
        response = client.post(
            f"{settings().deepseek_base_url.rstrip('/')}/api/v0/users/set_birthday",
            headers=_headers(current, with_token=True, token=token),
            json={"year": random.randint(1985, 2000), "month": random.randint(1, 12)},
            timeout=60,
        )
        if response.status_code < 400:
            try:
                body = response.json()
                _require_success(body, "birthday activation")
                return "set"
            except (RuntimeError, TypeError, ValueError):
                pass
        if attempt < 3:
            time.sleep(0.75 * attempt)
    return "pending"


def _visible(page: Any, selectors: tuple[str, ...], timeout_ms: int = 20_000) -> Any | None:
    for selector in selectors:
        locator = page.locator(selector).first
        try:
            locator.wait_for(state="visible", timeout=timeout_ms)
            return locator
        except Exception:  # noqa: BLE001,S112 - try the next stable selector
            continue
    return None


def _json_response(response: Any, operation: str) -> dict[str, Any]:
    if response.status >= 400:
        raise RuntimeError(f"DeepSeek {operation} returned HTTP {response.status}")
    try:
        body = response.json()
    except Exception as error:
        raise RuntimeError(f"DeepSeek {operation} returned invalid JSON") from error
    if not isinstance(body, dict):
        raise TypeError(f"DeepSeek {operation} returned an invalid envelope")
    return body


def _require_success(body: dict[str, Any], operation: str) -> None:
    code = int(body.get("code") or 0)
    data = body.get("data") or {}
    biz_code = int(data.get("biz_code") or 0) if isinstance(data, dict) else -1
    if code != 0 or biz_code != 0:
        raise RuntimeError(f"DeepSeek {operation} was rejected code={code} biz_code={biz_code}")
