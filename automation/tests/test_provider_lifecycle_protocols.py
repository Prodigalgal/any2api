import base64
from contextlib import contextmanager
from urllib.parse import parse_qs, urlparse

import httpx
import pytest
from cryptography.hazmat.primitives import padding, serialization
from cryptography.hazmat.primitives.asymmetric import padding as asymmetric_padding
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

from any2api_automation.captcha.registry import SolverRegistry
from any2api_automation.lifecycle.browser import BrowserResult
from any2api_automation.lifecycle.mail import Mailbox
from any2api_automation.lifecycle.registration import RegistrationStage, RegistrationTrace
from any2api_automation.providers.glm import (
    _activate_from_link,
    _activation_parameters,
    _retryable_registration_challenge,
)
from any2api_automation.providers.grok_protocol.antibot_service import (
    AntibotService,
    _parse_bot_flag_details,
)
from any2api_automation.providers.grok_protocol.device_flow import (
    DEFAULT_SCOPES,
    _device_headers,
    _install_sso_cookies,
    _parse_consent_page,
)
from any2api_automation.providers.grok_settings import settings as grok_settings
from any2api_automation.providers.longcat import _login_url
from any2api_automation.providers.longcat_settings import settings as longcat_settings
from any2api_automation.providers.mimo_protocol import (
    XiaomiProtocolClient,
    _captcha_candidates,
    _encrypt_credentials,
    _region,
    _select_registration_public_key,
    _webpack_crypto_assets,
)
from any2api_automation.providers.minmax import _script_urls, _verified_profile_identity
from any2api_automation.providers.minmax_settings import settings as minmax_settings
from any2api_automation.providers.qwen_challenge import (
    _drag_slider_to_piece_target,
    _piece_target_to_drag,
)


class _Estimate:
    def __init__(self, value: str, confidence: float) -> None:
        self.value = value
        self.confidence = confidence


def test_registration_result_distinguishes_account_from_inference_readiness() -> None:
    result = BrowserResult(
        "account-1",
        "mail@example.test",
        {"sso": "session-only", "auth_stage": "registered_pending_auth"},
        ready_for_inference=False,
    ).response()

    assert result["healthy"] is False
    assert result["ready_for_inference"] is False
    assert result["credential"]["sso"] == "session-only"


def test_glm_activation_parameters_require_expected_https_mailbox() -> None:
    link = (
        "https://chat.z.ai/auth/verify_email?"
        "username=fixture-user&email=mail%40example.test&token=fixture-token"
    )

    assert _activation_parameters(link, "mail@example.test") == (
        "fixture-user",
        "mail@example.test",
        "fixture-token",
    )
    with pytest.raises(RuntimeError, match="host"):
        _activation_parameters(link.replace("chat.z.ai", "invalid.example"), "mail@example.test")
    with pytest.raises(RuntimeError, match="mailbox"):
        _activation_parameters(link, "other@example.test")


def test_glm_registration_retries_only_before_form_submission() -> None:
    trace = RegistrationTrace("glm")
    trace.mark(RegistrationStage.FORM_READY)
    error = RuntimeError("GLM Aliyun captcha initialization timed out")

    assert _retryable_registration_challenge(trace, error) is True

    trace.mark(RegistrationStage.FORM_SUBMITTED)
    assert _retryable_registration_challenge(trace, error) is False
    assert (
        _retryable_registration_challenge(
            RegistrationTrace("glm"), RuntimeError("unrelated failure")
        )
        is False
    )


