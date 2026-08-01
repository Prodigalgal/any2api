from __future__ import annotations

import asyncio
import random
import string
from typing import Any
from urllib.parse import parse_qs, urlparse

import httpx

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
    credential_from_context,
    run_browser_flow,
)
from ..lifecycle.mail import Mailbox, TempMailClient
from ..lifecycle.proxy import proxy_attempt_payload, proxy_lease, proxy_parameters
from ..lifecycle.registration import RegistrationStage, RegistrationTrace
from .base import AutomationProvider, AutomationProviderManifest
from .glm_challenge import GlmAliyunChallenge
from .glm_settings import settings


class GlmAutomationProvider(AutomationProvider):
    manifest = AutomationProviderManifest(
        id="glm",
        browser_backend="camoufox",
        fallback_backend="patchright",
        isolation="process",
        challenge_types=("aliyun_traceless", "semantic_slider", "semantic_drag", "semantic_click"),
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
                    lambda: strong_password(12),
                    min_length=8,
                    max_length=64,
                ),
            )
            trace.mark(RegistrationStage.MAILBOX_CREATED)
            flow_payload = {**payload}
            flow_payload.setdefault("proxy_check_url", settings().glm_base_url)
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
        result = await asyncio.to_thread(
            run_browser_flow,
            lambda page, context, backend, proxy_url: _reauthenticate_browser(
                page,
                context,
                backend,
                current,
            ),
            preferred=self.manifest.browser_backend,
            fallback=self.manifest.fallback_backend,
            payload={**payload, "proxy_check_url": settings().glm_base_url},
            context_profile=self.browser_context_profile(),
            launch_profile=self.browser_launch_profile(),
            fingerprint_policy=self.browser_fingerprint_policy(),
        )
        return {
            "healthy": True,
            "ready_for_inference": False,
            "credential_patch": result.credential,
            "metadata": result.metadata,
        }

    async def keepalive(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await asyncio.to_thread(_keepalive_sync, payload, credential(payload))

    def routers(self) -> tuple[Any, ...]:
        from .glm_runtime import router

        return (router,)

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
        return BrowserLaunchProfile(humanize=True, camoufox_os="windows")

    def browser_fingerprint_policy(self) -> BrowserFingerprintPolicy:
        return BrowserFingerprintPolicy(
            variants=tuple(
                BrowserFingerprintVariant(
                    id=f"glm-windows-{width}x{height}",
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
    provider: GlmAutomationProvider,
    payload: dict[str, Any],
    mail: TempMailClient,
    mailbox: Mailbox,
    password: str,
    trace: RegistrationTrace,
) -> BrowserResult:
    attempts = max(1, min(8, settings().glm_registration_browser_attempts))
    for attempt in range(1, attempts + 1):
        try:
            return await asyncio.to_thread(
                run_browser_flow,
                lambda page, context, backend, proxy_url: _register_browser(
                    page,
                    context,
                    backend,
                    mail,
                    mailbox,
                    password,
                    trace,
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
        except RuntimeError as error:
            if attempt >= attempts or not _retryable_registration_challenge(trace, error):
                raise
    raise RuntimeError("GLM registration browser attempts were exhausted")


def _retryable_registration_challenge(
    trace: RegistrationTrace,
    error: Exception,
) -> bool:
    return trace.current == RegistrationStage.FORM_READY.value and str(error).startswith(
        "GLM Aliyun captcha"
    )


def _register_browser(
    page: Any,
    context: Any,
    backend: str,
    mail: TempMailClient,
    mailbox: Mailbox,
    password: str,
    trace: RegistrationTrace,
) -> BrowserResult:
    config = settings()
    trace.mark(RegistrationStage.BROWSER_LAUNCHED)
    page.goto(
        f"{config.glm_base_url.rstrip('/')}/auth?action=signup",
        wait_until="domcontentloaded",
        timeout=90_000,
    )
    page.wait_for_timeout(2_000 + random.randint(0, 1_500))
    trace.mark(RegistrationStage.FORM_READY)
    challenge = GlmAliyunChallenge.for_authentication()
    ticket = challenge.solve(page)
    trace.mark(RegistrationStage.CHALLENGE_CLEARED)
    display_name = "glm" + "".join(random.choice(string.ascii_lowercase) for _ in range(10))
    signup = _browser_auth_request(
        page,
        "/api/v1/auths/signup",
        {
            "name": display_name,
            "email": mailbox.address,
            "password": password,
            "profile_image_url": "/user.png",
            "sso_redirect": None,
            "captcha_verify_param": ticket,
        },
    )
    trace.mark(RegistrationStage.FORM_SUBMITTED)
    if not signup["ok"]:
        raise RuntimeError(
            f"GLM signup rejected status={signup['status']} detail={signup['detail']}"
        )
    trace.mark(RegistrationStage.UPSTREAM_ACCEPTED)
    activate_url = mail.wait_for_link_sync(mailbox, host_pattern=r"(?:chat\.)?z\.ai")
    trace.mark(RegistrationStage.OTP_RECEIVED)
    token, profile = _activate_from_link(page, activate_url, mailbox.address, password)
    signin_diagnostic = "activation_finish_signup"
    trace.mark(RegistrationStage.ACTIVATED)
    value = credential_from_context(context, page, password, mailbox.jwt)
    value.update(
        {
            "email": mailbox.address,
            "token": token,
            "user_id": profile["id"],
            "registration_backend": backend,
        }
    )
    trace.mark(RegistrationStage.CREDENTIAL_CAPTURED)
    return BrowserResult(
        external_id=profile["id"],
        email=mailbox.address,
        credential=value,
        metadata={
            **trace.metadata(),
            "captcha": challenge.last_diagnostic,
            "authentication": signin_diagnostic,
            "inference_probe_required": True,
        },
        ready_for_inference=False,
    )


def _reauthenticate_browser(
    page: Any,
    context: Any,
    backend: str,
    current: dict[str, Any],
) -> BrowserResult:
    config = settings()
    page.goto(config.glm_base_url, wait_until="domcontentloaded")
    page.wait_for_timeout(1_500)
    email = required(current, "email")
    password = required(current, "password")
    token, profile, diagnostic = _signin(page, email, password)
    value = credential_from_context(
        context,
        page,
        password,
        str(current.get("mail_jwt") or ""),
    )
    value.update(
        {
            "email": email,
            "token": token,
            "user_id": profile["id"],
            "registration_backend": backend,
        }
    )
    return BrowserResult(
        profile["id"],
        email,
        value,
        metadata={"authentication": diagnostic, "inference_probe_required": True},
        ready_for_inference=False,
    )


def _signin(page: Any, email: str, password: str) -> tuple[str, dict[str, str], str]:
    challenge = GlmAliyunChallenge.for_authentication()
    ticket = challenge.solve(page)
    result = _browser_auth_request(
        page,
        "/api/v1/auths/signin",
        {"email": email, "password": password, "captcha_verify_param": ticket},
    )
    if not result["ok"] or not result["token"]:
        raise RuntimeError(
            f"GLM sign-in rejected status={result['status']} detail={result['detail']}"
        )
    profile = _browser_profile(page, result["token"])
    return result["token"], profile, challenge.last_diagnostic


def _activate_from_link(
    page: Any,
    activate_url: str,
    expected_email: str,
    password: str,
) -> tuple[str, dict[str, str]]:
    username, email, token = _activation_parameters(activate_url, expected_email)
    verified = _browser_auth_request(
        page,
        "/api/v1/auths/verify_email",
        {"username": username, "email": email, "token": token},
    )
    if not verified["ok"]:
        raise RuntimeError(
            "GLM email verification rejected "
            f"status={verified['status']} detail={verified['detail']}"
        )
    finished = _browser_auth_request(
        page,
        "/api/v1/auths/finish_signup",
        {
            "username": username,
            "email": email,
            "token": token,
            "password": password,
            "profile_image_url": "/user.png",
            "sso_redirect": None,
        },
    )
    access_token = str(finished.get("token") or "")
    if not finished["ok"] or not access_token:
        raise RuntimeError(
            "GLM signup completion rejected "
            f"status={finished['status']} detail={finished['detail']}"
        )
    page.evaluate("token => localStorage.setItem('token', token)", access_token)
    return access_token, _browser_profile(page, access_token)


def _activation_parameters(activate_url: str, expected_email: str) -> tuple[str, str, str]:
    parsed = urlparse(activate_url)
    expected_host = (urlparse(settings().glm_base_url).hostname or "").lower()
    if parsed.scheme != "https" or (parsed.hostname or "").lower() != expected_host:
        raise RuntimeError("GLM activation link host is invalid")
    query = parse_qs(parsed.query, keep_blank_values=False)
    username = str((query.get("username") or [""])[0]).strip()
    email = str((query.get("email") or [""])[0]).strip()
    token = str((query.get("token") or [""])[0]).strip()
    if email.casefold() != expected_email.strip().casefold():
        raise RuntimeError("GLM activation link email does not match the mailbox")
    if not username or not token or len(token) > 4096:
        raise RuntimeError("GLM activation link is incomplete")
    return username, email, token


def _browser_profile(page: Any, token: str) -> dict[str, str]:
    result = page.evaluate(
        """async token => {
          const controller = new AbortController();
          const timeout = setTimeout(() => controller.abort(), 60000);
          try {
            const response = await fetch('/api/v1/auths/', {
              headers: {Authorization: `Bearer ${token}`},
              credentials: 'include',
              signal: controller.signal
            });
            let data = {};
            try { data = await response.json(); } catch (_) {}
            return {ok: response.ok, status: response.status, id: String(data.id || '')};
          } finally {
            clearTimeout(timeout);
          }
        }""",
        token,
    )
    if not result.get("ok") or not result.get("id"):
        raise RuntimeError(f"GLM profile probe failed status={result.get('status')}")
    return {"id": str(result["id"])}


def _browser_auth_request(page: Any, path: str, body: dict[str, Any]) -> dict[str, Any]:
    return page.evaluate(
        """async args => {
          const controller = new AbortController();
          const timeout = setTimeout(() => controller.abort(), 60000);
          const headers = {'Content-Type': 'application/json'};
          for (let index = 0; index < localStorage.length; index += 1) {
            const value = localStorage.getItem(localStorage.key(index));
            if (/^uid_[A-Za-z0-9]{7,}$/.test(value || '')) {
              headers['X-Device-ID'] = value;
              break;
            }
          }
          try {
            const response = await fetch(args.path, {
              method: 'POST', headers, credentials: 'include',
              body: JSON.stringify(args.body), signal: controller.signal
            });
            let data = {};
            try { data = await response.json(); } catch (_) {}
            return {
              ok: response.ok,
              status: response.status,
              detail: String(data.detail || '').replace(String(args.body.email || ''), '<email>').slice(0, 300),
              token: String(data.token || data.access_token || data.user?.token || ''),
              id: String(data.id || data.user?.id || '')
            };
          } finally {
            clearTimeout(timeout);
          }
        }""",
        {"path": path, "body": body},
    )


def _keepalive_sync(payload: dict[str, Any], current: dict[str, Any]) -> dict[str, Any]:
    config = settings()
    token = required(current, "token", "access_token")
    with (
        proxy_lease(check_url=config.glm_base_url, **proxy_parameters(payload)) as proxy_url,
        httpx.Client(proxy=proxy_url or None, timeout=60) as client,
    ):
        response = client.get(
            f"{config.glm_base_url.rstrip('/')}/api/v1/auths/",
            headers={"Authorization": f"Bearer {token}", "Accept": "application/json"},
        )
    if response.status_code in {401, 403}:
        return {"healthy": False, "auth_expired": True, "ready_for_inference": False}
    response.raise_for_status()
    profile = response.json()
    return {
        "healthy": bool(profile.get("id")),
        "auth_expired": not bool(profile.get("id")),
        "ready_for_inference": False,
        "inference_probe_required": True,
    }
