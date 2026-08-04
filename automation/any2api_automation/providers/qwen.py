import asyncio
import hashlib
import random
import secrets
from typing import Any

import httpx

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
from .base import AutomationProvider, AutomationProviderManifest
from .qwen_challenge import QwenSignupChallenge, pace
from .qwen_settings import settings


class QwenAutomationProvider(AutomationProvider):
    manifest = AutomationProviderManifest(
        id="qwen",
        browser_backend="camoufox",
        fallback_backend="patchright",
        isolation="process",
        challenge_types=("slider",),
        operations=("register", "reauthenticate", "keepalive"),
        realtime=True,
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
                flow_payload.setdefault(
                    "proxy_check_url", f"{settings().qwen_base_url.rstrip('/')}/auth?mode=register"
                )
                result = await asyncio.to_thread(
                    run_browser_flow,
                    lambda page, context, backend, proxy_url, _trace=trace: _register_browser(
                        page,
                        context,
                        backend,
                        proxy_url,
                        mail,
                        mailbox,
                        password,
                        _trace,
                    ),
                    preferred=self.manifest.browser_backend,
                    fallback=self.manifest.fallback_backend,
                    payload=flow_payload,
                    context_profile=self.browser_context_profile(),
                    launch_profile=self.browser_launch_profile(),
                    fingerprint_policy=self.browser_fingerprint_policy(),
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
            self.browser_context_profile(),
            self.browser_fingerprint_policy(),
        )

    async def keepalive(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await asyncio.to_thread(
            _keepalive_sync,
            payload,
            credential(payload),
            self.browser_context_profile(),
            self.browser_fingerprint_policy(),
        )

    def routers(self) -> tuple[Any, ...]:
        from .qwen_risk import router

        return (router,)

    def browser_context_profile(self) -> BrowserContextProfile:
        return BrowserContextProfile(
            ignore_https_errors=True,
            locale="zh-CN",
            timezone_id="Asia/Shanghai",
            viewport_width=1365,
            viewport_height=900,
            accept_language="zh-CN,zh;q=0.9",
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
                for width, height in ((1365, 900), (1440, 900), (1536, 864))
            ),
            camoufox_mode="synthetic",
        )


def _reauthenticate_sync(
    payload: dict[str, Any],
    current: dict[str, Any],
    profile: BrowserContextProfile,
    fingerprint_policy: BrowserFingerprintPolicy,
) -> dict[str, Any]:
    result = _run_qwen_api_flow(payload, current, "reauthenticate", profile, fingerprint_policy)
    return result.metadata


def _keepalive_sync(
    payload: dict[str, Any],
    current: dict[str, Any],
    profile: BrowserContextProfile,
    fingerprint_policy: BrowserFingerprintPolicy,
) -> dict[str, Any]:
    result = _run_qwen_api_flow(payload, current, "keepalive", profile, fingerprint_policy)
    return result.metadata


def _run_qwen_api_flow(
    payload: dict[str, Any],
    current: dict[str, Any],
    operation: str,
    profile: BrowserContextProfile,
    fingerprint_policy: BrowserFingerprintPolicy,
) -> BrowserResult:
    flow_payload = {**payload}
    flow_payload.setdefault("proxy_check_url", settings().qwen_base_url)
    return run_browser_flow(
        lambda page, context, backend, proxy_url: _qwen_api_browser(
            page, backend, proxy_url, current, operation
        ),
        preferred="patchright",
        fallback="camoufox",
        payload=flow_payload,
        context_profile=profile,
        fingerprint_policy=fingerprint_policy,
    )


def _qwen_api_browser(
    page, backend: str, proxy_url: str, current: dict[str, Any], operation: str
) -> BrowserResult:
    config = settings()
    base_url = config.qwen_base_url.rstrip("/")
    page.goto(base_url, wait_until="domcontentloaded")
    _wait_qwen_risk_runtime(page)
    if operation == "reauthenticate":
        email = required(current, "email")
        password = required(current, "password")
        token = _signin_with_current_protocol(page, email, password, proxy_url)
        if token:
            return BrowserResult(
                email,
                email,
                {},
                metadata={
                    "healthy": True,
                    "credential_patch": {**current, "token": token},
                },
            )
        return BrowserResult(
            email,
            email,
            {},
            metadata={"healthy": False, "auth_expired": True, "terminal": True},
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
    url = f"{base_url}/api/v2/models/"
    risk = _risk_headers_from_page(page, url, "GET", "")
    response = httpx.get(
        url,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/json",
            "Origin": base_url,
            "Referer": f"{base_url}/",
            "source": config.qwen_source,
            **risk,
        },
        proxy=proxy_url or None,
        timeout=60,
    )
    if response.status_code in {401, 403}:
        metadata = {"healthy": False, "auth_expired": True}
    else:
        response.raise_for_status()
        metadata = {"healthy": True, "auth_expired": False}
    return BrowserResult("qwen", str(current.get("email") or ""), {}, metadata=metadata)


def _register_browser(
    page, context, backend, proxy_url, mail, mailbox, password, trace: RegistrationTrace
) -> BrowserResult:
    config = settings()
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
    credential_value["token"] = challenge.token or _signin_sync(
        page, mailbox.address, password, proxy_url
    )
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


def _signin_sync(page, email: str, password: str, proxy_url: str = "") -> str:
    token = _signin_with_current_protocol(page, email, password, proxy_url)
    if token:
        return token
    raise RuntimeError("Qwen post-registration sign-in failed")


def _signin_with_current_protocol(
    page, email: str, password: str, proxy_url: str = ""
) -> str | None:
    config = settings()
    base_url = config.qwen_base_url.rstrip("/")
    candidates = (hashlib.sha256(password.encode()).hexdigest(), password)
    for path in ("/api/v2/auths/signin", "/api/v1/auths/signin"):
        url = base_url + path
        for candidate in candidates:
            body = f'{{"email":"{email}","password":"{candidate}"}}'
            risk = _risk_headers_from_page(page, url, "POST", body)
            response = httpx.post(
                url,
                json={"email": email, "password": candidate},
                headers={"source": config.qwen_source, **risk},
                proxy=proxy_url or None,
                timeout=60,
            )
            if response.is_success:
                data = response.json()
                token = data.get("token") or (data.get("data") or {}).get("token")
                if token:
                    return str(token)
            if response.status_code not in {400, 401, 403, 404, 405}:
                response.raise_for_status()
            if response.status_code in {404, 405}:
                break
    return None


def _risk_headers_from_page(page, url: str, method: str, body: str) -> dict[str, str]:
    import json

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