def test_glm_activation_executes_verify_finish_and_profile_probe() -> None:
    calls: list[dict[str, object]] = []
    scripts: list[str] = []

    class ActivationPage:
        def evaluate(self, script: str, argument: object | None = None) -> object:
            scripts.append(script)
            if isinstance(argument, dict):
                calls.append(argument)
                if argument["path"] == "/api/v1/auths/verify_email":
                    return {"ok": True, "status": 200, "detail": "", "token": "", "id": ""}
                if argument["path"] == "/api/v1/auths/finish_signup":
                    return {
                        "ok": True,
                        "status": 200,
                        "detail": "",
                        "token": "access-token",
                        "id": "user-id",
                    }
            if "localStorage.setItem" in script:
                calls.append({"stored_token": argument})
                return None
            if "/api/v1/auths/" in script:
                return {"ok": True, "status": 200, "id": "user-id"}
            raise AssertionError("unexpected browser evaluation")

    token, profile = _activate_from_link(
        ActivationPage(),
        "https://chat.z.ai/auth/verify_email?"
        "username=fixture-user&email=mail%40example.test&token=fixture-token",
        "mail@example.test",
        "StrongPassword123!",
    )

    assert token == "access-token"
    assert profile == {"id": "user-id"}
    assert [call.get("path") for call in calls[:2]] == [
        "/api/v1/auths/verify_email",
        "/api/v1/auths/finish_signup",
    ]
    assert calls[2] == {"stored_token": "access-token"}
    assert sum("AbortController" in script for script in scripts) == 3


def test_longcat_login_url_is_built_from_provider_configuration() -> None:
    config = longcat_settings()
    parsed = urlparse(_login_url())
    query = parse_qs(parsed.query)

    assert parsed.netloc == urlparse(config.longcat_passport_url).netloc
    assert parsed.path == "/pc/login"
    assert query["service"] == [config.longcat_service]
    assert query["region"] == [config.longcat_region]
    assert query["backurl"][0].startswith(config.longcat_base_url)


def test_minmax_official_asset_hosts_include_current_and_legacy_cdn() -> None:
    config = minmax_settings()
    allowed = {
        "agent.minimax.io",
        *(host.strip() for host in config.minmax_profile_asset_hosts.split(",")),
    }
    html = """
      <script src="https://cdn.hailuo.ai/current.js"></script>
      <script src="https://cdn.hailuoai.com/legacy.js"></script>
      <script src="https://untrusted.example/injected.js"></script>
    """

    assert _script_urls("https://agent.minimax.io", html, allowed) == [
        "https://cdn.hailuo.ai/current.js",
        "https://cdn.hailuoai.com/legacy.js",
    ]


def test_minmax_registration_uses_verified_profile_identity_not_request_user_id() -> None:
    identity = _verified_profile_identity(
        {
            "data": {
                "userInfo": {
                    "userID": "account-user",
                    "realUserID": "stable-real-user",
                    "email": "mail@example.test",
                }
            },
            "statusInfo": {"code": 0},
        },
        "mail@example.test",
    )

    assert identity == {
        "external_id": "stable-real-user",
        "account_user_id": "account-user",
        "real_user_id": "stable-real-user",
    }


def test_minmax_registration_rejects_a_profile_for_another_mailbox() -> None:
    with pytest.raises(RuntimeError, match="does not match"):
        _verified_profile_identity(
            {
                "data": {
                    "userInfo": {
                        "userID": "account-user",
                        "realUserID": "stable-real-user",
                        "email": "other@example.test",
                    }
                },
                "statusInfo": {"code": 0},
            },
            "mail@example.test",
        )


@pytest.mark.asyncio
async def test_mimo_reauthentication_falls_back_when_exchanged_token_is_rejected(
    monkeypatch,
) -> None:
    from any2api_automation.providers import mimo

    monkeypatch.setattr(
        mimo,
        "_exchange_pass_token",
        lambda _payload, _current: {"service_token": "exchanged"},
    )
    monkeypatch.setattr(
        mimo,
        "_password_otp_reauthenticate",
        lambda *_args: {
            "service_token": "password-valid",
            "user_id": "mimo-user",
            "xiaomichatbot_ph": "phase",
        },
    )
    monkeypatch.setattr(
        mimo,
        "run_browser_flow",
        lambda *_args, **_kwargs: pytest.fail("direct password recovery should be final"),
    )
    monkeypatch.setattr(
        mimo,
        "_keepalive_sync",
        lambda _payload, current: {"healthy": current.get("service_token") == "password-valid"},
    )

    result = await mimo.MimoAutomationProvider().reauthenticate(
        {
            "credential": {
                "email": "mail@example.test",
                "password": "Password1!",
                "mail_jwt": "mail-token",
                "pass_token": "old-pass",
                "service_token": "old-service",
            }
        }
    )

    assert result["healthy"] is True
    assert result["recovery_stage"] == "password_otp"
    assert result["credential_patch"]["service_token"] == "password-valid"


