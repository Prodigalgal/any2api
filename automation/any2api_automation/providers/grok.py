import asyncio
import base64
import hashlib
import json
import re
import secrets
import time
import uuid
from datetime import UTC, datetime, timedelta
from typing import Any
from urllib.parse import parse_qs, urlencode, urlparse

import httpx

from ..captcha.turnstile import LocalTurnstileSolver
from ..config import settings as core_settings
from ..lifecycle.account import credential, prepare_registration, required
from ..lifecycle.browser import BrowserResult, credential_from_context, fill_first
from ..lifecycle.proxy import proxy_lease, proxy_parameters
from ..lifecycle.registration import RegistrationStage, RegistrationTrace
from .base import AutomationProvider, AutomationProviderManifest
from .grok_protocol import (
    ProtocolOAuthClient,
    XConsoleAuthClient,
    exchange_sso_for_token,
    exchange_sso_for_token_in_browser,
    extract_cookies_from_auth_client,
    same_session_register,
)
from .grok_protocol.registration_risk import inspect_registration_risk_page
from .grok_settings import settings


class GrokAutomationProvider(AutomationProvider):
    manifest = AutomationProviderManifest(
        id="grok",
        browser_backend="camoufox",
        fallback_backend="patchright",
        isolation="process",
        challenge_types=("turnstile",),
        operations=("register", "reauthenticate", "keepalive"),
    )

    async def register(self, payload: dict[str, Any]) -> dict[str, Any]:
        attempts = max(1, settings().grok_registration_attempts)
        failures: list[str] = []
        last_failure: RuntimeError | None = None
        for attempt in range(1, attempts + 1):
            trace = RegistrationTrace(self.manifest.id)
            try:
                mail, mailbox, password = await prepare_registration(payload)
                trace.mark(RegistrationStage.MAILBOX_CREATED)
                result = await asyncio.to_thread(
                    _register_same_session,
                    payload,
                    mail,
                    mailbox,
                    password,
                    trace,
                )
                response = result.response()
                response.setdefault("metadata", {})["registration_attempt"] = attempt
                return response
            except Exception as error:  # noqa: BLE001 - provider retry boundary
                last_failure = trace.failure(error)
                failures.append(f"attempt={attempt}: {last_failure}")
        raise RuntimeError(
            f"Grok registration exhausted {attempts} attempts: " + " | ".join(failures)
        ) from last_failure

    async def reauthenticate(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await asyncio.to_thread(_reauthenticate_sync, payload, credential(payload))

    async def keepalive(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await asyncio.to_thread(_keepalive_sync, payload, credential(payload))


def _reauthenticate_sync(payload: dict[str, Any], current: dict[str, Any]) -> dict[str, Any]:
    config = settings()
    with proxy_lease(check_url=config.grok_authorize_url, **proxy_parameters(payload)) as proxy_url:
        attempted: list[str] = []
        refresh_token = str(current.get("refresh_token") or "").strip()
        if refresh_token:
            attempted.append("refresh_token")
            refreshed = _refresh_oauth_token(payload, current, refresh_token, proxy_url)
            if refreshed is not None:
                refreshed.setdefault("refresh_token", refresh_token)
                return _reauthentication_success("refresh_token", refreshed, attempted)

        sso = _saved_sso(current)
        if sso:
            attempted.append("saved_sso")
            try:
                token = _exchange_sso_token(sso, proxy_url)
            except RuntimeError:
                token = {}
            if token.get("access_token"):
                return _reauthentication_success("saved_sso", token, attempted)

        email = str(current.get("email") or "").strip()
        password = str(current.get("password") or current.get("register_password") or "").strip()
        if not email or not password:
            return {
                "healthy": False,
                "auth_expired": True,
                "terminal": True,
                "error_class": "MissingReloginCredential",
                "recovery_stage": "password_relogin",
                "recovery_attempts": attempted,
            }

        attempted.append("password_relogin")
        try:
            new_sso, cookies = _password_relogin_sso(payload, email, password, proxy_url)
        except Exception as error:  # noqa: BLE001 - provider recovery boundary
            return _reauthentication_failure(
                "password_relogin", attempted, error, auth_expired=True
            )

        attempted.append("new_sso_oauth")
        sso_patch = {
            "sso": new_sso,
            "sso_cookie": new_sso,
            "cookies": cookies,
            "cookie": "; ".join(f"{name}={value}" for name, value in cookies.items()),
            "auth_stage": "registered_pending_auth",
        }
        try:
            token = _exchange_sso_token(new_sso, proxy_url)
        except Exception as error:  # noqa: BLE001 - preserve the newly recovered SSO
            return _reauthentication_failure(
                "new_sso_oauth",
                attempted,
                error,
                auth_expired=True,
                credential_patch=sso_patch,
                authorization_pending=True,
            )
        return _reauthentication_success(
            "password_relogin",
            {**sso_patch, **token},
            attempted,
        )


def _refresh_oauth_token(
    payload: dict[str, Any],
    current: dict[str, Any],
    refresh_token: str,
    proxy_url: str,
) -> dict[str, Any] | None:
    config = settings()
    with httpx.Client(timeout=60, proxy=proxy_url or None) as client:
        response = client.post(
            str(payload.get("token_url") or config.grok_token_url),
            data={
                "grant_type": "refresh_token",
                "refresh_token": refresh_token,
                "client_id": str(
                    current.get("oauth_client_id")
                    or payload.get("client_id")
                    or config.grok_oauth_client_id
                ),
            },
        )
    if response.is_success:
        value = response.json()
        return value if isinstance(value, dict) and value.get("access_token") else None
    if response.status_code not in {400, 401, 403}:
        response.raise_for_status()
    return None


def _saved_sso(current: dict[str, Any]) -> str:
    for key in ("sso", "sso_cookie", "sso_token"):
        value = str(current.get(key) or "").strip()
        if value:
            return re.sub(r"^sso(?:-rw)?\s*=\s*", "", value, flags=re.IGNORECASE)
    nested = current.get("cookies") or current.get("session_cookies")
    if isinstance(nested, dict):
        for key in ("sso", "sso-rw"):
            value = str(nested.get(key) or "").strip()
            if value:
                return value
    for key in ("cookie", "set_cookie", "set-cookie", "set_cookies"):
        value = str(current.get(key) or "")
        matched = re.search(r"(?:^|[;,\s])sso(?:-rw)?\s*=\s*([^;,\s]+)", value, re.IGNORECASE)
        if matched:
            return matched.group(1)
    return ""


def _exchange_sso_token(sso: str, proxy_url: str) -> dict[str, Any]:
    config = settings()
    return exchange_sso_for_token(
        sso,
        proxy=proxy_url,
        session=None,
        client_id=config.grok_oauth_client_id,
        scopes=config.grok_oauth_scopes,
        http_timeout=config.grok_oauth_http_timeout_seconds,
        poll_timeout=config.grok_oauth_poll_timeout_seconds,
        client_version=config.grok_client_version,
        client_surface=config.grok_oauth_client_surface,
        referrer=config.grok_oauth_referrer,
    )


def _password_relogin_sso(
    payload: dict[str, Any],
    email: str,
    password: str,
    proxy_url: str,
) -> tuple[str, dict[str, str]]:
    config = settings()
    signin_url = str(payload.get("signin_url") or config.grok_signin_url).strip()
    client = XConsoleAuthClient(
        debug=False,
        proxy=proxy_url or None,
        signup_url=signin_url,
    )
    try:
        client.visit_home()
        try:
            client.load_signup_page()
        except RuntimeError:
            pass
        sitekey = client._scrape_turnstile_sitekey(client._last_signup_html)
        if not sitekey:
            raise RuntimeError("Grok sign-in page did not expose a live Turnstile sitekey")
        solver_proxy = (
            proxy_url
            if bool(
                payload.get(
                    "turnstile_use_flow_proxy",
                    config.grok_turnstile_use_flow_proxy,
                )
            )
            else ""
        )
        with LocalTurnstileSolver(
            proxy_url=solver_proxy,
            headless=True,
            rounds=config.grok_turnstile_rounds,
            timeout_seconds=config.grok_turnstile_timeout_seconds,
        ) as solver:
            turnstile = solver.solve_turnstile(
                website_url=signin_url,
                website_key=sitekey,
            )
        sso = client.obtain_session_via_password(
            email=email,
            password=password,
            turnstile_token=turnstile,
            referer=signin_url,
            retries=4,
        )
        if not sso:
            raise RuntimeError("Grok password login returned no SSO token")
        cookies = {
            name: str(value)
            for name, value in extract_cookies_from_auth_client(client).items()
            if str(value).strip()
        }
        cookies.update({"sso": sso, "sso-rw": sso})
        return sso, cookies
    finally:
        client.close()


def _reauthentication_success(
    stage: str,
    token: dict[str, Any],
    attempted: list[str],
) -> dict[str, Any]:
    config = settings()
    patch = {
        **token,
        "auth_stage": "inference_ready",
        "oauth_client_id": config.grok_oauth_client_id,
    }
    return {
        "healthy": True,
        "auth_expired": False,
        "terminal": False,
        "recovery_stage": stage,
        "recovery_attempts": attempted,
        "credential_patch": patch,
        "credential_expires_at": _token_expiry(token),
    }


def _reauthentication_failure(
    stage: str,
    attempted: list[str],
    error: Exception,
    *,
    auth_expired: bool,
    credential_patch: dict[str, Any] | None = None,
    authorization_pending: bool = False,
) -> dict[str, Any]:
    return {
        "healthy": False,
        "auth_expired": auth_expired,
        "terminal": False,
        "authorization_pending": authorization_pending,
        "error_class": type(error).__name__,
        "message": str(error)[:300],
        "recovery_stage": stage,
        "recovery_attempts": attempted,
        "credential_patch": credential_patch,
    }


def _token_expiry(token: dict[str, Any]) -> str | None:
    expires_in = token.get("expires_in")
    if isinstance(expires_in, (int, float)) and expires_in > 0:
        return (datetime.now(UTC) + timedelta(seconds=float(expires_in))).isoformat()
    access_token = str(token.get("access_token") or token.get("key") or "")
    parts = access_token.split(".")
    if len(parts) < 2:
        return None
    try:
        segment = parts[1] + "=" * (-len(parts[1]) % 4)
        claims = json.loads(base64.urlsafe_b64decode(segment).decode())
        expires_at = float(claims["exp"])
    except (KeyError, TypeError, ValueError, json.JSONDecodeError):
        return None
    return datetime.fromtimestamp(expires_at, UTC).isoformat()


def _keepalive_sync(payload: dict[str, Any], current: dict[str, Any]) -> dict[str, Any]:
    config = settings()
    access_token = required(current, "access_token", "key", "token")
    base_url = str(payload.get("base_url") or config.grok_base_url).rstrip("/")
    with (
        proxy_lease(check_url=base_url, **proxy_parameters(payload)) as proxy_url,
        httpx.Client(timeout=60, proxy=proxy_url or None) as client,
    ):
        model = str(payload.get("model") or config.grok_keepalive_model)
        status = _probe_grok_inference(
            client,
            base_url,
            access_token,
            model,
        )
        if status not in {401, 403}:
            return {"healthy": True, "credential_patch": None}
        refresh_token = str(current.get("refresh_token") or "").strip()
        if not refresh_token:
            return {"healthy": False, "auth_expired": True, "credential_patch": None}
        patch = _refresh_oauth_token(payload, current, refresh_token, proxy_url)
        if patch is None:
            return {"healthy": False, "auth_expired": True, "credential_patch": None}
        if not patch.get("refresh_token"):
            patch["refresh_token"] = refresh_token
        refreshed_status = _probe_grok_inference(
            client,
            base_url,
            str(patch["access_token"]),
            model,
        )
        if refreshed_status in {401, 403}:
            return {"healthy": False, "auth_expired": True, "credential_patch": patch}
        return _reauthentication_success(
            "refresh_token",
            patch,
            ["inference_probe", "refresh_token"],
        )


def _probe_grok_inference(
    client: httpx.Client,
    base_url: str,
    access_token: str,
    model: str,
) -> int:
    config = settings()
    with client.stream(
        "POST",
        f"{base_url}/responses",
        headers={
            "Authorization": f"Bearer {access_token}",
            "Accept": "text/event-stream",
            "x-xai-token-auth": config.grok_token_auth,
            "x-grok-client-version": config.grok_client_version,
            "x-grok-client-identifier": config.grok_client_identifier,
        },
        json={
            "model": model,
            "stream": True,
            "store": False,
            "input": [
                {
                    "role": "user",
                    "content": [{"type": "input_text", "text": "Reply with OK."}],
                }
            ],
            "max_output_tokens": 1,
            "reasoning": {"effort": "low", "summary": "auto"},
        },
    ) as response:
        if response.status_code in {401, 403}:
            return response.status_code
        response.raise_for_status()
        completed = any(
            "response.completed" in line or line.strip() == "data: [DONE]"
            for line in response.iter_lines()
        )
        if not completed:
            raise RuntimeError("Grok inference probe ended without a completion event")
        return response.status_code


def _register_same_session(
    payload, mail, mailbox, password, trace: RegistrationTrace
) -> BrowserResult:
    config = settings()
    signup_url = str(payload.get("signup_url") or config.grok_signup_url).strip()
    with proxy_lease(check_url=signup_url, **proxy_parameters(payload)) as proxy_url:
        solver_proxy = (
            proxy_url
            if bool(
                payload.get(
                    "turnstile_use_flow_proxy",
                    config.grok_turnstile_use_flow_proxy,
                )
            )
            else ""
        )

        def solve_turnstile(sitekey: str) -> str:
            with LocalTurnstileSolver(
                proxy_url=solver_proxy,
                headless=True,
                rounds=config.grok_turnstile_rounds,
                timeout_seconds=config.grok_turnstile_timeout_seconds,
            ) as solver:
                return solver.solve_turnstile(
                    website_url=signup_url,
                    website_key=sitekey,
                )

        def mark_stage(value: str) -> None:
            trace.mark(RegistrationStage(value))

        def exchange_token(page, context, sso: str, sso_rw: str) -> dict[str, Any]:
            risk = inspect_registration_risk_page(
                page,
                timeout_ms=int(config.grok_oauth_http_timeout_seconds * 1000),
            )
            result: dict[str, Any] = {
                "registration_risk": risk.metadata(),
                "token": {},
                "oauth_error": None,
            }
            if risk.denied:
                result["oauth_error"] = "registration_risk_denied"
                return result
            try:
                result["token"] = exchange_sso_for_token_in_browser(
                    sso,
                    page=page,
                    context=context,
                    sso_rw=sso_rw,
                    proxy=proxy_url or "",
                    client_id=config.grok_oauth_client_id,
                    scopes=config.grok_oauth_scopes,
                    http_timeout=config.grok_oauth_http_timeout_seconds,
                    poll_timeout=config.grok_oauth_poll_timeout_seconds,
                    client_version=config.grok_client_version,
                    client_surface=config.grok_oauth_client_surface,
                    referrer=config.grok_oauth_referrer,
                )
            except RuntimeError as error:
                result["oauth_error"] = f"{type(error).__name__}:{str(error)[:180]}"
            return result

        registration = same_session_register(
            email=mailbox.address,
            password=password,
            fetch_code=lambda _email: _wait_grok_code(mail, mailbox, dashed=True),
            solve_turnstile=solve_turnstile,
            timeout_s=core_settings().registration_timeout_seconds,
            proxy=proxy_url or None,
            browser="camoufox",
            fp_os=str(payload.get("fingerprint_os") or "windows"),
            locale=str(payload.get("fingerprint_locale") or "ja-JP"),
            timezone_id=str(payload.get("fingerprint_timezone") or "Asia/Tokyo"),
            humanize=bool(payload.get("fingerprint_humanize", True)),
            timing=str(payload.get("registration_timing") or "fast"),
            on_stage=mark_stage,
            post_sso=exchange_token,
        )
        if not registration.get("ok") or not registration.get("sso"):
            raise RuntimeError(
                "Grok same-session registration failed "
                f"(error={str(registration.get('error') or 'unknown')[:500]}, "
                f"signup_status={registration.get('signup_status')}, "
                f"castle_len={registration.get('castle_len')})"
            )
        sso = str(registration["sso"])
        sso_rw = str(registration.get("sso_rw") or sso)
        post_sso = registration.get("post_sso") or {}
        token = post_sso.get("token") or {}
        registration_risk = post_sso.get("registration_risk") or {
            "registration_risk_status": "unknown"
        }
        ready_for_inference = bool(token.get("access_token"))
        oauth_error = str(post_sso.get("oauth_error") or "").strip()
        if not ready_for_inference and not oauth_error:
            oauth_error = "Grok OAuth exchange omitted access_token"
        cookies = {"sso": sso, "sso-rw": sso_rw}
        credential_value = {
            "email": mailbox.address,
            "password": password,
            "mail_jwt": mailbox.jwt,
            "sso": sso,
            "cookies": cookies,
            "cookie": "; ".join(f"{key}={value}" for key, value in cookies.items()),
            "oauth_client_id": config.grok_oauth_client_id,
            "auth_stage": ("inference_ready" if ready_for_inference else "registered_pending_auth"),
            **token,
        }
        trace.mark(RegistrationStage.CREDENTIAL_CAPTURED)
        return BrowserResult(
            mailbox.address,
            mailbox.address,
            credential_value,
            metadata={
                **trace.metadata(),
                "registration_backend": "camoufox_same_session",
                "dynamic_sitekey": True,
                "dynamic_server_action": True,
                "castle_method": registration.get("castle_method"),
                "castle_len": registration.get("castle_len"),
                "signup_status": registration.get("signup_status"),
                "browser_engine": registration.get("browser_engine"),
                "fingerprint": {
                    "os": registration.get("fp_os"),
                    "locale": registration.get("locale"),
                    "timezone": registration.get("timezone"),
                    "viewport": registration.get("viewport"),
                },
                "oauth_source": "device_flow_dynamic_consent",
                "oauth_error": oauth_error or None,
                "account_registered": True,
                "inference_ready": ready_for_inference,
                **registration_risk,
            },
            ready_for_inference=ready_for_inference,
        )


def _register_protocol(payload, mail, mailbox, password, trace: RegistrationTrace) -> BrowserResult:
    config = settings()
    signup_url = str(payload.get("signup_url") or config.grok_signup_url).strip()
    with proxy_lease(
        check_url=signup_url,
        **proxy_parameters(payload),
    ) as proxy_url:
        client = XConsoleAuthClient(
            debug=False,
            proxy=proxy_url or None,
            signup_url=signup_url,
        )
        try:
            solver_proxy = (
                proxy_url
                if bool(
                    payload.get(
                        "turnstile_use_flow_proxy",
                        config.grok_turnstile_use_flow_proxy,
                    )
                )
                else ""
            )
            with LocalTurnstileSolver(
                proxy_url=solver_proxy,
                headless=True,
                rounds=config.grok_turnstile_rounds,
                timeout_seconds=config.grok_turnstile_timeout_seconds,
            ) as solver:
                trace.mark(RegistrationStage.BROWSER_LAUNCHED)
                client.visit_home()
                client.load_signup_page()
                sitekey = client._scrape_turnstile_sitekey(client._last_signup_html)
                if not sitekey:
                    raise RuntimeError("Grok signup page did not expose a live Turnstile sitekey")
                trace.mark(RegistrationStage.FORM_READY)

                turnstile = solver.solve_turnstile(
                    website_url=signup_url,
                    website_key=sitekey,
                )
                trace.mark(RegistrationStage.CHALLENGE_CLEARED)
                client.validate_password(mailbox.address, password)
                sent = client.create_email_validation_code(mailbox.address)
                if not sent.ok:
                    raise RuntimeError(
                        "Grok rejected email verification request "
                        f"(http={sent.http_status}, grpc={sent.grpc_status})"
                    )
                trace.mark(RegistrationStage.FORM_SUBMITTED)
                trace.mark(RegistrationStage.UPSTREAM_ACCEPTED)
                trace.mark(RegistrationStage.OTP_UI_VISIBLE)
                code = _wait_grok_code(mail, mailbox)
                trace.mark(RegistrationStage.OTP_RECEIVED)

                result = None
                signup_error = ""
                create_attempt = 0
                for create_attempt in range(1, max(1, config.grok_registration_attempts) + 1):
                    if create_attempt > 1:
                        time.sleep(2.5 + create_attempt * 0.4)
                        turnstile = solver.solve_turnstile(
                            website_url=signup_url,
                            website_key=sitekey,
                        )
                        if _grok_error_needs_new_code(signup_error):
                            sent = client.create_email_validation_code(mailbox.address)
                            if not sent.ok:
                                raise RuntimeError(
                                    "Grok rejected refreshed email verification code"
                                )
                            code = _wait_grok_code(mail, mailbox)
                    verified = client.verify_email_validation_code(mailbox.address, code)
                    if not verified.ok:
                        signup_error = "email_invalid_validation_code"
                        continue
                    result = client.create_account(
                        email=mailbox.address,
                        given_name="User",
                        family_name="Grok",
                        password=password,
                        email_validation_code=code,
                        turnstile_token=turnstile,
                        castle_request_token="",
                        conversion_id=str(uuid.uuid4()),
                    )
                    signup_error = str(client.extract_signup_error(result.rsc_body) or "")
                    if result.http_status == 200 and not signup_error:
                        break
                    if not _grok_error_recoverable(signup_error, result.http_status):
                        break
                if result is None or result.http_status != 200 or signup_error:
                    raise RuntimeError(
                        "Grok account creation failed "
                        f"(http={getattr(result, 'http_status', 0)}, "
                        f"error={signup_error or 'transport'})"
                    )
                trace.mark(RegistrationStage.ACTIVATED)

                sso = client.fetch_sso_token(save=False, retries=5)
                session_client = client
                sso_source = "signup_session"
                reauth_client = None
                try:
                    if not sso:
                        signin_url = "https://accounts.x.ai/sign-in?redirect=grok-com"
                        reauth_client = XConsoleAuthClient(
                            debug=False,
                            proxy=proxy_url or None,
                            signup_url=signin_url,
                        )
                        reauth_client.visit_home()
                        try:
                            reauth_client.load_signup_page()
                        except RuntimeError:
                            pass
                        signin_sitekey = reauth_client._scrape_turnstile_sitekey(
                            reauth_client._last_signup_html
                        )
                        if not signin_sitekey:
                            raise RuntimeError(
                                "Grok sign-in page did not expose a live Turnstile sitekey"
                            )
                        signin_token = solver.solve_turnstile(
                            website_url=signin_url,
                            website_key=signin_sitekey,
                        )
                        sso = reauth_client.obtain_session_via_password(
                            email=mailbox.address,
                            password=password,
                            turnstile_token=signin_token,
                            referer=signin_url,
                            retries=4,
                        )
                        session_client = reauth_client
                        sso_source = "password_session_fallback"
                    if not sso:
                        raise RuntimeError(
                            "Grok account was created but no SSO session could be extracted"
                        )
                    cookies = extract_cookies_from_auth_client(session_client)
                    cookies.update({"sso": sso, "sso-rw": sso})

                    time.sleep(max(0.0, config.grok_oauth_settle_seconds))
                    oauth_source = "device_flow"
                    oauth_error = ""
                    token: dict[str, Any] = {}
                    try:
                        token = exchange_sso_for_token(
                            sso,
                            proxy=proxy_url,
                            session=None,
                            client_id=config.grok_oauth_client_id,
                            scopes=config.grok_oauth_scopes,
                            http_timeout=config.grok_oauth_http_timeout_seconds,
                            poll_timeout=config.grok_oauth_poll_timeout_seconds,
                        )
                    except RuntimeError as device_error:
                        oauth_source = "consent_protocol"
                        oauth_client = ProtocolOAuthClient(
                            proxy=proxy_url,
                            debug=False,
                            turnstile_premium=False,
                        )
                        oauth_client.solver = solver
                        session = getattr(session_client._t, "_session", None)
                        if session is not None:
                            oauth_client._s = session
                        try:
                            oauth = oauth_client.login(
                                mailbox.address,
                                password,
                                client_id=config.grok_oauth_client_id,
                                scopes=config.grok_oauth_scopes.split(),
                                proxy=proxy_url,
                                session_cookies=cookies,
                            )
                        except Exception as consent_error:  # noqa: BLE001 - keep SSO-only account pending
                            oauth_error = (
                                f"device_flow:{type(device_error).__name__}:"
                                f"{str(device_error)[:180]}; "
                                f"consent_protocol:{type(consent_error).__name__}:"
                                f"{str(consent_error)[:180]}"
                            )
                        else:
                            token = oauth.token
                finally:
                    if reauth_client is not None:
                        reauth_client.close()

                ready_for_inference = bool(token.get("access_token"))
                auth_stage = "inference_ready" if ready_for_inference else "registered_pending_auth"
                if not ready_for_inference and not oauth_error:
                    oauth_error = "Grok OAuth exchange omitted access_token"
                credential_value = {
                    "email": mailbox.address,
                    "password": password,
                    "mail_jwt": mailbox.jwt,
                    "sso": sso,
                    "cookies": cookies,
                    "cookie": "; ".join(f"{key}={value}" for key, value in cookies.items()),
                    "oauth_client_id": config.grok_oauth_client_id,
                    "auth_stage": auth_stage,
                    **token,
                }
                trace.mark(RegistrationStage.CREDENTIAL_CAPTURED)
                return BrowserResult(
                    mailbox.address,
                    mailbox.address,
                    credential_value,
                    metadata={
                        **trace.metadata(),
                        "registration_backend": "curl_cffi+camoufox",
                        "dynamic_sitekey": True,
                        "dynamic_server_action": True,
                        "create_attempts": create_attempt,
                        "account_registered": True,
                        "build_authorized": ready_for_inference,
                        "inference_ready": ready_for_inference,
                        "sso_source": sso_source,
                        "oauth_source": oauth_source,
                        "oauth_error": oauth_error or None,
                    },
                    ready_for_inference=ready_for_inference,
                )
        finally:
            client.close()


def _wait_grok_code(mail, mailbox, *, dashed: bool = False) -> str:
    value = mail.wait_for_code_sync(
        mailbox,
        pattern=(
            r"(?<![A-Z0-9])([A-Z0-9]{3}-[A-Z0-9]{3})(?![A-Z0-9])"
            r"|(?:code|otp|verification|verify)[^A-Z0-9]{0,40}([A-Z0-9]{6})(?![A-Z0-9])"
        ),
    )
    code = str(value).strip().upper().replace("-", "").replace(" ", "")
    if len(code) != 6 or not code.isalnum():
        raise RuntimeError("Grok mailbox returned an invalid verification code shape")
    return f"{code[:3]}-{code[3:]}" if dashed else code


def _grok_error_needs_new_code(error: str) -> bool:
    lowered = error.lower()
    return any(marker in lowered for marker in ("invalid-validation-code", "expired", "email"))


def _grok_error_recoverable(error: str, status: int) -> bool:
    if status in {403, 408, 409, 425, 429} or status >= 500:
        return True
    lowered = error.lower()
    return any(
        marker in lowered
        for marker in ("turnstile", "captcha", "rate_limited", "invalid-validation-code")
    )


def _register_browser(page, context, backend, proxy_url, mail, mailbox, password) -> BrowserResult:
    page.goto(settings().grok_signup_url, wait_until="domcontentloaded")
    fill_first(page, ('input[type="email"]', 'input[name="email"]'), mailbox.address)
    submit = page.locator('button[type="submit"]').first
    password_field = page.locator('input[type="password"], input[name="password"]').first
    if password_field.count() and password_field.is_visible():
        password_field.fill(password)
    submit.click()
    code = mail.wait_for_code_sync(
        mailbox,
        pattern=r"(?<![A-Z0-9])([A-Z0-9]{3}-?[A-Z0-9]{3})(?![A-Z0-9])",
    )
    fields = page.locator('input[autocomplete="one-time-code"], input[maxlength="1"]')
    if fields.count() == 1:
        fields.first.fill(code.replace("-", ""))
    else:
        for index, char in enumerate(code.replace("-", "")):
            fields.nth(index).fill(char)
    submit.click()
    page.wait_for_timeout(900)
    password_field = page.locator('input[type="password"], input[name="password"]').first
    if password_field.count() and password_field.is_visible():
        password_field.fill(password)
        confirmations = page.locator('input[type="password"]')
        if confirmations.count() > 1:
            confirmations.nth(1).fill(password)
        submit.click()
    page.wait_for_timeout(2200)
    return _browser_result(
        page, context, backend, proxy_url, mailbox.address, password, mailbox.jwt
    )


def _reauth_browser(page, context, backend, proxy_url, current) -> BrowserResult:
    return _browser_result(
        page,
        context,
        backend,
        proxy_url,
        required(current, "email"),
        required(current, "password"),
        str(current.get("mail_jwt") or ""),
    )


def _browser_result(page, context, backend, proxy_url, email, password, mail_jwt) -> BrowserResult:
    value = credential_from_context(context, page, password, mail_jwt)
    value.update({"email": email, "registration_backend": backend})
    value.update(_oauth_token(page, email, password, proxy_url))
    return BrowserResult(email, email, value)


def _oauth_token(page, email: str, password: str, proxy_url: str = "") -> dict[str, Any]:
    config = settings()
    verifier = base64.urlsafe_b64encode(secrets.token_bytes(48)).decode().rstrip("=")
    challenge = (
        base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
    )
    state = secrets.token_hex(16)
    params = {
        "client_id": config.grok_oauth_client_id,
        "code_challenge": challenge,
        "code_challenge_method": "S256",
        "nonce": secrets.token_hex(16),
        "redirect_uri": config.grok_oauth_redirect_uri,
        "response_type": "code",
        "scope": config.grok_oauth_scopes,
        "state": state,
    }
    page.goto(f"{config.grok_authorize_url}?{urlencode(params)}", wait_until="domcontentloaded")
    email_field = page.locator('input[type="email"], input[name="email"]').first
    if email_field.count() and email_field.is_visible():
        email_field.fill(email)
        password_field = page.locator('input[type="password"], input[name="password"]').first
        if password_field.count() and password_field.is_visible():
            password_field.fill(password)
        page.locator('button[type="submit"]').first.click()
    deadline = time.monotonic() + 90
    code = ""
    while time.monotonic() < deadline:
        parsed = urlparse(page.url)
        query = parse_qs(parsed.query)
        if query.get("state", [""])[0] == state and query.get("code"):
            code = query["code"][0]
            break
        consent = page.get_by_role(
            "button", name=re.compile(r"Authorize|Allow|Continue", re.IGNORECASE)
        ).first
        if consent.count() and consent.is_visible():
            consent.click()
        page.wait_for_timeout(500)
    if not code:
        raise RuntimeError("Grok OAuth authorization did not return a code")
    with httpx.Client(timeout=60, proxy=proxy_url or None) as client:
        response = client.post(
            config.grok_token_url,
            data={
                "grant_type": "authorization_code",
                "client_id": config.grok_oauth_client_id,
                "code": code,
                "redirect_uri": config.grok_oauth_redirect_uri,
                "code_verifier": verifier,
            },
        )
        response.raise_for_status()
        token = response.json()
    if not token.get("access_token"):
        raise RuntimeError("Grok OAuth token response omitted access_token")
    token["oauth_client_id"] = config.grok_oauth_client_id
    return token
