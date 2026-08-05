import asyncio
import hashlib
import json
import random
import secrets
from typing import Any

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
from .base import AutomationProvider, AutomationProviderManifest
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
    normalize_browser_state,
    playwright_storage_state,
)
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
        inference_transport=True,
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
        return await asyncio.to_thread(
            _keepalive_sync,
            payload,
            credential(payload),
        )

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


def _keepalive_sync(
    payload: dict[str, Any],
    current: dict[str, Any],
) -> dict[str, Any]:
    result = _run_qwen_api_flow(payload, current, "keepalive")
    return result.metadata


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
    plan = new_qwen_fingerprint_plan()
    return run_browser_flow(
        lambda page, context, backend, proxy_url: _register_browser(
            page, context, backend, proxy_url, mail, mailbox, password, trace, plan
        ),
        preferred="camoufox",
        fallback="patchright",
        payload=payload,
        context_profile=_context_profile_for_fingerprint(plan.patchright, {}),
        launch_profile=_launch_profile_for_fingerprint(plan.camoufox),
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
    browser_state = normalize_browser_state(raw_state, base_url)
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