@pytest.mark.asyncio
async def test_mimo_reauthentication_skips_pass_token_after_inference_rejection(
    monkeypatch,
) -> None:
    from any2api_automation.providers import mimo

    monkeypatch.setattr(
        mimo,
        "_exchange_pass_token",
        lambda *_args: pytest.fail("the rejected pass token path must not be retried"),
    )
    monkeypatch.setattr(
        mimo,
        "_password_otp_reauthenticate",
        lambda *_args: {
            "service_token": "password-valid",
            "user_id": "mimo-user",
            "xiaomichatbot_ph": "phase",
        },
    )
    monkeypatch.setattr(
        mimo,
        "run_browser_flow",
        lambda *_args, **_kwargs: pytest.fail("direct password recovery should be final"),
    )
    monkeypatch.setattr(
        mimo,
        "_keepalive_sync",
        lambda _payload, current: {"healthy": current.get("service_token") == "password-valid"},
    )

    result = await mimo.MimoAutomationProvider().reauthenticate(
        {
            "metadata": {
                "inference_probe_status": "FAILED",
                "inference_probe_error": "credential_rejected",
            },
            "credential": {
                "email": "mail@example.test",
                "password": "Password1!",
                "mail_jwt": "mail-token",
                "pass_token": "old-pass",
                "service_token": "old-service",
            },
        }
    )

    assert result["healthy"] is True
    assert result["recovery_stage"] == "password_otp"
    assert result["credential_patch"]["service_token"] == "password-valid"


@pytest.mark.asyncio
async def test_mimo_reauthentication_reports_sanitized_password_failure(
    monkeypatch,
) -> None:
    from any2api_automation.providers import mimo

    monkeypatch.setattr(
        mimo,
        "_exchange_pass_token",
        lambda *_args: pytest.fail("the rejected pass token path must not be retried"),
    )
    monkeypatch.setattr(
        mimo,
        "_password_otp_reauthenticate",
        lambda *_args: (_ for _ in ()).throw(
            RuntimeError("Xiaomi password login failed: invalid credential")
        ),
    )
    monkeypatch.setattr(
        mimo,
        "run_browser_flow",
        lambda *_args, **_kwargs: pytest.fail("known-bad UI fallback must not run"),
    )

    result = await mimo.MimoAutomationProvider().reauthenticate(
        {
            "metadata": {
                "inference_probe_status": "FAILED",
                "inference_probe_error": "credential_rejected",
            },
            "credential": {
                "email": "mail@example.test",
                "password": "Password1!",
                "mail_jwt": "mail-token",
                "pass_token": "old-pass",
                "service_token": "old-service",
            },
        }
    )

    assert result == {
        "healthy": False,
        "auth_expired": True,
        "error_class": "mimo_reauth_password_rejected",
        "recovery_stage": "password_otp",
        "metadata_patch": {
            "mimo_recovery_stage": "password_otp",
            "mimo_recovery_error": "mimo_reauth_password_rejected",
        },
    }


