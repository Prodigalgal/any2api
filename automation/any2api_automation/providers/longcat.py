import asyncio
import re
import time
from typing import Any
from urllib.parse import urlencode

import httpx

from ..config import settings as core_settings
from ..lifecycle.account import credential, prepare_registration, required
from ..lifecycle.browser import (
    BrowserContextProfile,
    BrowserLaunchProfile,
    BrowserResult,
    credential_from_context,
    first_visible,
    run_browser_flow,
)
from ..lifecycle.mail import Mailbox, TempMailClient
from ..lifecycle.proxy import proxy_lease, proxy_parameters
from ..lifecycle.registration import RegistrationStage, RegistrationTrace
from .base import AutomationProvider, AutomationProviderManifest
from .longcat_challenge import solve_yoda_if_present, yoda_visible
from .longcat_settings import settings


class LongcatAutomationProvider(AutomationProvider):
    manifest = AutomationProviderManifest(
        id="longcat",
        browser_backend="patchright",
        fallback_backend="camoufox",
        isolation="process",
        challenge_types=("slider", "tap", "dots"),
        operations=("register", "reauthenticate", "keepalive"),
    )

    async def register(self, payload: dict[str, Any]) -> dict[str, Any]:
        last_failure: RuntimeError | None = None
        failures: list[str] = []
        attempts = max(1, settings().longcat_registration_attempts)
        for attempt in range(1, attempts + 1):
            trace = RegistrationTrace(self.manifest.id)
            try:
                mail, mailbox, password = await prepare_registration(payload)
                trace.mark(RegistrationStage.MAILBOX_CREATED)
                flow_payload = {**payload}
                flow_payload.setdefault("proxy_check_url", _login_url())
                result = await asyncio.to_thread(
                    run_browser_flow,
                    lambda page, context, backend, proxy_url, _mail=mail, _mailbox=mailbox, _password=password, _trace=trace: (
                        _email_browser_flow(
                            page,
                            context,
                            backend,
                            _mail,
                            _mailbox,
                            _password,
                            _trace,
                        )
                    ),
                    preferred=self.manifest.browser_backend,
                    fallback=self.manifest.fallback_backend,
                    payload=flow_payload,
                    context_profile=self.browser_context_profile(),
                    launch_profile=self.browser_launch_profile(),
                )
                response = result.response()
                response.setdefault("metadata", {})["browser_attempt"] = attempt
                return response
            except Exception as error:  # noqa: BLE001 - provider retry boundary
                last_failure = trace.failure(error)
                failures.append(f"attempt={attempt}: {last_failure}")
        raise RuntimeError(
            f"LongCat registration exhausted {attempts} browser attempts: " + " | ".join(failures)
        ) from last_failure

    async def reauthenticate(self, payload: dict[str, Any]) -> dict[str, Any]:
        current = credential(payload)
        email = required(current, "email")
        password = required(current, "password")
        mail = TempMailClient()
        mailbox = Mailbox(email, required(current, "mail_jwt"))
        trace = RegistrationTrace(self.manifest.id)
        trace.mark(RegistrationStage.MAILBOX_CREATED)
        flow_payload = {**payload}
        flow_payload.setdefault("proxy_check_url", _login_url())
        result = await asyncio.to_thread(
            run_browser_flow,
            lambda page, context, backend, proxy_url: _email_browser_flow(
                page, context, backend, mail, mailbox, password, trace
            ),
            preferred=self.manifest.browser_backend,
            fallback=self.manifest.fallback_backend,
            payload=flow_payload,
            context_profile=self.browser_context_profile(),
            launch_profile=self.browser_launch_profile(),
        )
        return {"healthy": True, "credential_patch": result.credential}

    async def keepalive(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await asyncio.to_thread(_keepalive_sync, payload, credential(payload))

    def browser_context_profile(self) -> BrowserContextProfile:
        return BrowserContextProfile(
            locale="en-US",
            timezone_id="Asia/Hong_Kong",
            viewport_width=1280,
            viewport_height=900,
            accept_language="en-US,en;q=0.9",
            color_scheme="light",
            patchright_user_agent=(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                "AppleWebKit/537.36 (KHTML, like Gecko) "
                "Chrome/131.0.0.0 Safari/537.36"
            ),
        )

    def browser_launch_profile(self) -> BrowserLaunchProfile:
        return BrowserLaunchProfile(
            headless=True,
            camoufox_os="windows",
            window_width=1280,
            window_height=900,
            block_webrtc=True,
            firefox_user_prefs=(("intl.accept_languages", "en-US, en"),),
            patchright_args=(
                "--disable-blink-features=AutomationControlled",
                "--no-sandbox",
                "--disable-setuid-sandbox",
                "--disable-dev-shm-usage",
                "--disable-infobars",
                "--window-size=1280,900",
            ),
            patchright_ignore_default_args=("--enable-automation",),
            launch_timeout_ms=120_000,
        )


def _keepalive_sync(payload: dict[str, Any], current: dict[str, Any]) -> dict[str, Any]:
    config = settings()
    cookie = _cookie(current)
    base_url = str(payload.get("base_url") or config.longcat_base_url).rstrip("/")
    with (
        proxy_lease(check_url=base_url, **proxy_parameters(payload)) as proxy_url,
        httpx.Client(timeout=60, proxy=proxy_url or None) as client,
    ):
        response = client.post(
            f"{base_url}/api/v1/session-create",
            headers={
                "Accept": "application/json",
                "Cookie": cookie,
                "Origin": base_url,
                "Referer": f"{base_url}/t",
                "m-appkey": config.longcat_app_key,
                "x-client-language": config.longcat_language,
                "x-requested-with": config.longcat_requested_with,
                "User-Agent": core_settings().provider_user_agent,
            },
            json={
                "model": "",
                "agentId": str(payload.get("agent_id") or config.longcat_keepalive_agent_id),
            },
        )
    if response.status_code in {401, 403}:
        return {"healthy": False, "auth_expired": True}
    response.raise_for_status()
    body = response.json()
    healthy = body.get("code") == 0 and bool((body.get("data") or {}).get("conversationId"))
    return {"healthy": healthy, "auth_expired": not healthy}


def _cookie(credential: dict[str, Any]) -> str:
    raw = str(credential.get("cookie") or "").strip()
    if raw:
        return raw
    aliases = (
        ("passport_token_key", "passport_token"),
        ("_lxsdk_cuid", "lxsdk_cuid"),
        ("_lxsdk_s", "lxsdk_s"),
    )
    values: list[str] = []
    for cookie_name, alias in aliases:
        value = str(credential.get(cookie_name) or credential.get(alias) or "").strip()
        if value:
            values.append(f"{cookie_name}={value}")
    if not any(value.startswith("passport_token_key=") for value in values):
        raise ValueError("credential requires passport_token_key")
    return "; ".join(values)


def _email_browser_flow(
    page, context, backend, mail, mailbox, password, trace: RegistrationTrace
) -> BrowserResult:
    config = settings()
    trace.mark(RegistrationStage.BROWSER_LAUNCHED)
    network = {
        "risk_ok": False,
        "apply_ok": False,
        "apply_serial": "",
        "forbidden": False,
    }
    challenge_diagnostics: list[str] = []
    _attach_network_watch(page, network)
    page.goto(
        _login_url(),
        wait_until="domcontentloaded",
        timeout=config.longcat_navigation_timeout_ms,
    )
    page.wait_for_timeout(config.longcat_h5guard_wait_ms)
    try:
        page.wait_for_function(
            "() => window.H5guard || window.TokenStandardization || "
            "window.__TOKEN_STANDARD_INTERCEPTOR__",
            timeout=30_000,
        )
    except Exception:  # noqa: BLE001,S110 - H5Guard may remain internal to page bundle
        pass
    _switch_to_email(page)
    _fill_email(page, mailbox.address)
    trace.mark(RegistrationStage.FORM_READY)

    otp_selector = (
        'input[inputmode="numeric"], .verify-code-input input, '
        '.pc-login-verify-code-container input, input[maxlength="6"], input[maxlength="1"]'
    )
    for _ in range(max(1, config.longcat_submit_attempts)):
        _click_continue(page)
        trace.mark(RegistrationStage.FORM_SUBMITTED)
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            if network["apply_ok"] or _visible(page, otp_selector):
                break
            if yoda_visible(page):
                break
            page.wait_for_timeout(500)
        challenge = solve_yoda_if_present(page)
        if challenge.present:
            challenge_diagnostics.append(challenge.diagnostic)
            trace.mark(RegistrationStage.CHALLENGE_CLEARED)
            page.wait_for_timeout(2_500)
            if not _visible(page, otp_selector):
                _click_continue(page)
                page.wait_for_timeout(config.longcat_after_action_ms)
        if _visible(page, otp_selector):
            break
        if network["apply_ok"]:
            trace.mark(RegistrationStage.UPSTREAM_ACCEPTED)
            page.wait_for_timeout(config.longcat_after_action_ms)
            if _visible(page, otp_selector):
                break
    try:
        page.locator(otp_selector).first.wait_for(
            state="visible", timeout=config.longcat_otp_ui_timeout_ms
        )
    except Exception as error:
        raise RuntimeError(
            "LongCat did not present the email verification form "
            f"(risk={network['risk_ok']}, apply={network['apply_ok']}, "
            f"serial={bool(network['apply_serial'])}, "
            f"forbidden={network['forbidden']})"
        ) from error

    trace.mark(RegistrationStage.UPSTREAM_ACCEPTED)
    trace.mark(RegistrationStage.OTP_UI_VISIBLE)
    code = mail.wait_for_code_sync(
        mailbox,
        pattern=(
            r"(?:verification code|code is|code:|security)[^\d]{0,40}(\d{4,8})"
            r"|(?<![A-Za-z0-9])(\d{6})(?![A-Za-z0-9])"
        ),
    )
    trace.mark(RegistrationStage.OTP_RECEIVED)
    _enter_longcat_code(page, code)
    _click_continue(page)
    page.wait_for_timeout(config.longcat_after_action_ms)
    final_challenge = solve_yoda_if_present(page)
    if final_challenge.present:
        challenge_diagnostics.append(final_challenge.diagnostic)
    password_field = page.locator('input[type="password"]').first
    if password_field.count() and password_field.is_visible():
        password_field.fill(password)
        fields = page.locator('input[type="password"]')
        if fields.count() > 1:
            fields.nth(1).fill(password)
        _click_continue(page)
        page.wait_for_timeout(config.longcat_after_action_ms)
    page.wait_for_timeout(2500)
    if not page.url.startswith(config.longcat_base_url):
        page.goto(
            config.longcat_base_url,
            wait_until="domcontentloaded",
            timeout=config.longcat_navigation_timeout_ms,
        )
    page.wait_for_timeout(2000)
    value = credential_from_context(context, page, password, mailbox.jwt)
    value.update({"email": mailbox.address, "registration_backend": backend})
    cookies = value.get("cookies") or {}
    token = cookies.get("passport_token_key")
    if not token:
        page.goto(
            f"{config.longcat_base_url.rstrip('/')}/t",
            wait_until="domcontentloaded",
            timeout=config.longcat_navigation_timeout_ms,
        )
        page.wait_for_timeout(1800)
        value = credential_from_context(context, page, password, mailbox.jwt)
        value.update({"email": mailbox.address, "registration_backend": backend})
        cookies = value.get("cookies") or {}
        token = cookies.get("passport_token_key")
    if not token:
        raise RuntimeError("LongCat login completed without passport_token_key")
    value.update(
        {
            "passport_token_key": token,
            "lxsdk_cuid": cookies.get("_lxsdk_cuid", ""),
            "lxsdk_s": cookies.get("_lxsdk_s", ""),
        }
    )
    trace.mark(RegistrationStage.ACTIVATED)
    trace.mark(RegistrationStage.CREDENTIAL_CAPTURED)
    return BrowserResult(
        mailbox.address,
        mailbox.address,
        value,
        metadata={
            **trace.metadata(),
            "challenges": challenge_diagnostics,
            "upstream": {
                "risk_accepted": bool(network["risk_ok"]),
                "apply_accepted": bool(network["apply_ok"]),
                "serial_present": bool(network["apply_serial"]),
                "forbidden": bool(network["forbidden"]),
            },
        },
    )


def _attach_network_watch(page, state: dict[str, bool]) -> None:
    def observe(response) -> None:
        try:
            url = response.url
            if not re.search(r"mykeeta\.com/api/", url, re.IGNORECASE):
                return
            if not re.search(
                r"emaillogin|emailsignup|userrisk|mobilelogin|yoda|risk",
                url,
                re.IGNORECASE,
            ):
                return
            status = response.status
            if "userriskcheck" in url.lower() and 200 <= status < 300:
                state["risk_ok"] = True
            if re.search(r"emailloginapply|emailsignupapply", url, re.IGNORECASE) and (
                200 <= status < 300
            ):
                serial = _response_serial(response)
                if serial:
                    state["apply_serial"] = serial
                    state["apply_ok"] = True
            if status == 403:
                state["forbidden"] = True
        except Exception:  # noqa: BLE001 - response bodies can disappear during navigation
            return

    page.on("response", observe)


def _response_serial(response) -> str:
    try:
        payload = response.json()
    except Exception:  # noqa: BLE001 - non-JSON responses are not application success
        return ""

    def find(value: Any) -> str:
        if isinstance(value, dict):
            for key, nested in value.items():
                if str(key).lower() in {"serialnumber", "serial_number"} and nested:
                    return str(nested)
            for nested in value.values():
                result = find(nested)
                if result:
                    return result
        elif isinstance(value, list):
            for nested in value:
                result = find(nested)
                if result:
                    return result
        return ""

    return find(payload)


def _email_input(page):
    return page.locator(
        'input[placeholder*="Email" i]:not([type="tel"]):not(.oversea-mobile-input), '
        'input[type="email"], '
        'input.oversea-input-container:not(.oversea-mobile-input):not([type="tel"])'
    ).first


def _switch_to_email(page) -> None:
    if _email_input(page).is_visible():
        return
    switched = page.evaluate(
        """() => {
          const el = document.querySelector('span.change-signin-text') ||
            [...document.querySelectorAll('span,div,a,button')].find(
              n => /continue with email/i.test((n.textContent || '').trim()));
          if (!el) return false;
          el.dispatchEvent(new MouseEvent('click', {bubbles: true, cancelable: true}));
          return true;
        }"""
    )
    if switched:
        page.wait_for_timeout(1800)
    if not _email_input(page).is_visible():
        action = first_visible(
            page,
            ("span.change-signin-text", ".change-signin-text", "text=Continue with email"),
            timeout_ms=3_000,
        )
        if action is not None:
            action.click(force=True)
            page.wait_for_timeout(1500)
    if not _email_input(page).is_visible():
        raise RuntimeError("LongCat email login mode is unavailable")


def _fill_email(page, email: str) -> None:
    filled = page.evaluate(
        """value => {
          const el = [...document.querySelectorAll('input')].find(input => {
            const text = `${input.placeholder || ''} ${input.className || ''}`.toLowerCase();
            return input.type !== 'tel' && !/mobile|phone/.test(text) &&
              (input.type === 'email' || text.includes('email'));
          });
          if (!el) return false;
          const setter = Object.getOwnPropertyDescriptor(
            HTMLInputElement.prototype, 'value')?.set;
          el.focus();
          setter ? setter.call(el, value) : (el.value = value);
          el.dispatchEvent(new Event('input', {bubbles: true}));
          el.dispatchEvent(new Event('change', {bubbles: true}));
          return el.value.includes('@');
        }""",
        email,
    )
    if not filled:
        field = _email_input(page)
        field.wait_for(state="visible", timeout=8_000)
        field.fill(email)


def _click_continue(page) -> None:
    clicked = page.evaluate(
        """() => {
          const el = document.querySelector('div.submit-btn') ||
            [...document.querySelectorAll('div,button,span')].find(
              n => (n.innerText || '').trim() === 'Continue' &&
                (n.children?.length || 0) <= 1);
          if (!el) return false;
          el.dispatchEvent(new MouseEvent('click', {bubbles: true, cancelable: true}));
          return true;
        }"""
    )
    if clicked:
        return
    action = first_visible(
        page,
        ("div.submit-btn", 'button[type="submit"]', 'button:has-text("Continue")'),
        timeout_ms=5_000,
    )
    if action is None:
        raise RuntimeError("LongCat continue action is unavailable")
    action.click(force=True)


def _enter_longcat_code(page, code: str) -> None:
    for selector in (
        'input[inputmode="numeric"]',
        ".verify-code-input input",
        ".pc-login-verify-code-container input",
        'input[maxlength="6"]',
        'input[type="tel"]',
    ):
        field = page.locator(selector).first
        try:
            if field.count() and field.is_visible():
                field.click()
                field.fill(str(code))
                return
        except Exception:  # noqa: BLE001,S112 - mutually exclusive OTP widgets
            continue
    cells = page.locator('input[maxlength="1"]')
    if cells.count() >= 4:
        for index, character in enumerate(str(code)[: cells.count()]):
            cells.nth(index).fill(character)
        return
    raise RuntimeError("LongCat verification code fields are unavailable")


def _visible(page, selector: str) -> bool:
    try:
        return page.locator(selector).first.is_visible()
    except Exception:  # noqa: BLE001 - page can change between state probes
        return False


def _login_url() -> str:
    config = settings()
    callback = f"{config.longcat_base_url.rstrip('/')}/api/v1/user-loginV3?" + urlencode(
        {
            "url": config.longcat_base_url.rstrip("/") + "/",
        }
    )
    query = urlencode(
        {
            "locale": config.longcat_locale,
            "region": config.longcat_region,
            "joinkey": config.longcat_join_key,
            "token_id": config.longcat_token_id,
            "service": config.longcat_service,
            "risk_cost_id": config.longcat_risk_cost_id,
            "theme": config.longcat_theme,
            "cityId": config.longcat_city_id,
            "backurl": callback,
        }
    )
    return f"{config.longcat_passport_url.rstrip('/')}/pc/login?{query}"
