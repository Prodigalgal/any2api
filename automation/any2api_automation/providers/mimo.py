import asyncio
import secrets
from typing import Any

import httpx

from ..config import settings as core_settings
from ..lifecycle.account import (
    RegistrationPasswordPolicy,
    credential,
    flow_max_attempts,
    mail_client,
    prepare_registration,
    required,
)
from ..lifecycle.browser import BrowserResult, credential_from_context, fill_first, run_browser_flow
from ..lifecycle.mail import Mailbox, TempMailClient
from ..lifecycle.proxy import proxy_lease, proxy_parameters
from .base import AutomationProvider, AutomationProviderManifest
from .mimo_protocol import XiaomiProtocolClient
from .mimo_settings import settings

_MIMO_PASSWORD_ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789!@#$%&*"
_MIMO_PASSWORD_POLICY = RegistrationPasswordPolicy(
    generator=lambda: "Aa1!" + "".join(secrets.choice(_MIMO_PASSWORD_ALPHABET) for _ in range(8)),
    min_length=8,
    max_length=16,
)


class MimoAutomationProvider(AutomationProvider):
    manifest = AutomationProviderManifest(
        id="mimo",
        browser_backend="http",
        fallback_backend=None,
        isolation="request",
        challenge_types=("ocr",),
        operations=("register", "reauthenticate", "keepalive"),
    )

    async def register(self, payload: dict[str, Any]) -> dict[str, Any]:
        mail, mailbox, password = await prepare_registration(
            payload,
            password_policy=_MIMO_PASSWORD_POLICY,
        )
        attempts = flow_max_attempts(payload, 1)
        last_error: Exception | None = None
        for _ in range(attempts):
            try:
                return await asyncio.to_thread(_register_protocol, payload, mail, mailbox, password)
            except Exception as error:  # noqa: BLE001 - same mailbox retry boundary
                last_error = error
        assert last_error is not None
        raise last_error

    async def reauthenticate(self, payload: dict[str, Any]) -> dict[str, Any]:
        current = credential(payload)
        inference_rejected = _inference_credential_rejected(payload)
        if current.get("pass_token") and not inference_rejected:
            try:
                patch = await asyncio.to_thread(_exchange_pass_token, payload, current)
                candidate = {**current, **patch}
                verification = await asyncio.to_thread(_keepalive_sync, payload, candidate)
                if verification.get("healthy"):
                    return {
                        "healthy": True,
                        "credential_patch": candidate,
                        "recovery_stage": "pass_token",
                        "metadata_patch": {"mimo_recovery_stage": "pass_token"},
                    }
            except (httpx.HTTPError, RuntimeError, ValueError):
                pass
        if current.get("email") and current.get("password") and current.get("mail_jwt"):
            try:
                patch = await asyncio.to_thread(_password_otp_reauthenticate, payload, current)
                candidate = {**current, **patch}
                verification = await asyncio.to_thread(_keepalive_sync, payload, candidate)
                if verification.get("healthy"):
                    return {
                        "healthy": True,
                        "credential_patch": candidate,
                        "recovery_stage": "password_otp",
                        "metadata_patch": {"mimo_recovery_stage": "password_otp"},
                    }
            except (httpx.HTTPError, RuntimeError, ValueError, TimeoutError) as error:
                if inference_rejected:
                    error_class = _recovery_error_class(error)
                    return {
                        "healthy": False,
                        "auth_expired": True,
                        "error_class": error_class,
                        "recovery_stage": "password_otp",
                        "metadata_patch": {
                            "mimo_recovery_stage": "password_otp",
                            "mimo_recovery_error": error_class,
                        },
                    }
        if not current.get("email") or not current.get("password"):
            return {"healthy": False, "auth_expired": True, "terminal": True}
        result = await asyncio.to_thread(
            run_browser_flow,
            lambda page, context, backend, proxy_url: _login_browser(
                page, context, backend, current, payload
            ),
            preferred="camoufox",
            fallback="patchright",
            payload=payload,
        )
        try:
            verification = await asyncio.to_thread(_keepalive_sync, payload, result.credential)
        except (httpx.HTTPError, RuntimeError, ValueError):
            verification = {"healthy": False}
        if not verification.get("healthy"):
            return {
                "healthy": False,
                "auth_expired": True,
                "credential_patch": result.credential,
                "recovery_stage": "browser_login",
                "metadata_patch": {"mimo_recovery_stage": "browser_login"},
            }
        return {
            "healthy": True,
            "credential_patch": result.credential,
            "recovery_stage": "browser_login",
            "metadata_patch": {"mimo_recovery_stage": "browser_login"},
        }

    async def keepalive(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await asyncio.to_thread(_keepalive_sync, payload, credential(payload))


def _keepalive_sync(payload: dict[str, Any], current: dict[str, Any]) -> dict[str, Any]:
    config = settings()
    service_token = required(current, "service_token")
    user_id = required(current, "user_id")
    phase = required(current, "xiaomichatbot_ph")
    base_url = str(payload.get("base_url") or config.mimo_base_url).rstrip("/")
    cookie = f"serviceToken={service_token}; userId={user_id}; xiaomichatbot_ph={phase}"
    with (
        proxy_lease(check_url=base_url, **proxy_parameters(payload)) as proxy_url,
        httpx.Client(timeout=60, proxy=proxy_url or None) as client,
    ):
        response = client.get(
            f"{base_url}/open-apis/bot/config",
            headers={
                "Accept": "application/json",
                "Cookie": cookie,
                "Origin": base_url,
                "Referer": f"{base_url}/",
                "x-timezone": config.mimo_timezone,
                "User-Agent": core_settings().provider_user_agent,
            },
        )
    if response.status_code in {401, 403}:
        return {"healthy": False, "auth_expired": True}
    response.raise_for_status()
    return {"healthy": True, "auth_expired": False}


def _inference_credential_rejected(payload: dict[str, Any]) -> bool:
    metadata = payload.get("metadata")
    if not isinstance(metadata, dict):
        return False
    return (
        str(metadata.get("inference_probe_status") or "").upper() == "FAILED"
        and str(metadata.get("inference_probe_error") or "").lower() == "credential_rejected"
    )


def _recovery_error_class(error: Exception) -> str:
    value = str(error).lower()
    if isinstance(error, TimeoutError):
        return "mimo_reauth_otp_timeout"
    if "password login" in value:
        return "mimo_reauth_password_rejected"
    if "identity" in value and "context" in value:
        return "mimo_reauth_identity_context_missing"
    if "ticket request" in value:
        return "mimo_reauth_otp_send_failed"
    if "email verification" in value:
        return "mimo_reauth_otp_verify_failed"
    if "passtoken" in value:
        return "mimo_reauth_pass_token_missing"
    if isinstance(error, httpx.HTTPStatusError):
        return "mimo_reauth_upstream_rejected"
    if isinstance(error, httpx.HTTPError):
        return "mimo_reauth_transport_error"
    return "mimo_reauth_protocol_error"


def _register_protocol(
    payload: dict[str, Any],
    mail: TempMailClient,
    mailbox: Mailbox,
    password: str,
) -> dict[str, Any]:
    config = settings()
    with proxy_lease(
        check_url=f"{config.mimo_account_url.rstrip('/')}/fe/service/register",
        **proxy_parameters(payload),
    ) as proxy_url:
        client = XiaomiProtocolClient(proxy_url)
        try:
            result = client.register(mail, mailbox, password, payload)
        finally:
            client.close()
    return {
        "healthy": True,
        "external_id": result.email,
        "email": result.email,
        "credential": result.credential,
        "metadata": {},
        "expires_at": None,
    }


def _login_browser(page, context, backend, current, payload) -> BrowserResult:
    config = settings()
    page.goto(
        f"{config.mimo_account_url}/pass/serviceLogin?sid=xiaomichatbot",
        wait_until="domcontentloaded",
    )
    fill_first(
        page,
        ('input[type="email"]', 'input[name="user"]', 'input[name="account"]'),
        required(current, "email"),
    )
    fill_first(
        page, ('input[type="password"]', 'input[name="password"]'), required(current, "password")
    )
    page.locator('button[type="submit"]').first.click()
    page.wait_for_timeout(2500)
    if page.locator('input[autocomplete="one-time-code"], input[maxlength="6"]').count():
        mail = mail_client(payload)
        mailbox = Mailbox(required(current, "email"), required(current, "mail_jwt"))
        code = mail.wait_for_code_sync(mailbox)
        from ..lifecycle.browser import enter_code

        enter_code(page, code)
        page.locator('button[type="submit"]').first.click()
    page.goto(config.mimo_base_url, wait_until="domcontentloaded")
    page.wait_for_timeout(2500)
    value = credential_from_context(
        context, page, required(current, "password"), str(current.get("mail_jwt") or "")
    )
    value.update({"email": required(current, "email"), "registration_backend": backend})
    _normalize_mimo_cookies(value)
    return BrowserResult(required(current, "email"), required(current, "email"), value)


def _exchange_pass_token(payload: dict[str, Any], current: dict[str, Any]) -> dict[str, Any]:
    config = settings()
    with proxy_lease(
        check_url=f"{config.mimo_account_url.rstrip('/')}/pass/serviceLogin",
        **proxy_parameters(payload),
    ) as proxy_url:
        client = XiaomiProtocolClient(proxy_url)
        try:
            return client.exchange_pass_token(current)
        finally:
            client.close()


def _password_otp_reauthenticate(
    payload: dict[str, Any], current: dict[str, Any]
) -> dict[str, Any]:
    config = settings()
    with proxy_lease(
        check_url=f"{config.mimo_account_url.rstrip('/')}/pass/serviceLogin",
        **proxy_parameters(payload),
    ) as proxy_url:
        client = XiaomiProtocolClient(proxy_url)
        try:
            mail = mail_client(payload)
            mailbox = Mailbox(required(current, "email"), required(current, "mail_jwt"))
            return client.reauthenticate_password(current, mail, mailbox)
        finally:
            client.close()


def _normalize_mimo_cookies(value: dict[str, Any]) -> None:
    cookies = value.get("cookies") or {}
    aliases = {
        "service_token": ("serviceToken", "xiaomichatbot_serviceToken"),
        "user_id": ("userId",),
        "xiaomichatbot_ph": ("xiaomichatbot_ph",),
        "pass_token": ("passToken",),
        "c_user_id": ("cUserId",),
        "device_id": ("deviceId",),
    }
    for target, names in aliases.items():
        for name in names:
            if cookies.get(name):
                value[target] = cookies[name]
                break