def test_mimo_password_otp_reauthentication_exchanges_fresh_service_cookies() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        path = request.url.path
        if path == "/pass/serviceLogin":
            return httpx.Response(
                200,
                json={
                    "serviceParam": "service",
                    "callback": "https://aistudio.xiaomimimo.com/sts",
                    "qs": "%3Fsid%3Dxiaomichatbot%26_json%3Dtrue",
                    "_sign": "sign",
                },
            )
        if path == "/pass/serviceLoginAuth2":
            return httpx.Response(
                200,
                headers={"set-cookie": "identitySession=temporary; Path=/"},
                json={
                    "securityStatus": 16,
                    "notificationUrl": (
                        "https://account.xiaomi.com/identity?context=identity-context"
                    ),
                },
            )
        if path == "/identity/list":
            return httpx.Response(200, json={"flag": "8"})
        if path == "/identity/auth/sendEmailTicket":
            return httpx.Response(200, json={"code": 0})
        if path == "/identity/auth/verifyEmail" and request.method == "GET":
            return httpx.Response(200, json={"code": 0})
        if path == "/identity/auth/verifyEmail" and request.method == "POST":
            return httpx.Response(
                200,
                json={"code": 0, "location": "https://account.xiaomi.com/after-otp"},
            )
        if path == "/after-otp":
            return httpx.Response(
                302,
                headers=[
                    ("set-cookie", "passToken=fresh-pass; Path=/"),
                    ("set-cookie", "userId=fresh-user; Path=/"),
                    ("set-cookie", "cUserId=fresh-cuser; Path=/"),
                    ("location", "https://aistudio.xiaomimimo.com/sts"),
                ],
            )
        if path == "/open-apis/user/info":
            return httpx.Response(200, json={"loginUrl": "https://account.xiaomi.com/exchange"})
        if path == "/exchange":
            return httpx.Response(
                302,
                headers=[
                    ("set-cookie", "serviceToken=preliminary-service; Path=/"),
                    ("set-cookie", "xiaomichatbot_ph=preliminary-phase; Path=/"),
                    ("location", "https://aistudio.xiaomimimo.com/sts"),
                ],
            )
        if path == "/sts":
            return httpx.Response(
                302,
                headers=[
                    ("set-cookie", 'serviceToken="fresh-service"; Path=/'),
                    ("set-cookie", 'xiaomichatbot_ph="fresh-phase"; Path=/'),
                    (
                        "location",
                        "https://aistudio.xiaomimimo.com/open-apis/bot/config",
                    ),
                ],
            )
        raise AssertionError(f"unexpected request: {request.method} {request.url}")

    class Mail:
        def message_ids_sync(self, _mailbox: Mailbox) -> set[str]:
            return {"old-message"}

        def wait_for_code_sync(self, _mailbox: Mailbox, *, seen_ids: set[str] | None = None) -> str:
            assert seen_ids == {"old-message"}
            return "654321"

    client = XiaomiProtocolClient("")
    client.client.close()
    client.client = httpx.Client(transport=httpx.MockTransport(handler), follow_redirects=False)
    try:
        result = client.reauthenticate_password(
            {
                "email": "mail@example.test",
                "password": "Password1!",
                "mail_jwt": "mail-token",
                "user_id": "old-user",
                "c_user_id": "old-cuser",
                "device_id": "device",
            },
            Mail(),  # type: ignore[arg-type]
            Mailbox("mail@example.test", "mail-token"),
        )
    finally:
        client.close()

    password_request = next(
        request for request in requests if request.url.path == "/pass/serviceLoginAuth2"
    )
    assert "hash=Password1%21" not in password_request.content.decode()
    assert (
        "passToken=fresh-pass"
        in next(
            request for request in requests if request.url.path == "/open-apis/user/info"
        ).headers["cookie"]
    )
    assert (
        "identitySession"
        not in next(
            request for request in requests if request.url.path == "/open-apis/user/info"
        ).headers["cookie"]
    )
    assert (
        "ticket=654321"
        in next(
            request
            for request in requests
            if request.url.path == "/identity/auth/verifyEmail" and request.method == "POST"
        ).content.decode()
    )
    assert result == {
        "service_token": "fresh-service",
        "xiaomichatbot_ph": "fresh-phase",
        "user_id": "fresh-user",
        "pass_token": "fresh-pass",
        "c_user_id": "fresh-cuser",
        "device_id": "device",
    }


def test_grok_device_consent_uses_live_form_fields() -> None:
    page = _parse_consent_page(
        """
        <form action="/oauth2/device/approve-v2">
          <input name="user_code" value="AB-CD" />
          <input name="principal_type" value="User" />
          <input name="principal_id" value="11111111-2222-3333-4444-555555555555" />
        </form>
        """,
        "https://auth.x.ai/oauth2/device/consent",
    )

    assert page == {
        "approve_url": "https://auth.x.ai/oauth2/device/approve-v2",
        "user_code": "AB-CD",
        "principal_type": "User",
        "principal_id": "11111111-2222-3333-4444-555555555555",
    }


def test_grok_device_flow_matches_current_official_client_contract() -> None:
    config = grok_settings()

    assert config.grok_oauth_scopes == DEFAULT_SCOPES
    assert config.grok_oauth_scopes.split() == [
        "openid",
        "profile",
        "email",
        "offline_access",
        "grok-cli:access",
        "api:access",
        "conversations:read",
        "conversations:write",
        "workspaces:read",
        "workspaces:write",
    ]
    assert _device_headers(
        config.grok_client_version,
        config.grok_oauth_client_surface,
    ) == {
        "content-type": "application/x-www-form-urlencoded",
        "accept": "application/json",
        "x-grok-client-version": "0.2.112",
        "x-grok-client-surface": "ui",
    }
    assert config.grok_oauth_referrer == "grok-build"


def test_grok_device_flow_installs_both_session_cookies_on_both_domains() -> None:
    calls: list[tuple[str, str, str, str]] = []

    class Cookies:
        def set(self, name: str, value: str, *, domain: str, path: str) -> None:
            calls.append((name, value, domain, path))

    class Session:
        cookies = Cookies()

    _install_sso_cookies(Session(), "session-value")

    assert calls == [
        ("sso", "session-value", ".x.ai", "/"),
        ("sso", "session-value", "accounts.x.ai", "/"),
        ("sso-rw", "session-value", ".x.ai", "/"),
        ("sso-rw", "session-value", "accounts.x.ai", "/"),
    ]


def test_grok_reauthentication_escalates_to_password_relogin(monkeypatch) -> None:
    from any2api_automation.providers import grok

    @contextmanager
    def no_proxy(**_kwargs):
        yield ""

    monkeypatch.setattr(grok, "proxy_lease", no_proxy)
    monkeypatch.setattr(grok, "_refresh_oauth_token", lambda *_args: None)

    def exchange(sso: str, _proxy: str):
        if sso == "old-sso":
            raise RuntimeError("saved SSO rejected")
        return {
            "access_token": "new-access",
            "refresh_token": "new-refresh",
            "expires_in": 3600,
        }

    monkeypatch.setattr(grok, "_exchange_sso_token", exchange)
    monkeypatch.setattr(
        grok,
        "_password_relogin_sso",
        lambda *_args: ("new-sso", {"sso": "new-sso", "sso-rw": "new-sso"}),
    )

    result = grok._reauthenticate_sync(
        {},
        {
            "email": "old@example.test",
            "password": "Password1!",
            "refresh_token": "old-refresh",
            "sso": "old-sso",
        },
    )

    assert result["healthy"] is True
    assert result["recovery_stage"] == "password_relogin"
    assert result["recovery_attempts"] == [
        "refresh_token",
        "saved_sso",
        "password_relogin",
        "new_sso_oauth",
    ]
    assert result["credential_patch"]["sso"] == "new-sso"
    assert result["credential_patch"]["refresh_token"] == "new-refresh"
    assert result["credential_expires_at"]


def test_grok_reauthentication_preserves_new_sso_when_oauth_is_pending(monkeypatch) -> None:
    from any2api_automation.providers import grok

    @contextmanager
    def no_proxy(**_kwargs):
        yield ""

    monkeypatch.setattr(grok, "proxy_lease", no_proxy)
    monkeypatch.setattr(grok, "_refresh_oauth_token", lambda *_args: None)
    monkeypatch.setattr(
        grok,
        "_password_relogin_sso",
        lambda *_args: ("replacement-sso", {"sso": "replacement-sso"}),
    )
    monkeypatch.setattr(
        grok,
        "_exchange_sso_token",
        lambda *_args: (_ for _ in ()).throw(RuntimeError("invalid_grant")),
    )

    result = grok._reauthenticate_sync(
        {},
        {"email": "old@example.test", "password": "Password1!"},
    )

    assert result["healthy"] is False
    assert result["authorization_pending"] is True
    assert result["terminal"] is False
    assert result["credential_patch"]["sso"] == "replacement-sso"
    assert result["credential_patch"]["auth_stage"] == "registered_pending_auth"


def test_grok_saved_sso_supports_legacy_credential_shapes() -> None:
    from any2api_automation.providers.grok import _saved_sso

    assert _saved_sso({"sso_cookie": "sso=direct"}) == "direct"
    assert _saved_sso({"cookies": {"sso-rw": "nested"}}) == "nested"
    assert _saved_sso({"cookie": "other=1; sso-rw=header; tail=2"}) == "header"


def test_grok_keepalive_uses_a_completed_responses_probe() -> None:
    from any2api_automation.providers.grok import _probe_grok_inference

    class Response:
        status_code = 200

        def __enter__(self):
            return self

        def __exit__(self, *_args) -> None:
            return None

        def raise_for_status(self) -> None:
            return None

        def iter_lines(self):
            return iter(
                ('data: {"type":"response.created"}', 'data: {"type":"response.completed"}')
            )

    class Client:
        request: tuple[str, str, dict] | None = None

        def stream(self, method: str, url: str, **kwargs):
            self.request = (method, url, kwargs)
            return Response()

    client = Client()
    assert _probe_grok_inference(client, "https://grok.example/v1", "token", "grok-test") == 200
    assert client.request is not None
    assert client.request[0:2] == ("POST", "https://grok.example/v1/responses")
    assert client.request[2]["json"]["model"] == "grok-test"
    assert client.request[2]["json"]["stream"] is True


def test_grok_keepalive_rejects_an_incomplete_stream() -> None:
    from any2api_automation.providers.grok import _probe_grok_inference

    class Response:
        status_code = 200

        def __enter__(self):
            return self

        def __exit__(self, *_args) -> None:
            return None

        def raise_for_status(self) -> None:
            return None

        def iter_lines(self):
            return iter(('data: {"type":"response.created"}',))

    class Client:
        def stream(self, *_args, **_kwargs):
            return Response()

    with pytest.raises(RuntimeError, match="without a completion event"):
        _probe_grok_inference(Client(), "https://grok.example/v1", "token", "grok-test")


def test_grok_castle_policy_deny_is_never_treated_as_registered() -> None:
    parsed = _parse_bot_flag_details("policy=deny,risk=0.99,event=$registration")
    risk = {
        "ok": True,
        "clean": False,
        "denied": parsed["has_policy_deny"],
        "false_clean": False,
        "cli_usable": False,
        "risk_score": parsed["risk_score"],
        "bot_flag_source": "BOT_FLAG_SOURCE_CASTLE",
        "bot_flag_details": parsed["bot_flag_details"],
        "policy": parsed["policy"],
        "event": parsed["event"],
    }

    assert not AntibotService.is_risk_clean(risk)
    assert AntibotService.risk_mark_summary(risk).startswith("DENIED score=0.99")


def test_mimo_encryption_round_trip_matches_xiaomi_envelope() -> None:
    private_key = rsa.generate_private_key(public_exponent=65537, key_size=1024)
    public_der = private_key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    encrypted_email, encrypted_password, eui = _encrypt_credentials(
        "mail@example.test",
        "Password1!",
        base64.b64encode(public_der).decode(),
        "0102030405060708",
    )
    wrapped_key, fields = eui.split(".", 1)
    key = base64.b64decode(
        private_key.decrypt(base64.b64decode(wrapped_key), asymmetric_padding.PKCS1v15())
    )
    assert base64.b64decode(fields) == b"email,password"

    def decrypt(value: str) -> str:
        decryptor = Cipher(algorithms.AES(key), modes.CBC(b"0102030405060708")).decryptor()
        padded = decryptor.update(base64.b64decode(value)) + decryptor.finalize()
        unpadder = padding.PKCS7(128).unpadder()
        return (unpadder.update(padded) + unpadder.finalize()).decode()

    assert decrypt(encrypted_email) == "mail@example.test"
    assert decrypt(encrypted_password) == "Password1!"


def test_mimo_public_key_discovery_selects_the_current_host_branch() -> None:
    preview = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQ" + "A" * 170
    production = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQ" + "B" * 170
    asset = (
        'const preview=window.location.host.includes("preview");'
        f'o.setPublicKey(preview?"{preview}":"{production}");'
        'const iv=parse("0102030405060708");'
    )

    assert (
        _select_registration_public_key(
            [("https://cdn.example/crypto.js", asset)], account_host="account.xiaomi.com"
        )
        == production
    )
    assert (
        _select_registration_public_key(
            [("https://cdn.example/crypto.js", asset)],
            account_host="account.preview.n.xiaomi.net",
        )
        == preview
    )


def test_mimo_captcha_candidates_are_ranked_and_deduplicated() -> None:
    candidates = _captcha_candidates(
        [_Estimate(" ab12 ", 0.9), _Estimate("AB12", 0.7), _Estimate("x", 1.0)]
    )
    assert candidates == ["ab12", "AB12"]


def test_mimo_rejects_mainland_registration_region() -> None:
    for value in ("CN", "china", "prc"):
        try:
            _region(value)
        except ValueError:
            continue
        raise AssertionError(f"{value} should be rejected")


def test_mimo_discovers_content_addressed_crypto_chunk() -> None:
    assets = _webpack_crypto_assets(
        [
            (
                "https://cdn.example/static/js/runtime-main.hash.js",
                'names={1234:"crypto",5678:"profile"};hashes={1234:"abcdef12",5678:"12345678"}',
            )
        ]
    )
    assert assets == ["https://cdn.example/static/js/crypto.abcdef12.chunk.js"]


def test_qwen_slider_uses_measured_inverse_curve() -> None:
    assert _piece_target_to_drag(0, 260) == 0
    assert 215 < _piece_target_to_drag(188.76, 260) < 225
    assert _piece_target_to_drag(999, 260) == 260


class _FakeSlider:
    def bounding_box(self) -> dict[str, float]:
        return {"x": 0.0, "y": 0.0, "width": 40.0, "height": 40.0}


class _FakeMouse:
    def __init__(self, page: "_FakePage") -> None:
        self.page = page

    def move(self, x: float, _y: float) -> None:
        if self.page.pressed:
            self.page.piece += (x - self.page.mouse_x) * 0.6
        self.page.mouse_x = x

    def down(self) -> None:
        self.page.pressed = True

    def up(self) -> None:
        self.page.pressed = False
        self.page.released = True


class _FakePage:
    def __init__(self) -> None:
        self.mouse_x = 0.0
        self.piece = 0.0
        self.pressed = False
        self.released = False
        self.mouse = _FakeMouse(self)

    def evaluate(self, _script: str) -> float:
        return self.piece

    def wait_for_timeout(self, _milliseconds: int) -> None:
        return None


def test_qwen_slider_adapts_to_observed_piece_position() -> None:
    page = _FakePage()
    final, _, _ = _drag_slider_to_piece_target(page, _FakeSlider(), 100.0, 260.0, 0.0, 0.75)

    assert page.released is True
    assert page.pressed is False
    assert abs(final - 100.0) <= 2.5


def test_slider_solver_rejects_left_edge_template_match() -> None:
    import cv2
    import numpy as np

    background = np.zeros((100, 300, 3), dtype=np.uint8)
    piece = np.zeros((40, 40, 3), dtype=np.uint8)
    _, background_png = cv2.imencode(".png", background)
    _, piece_png = cv2.imencode(".png", piece)

    result = SolverRegistry()._solve_slider_opencv_sync(
        background_png.tobytes(), piece_png.tobytes()
    )

    assert result is None
