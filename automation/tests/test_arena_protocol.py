from __future__ import annotations

import json
import re

import pytest

from any2api_automation.lifecycle.mail import Mailbox
from any2api_automation.lifecycle.registration import RegistrationTrace
from any2api_automation.providers.arena_browser import (
    _accept_arena_terms_if_present,
    _arena_anonymous_signup_script,
    _arena_anonymous_signup_with_retry,
    _arena_goto,
    _arena_me_script,
    _arena_ndjson_stream_script,
    _arena_set_password_script,
    _arena_set_password_with_retry,
    _arena_sign_in_script,
    _arena_terms_script,
    _arena_turbopack_uploader_init_script,
    _arena_update_tou_consent_script,
    _arena_upload_script,
    _ensure_arena_tou_consent,
    _validate_arena_link,
    arena_media_sources,
    build_arena_request,
    parse_arena_models,
    register_with_magic_link,
)
from any2api_automation.providers.arena_settings import settings as arena_settings

_MODEL_UUID = "11111111-1111-4111-8111-111111111111"
_PNG = "data:image/png;base64,YQ=="
_PDF = "data:application/pdf;base64,JVBERi0xLjQ="


@pytest.mark.asyncio
async def test_arena_runtime_accepts_only_the_terms_and_privacy_dialog() -> None:
    script = _arena_terms_script()
    assert '[role="dialog"]' in script
    assert "terms of use" in script
    assert "privacy policy" in script
    assert "agree.click()" in script

    class Page:
        def __init__(self) -> None:
            self.results = [{"status": "absent"}, {"status": "accepted"}]
            self.waits: list[int] = []

        async def evaluate(self, _: str) -> dict[str, str]:
            return self.results.pop(0)

        async def wait_for_timeout(self, milliseconds: int) -> None:
            self.waits.append(milliseconds)

    page = Page()
    assert await _accept_arena_terms_if_present(page) == "accepted"
    assert page.waits == [250, 250]


@pytest.mark.asyncio
async def test_arena_runtime_surfaces_a_terms_dialog_without_agree_button() -> None:
    class Page:
        async def evaluate(self, _: str) -> dict[str, str]:
            return {"status": "missing_button"}

        async def wait_for_timeout(self, _: int) -> None:
            raise AssertionError("a missing Agree button must fail immediately")

    with pytest.raises(RuntimeError, match="missing_button"):
        await _accept_arena_terms_if_present(Page())


@pytest.mark.asyncio
async def test_arena_runtime_confirms_tou_via_official_api_when_dialog_is_deferred() -> None:
    assert "touConsentTimestamp" in _arena_me_script()
    assert "input.path" in _arena_update_tou_consent_script()
    assert "method: 'POST'" in _arena_update_tou_consent_script()

    class Page:
        def __init__(self) -> None:
            self.profile_calls = 0
            self.consent_calls = 0
            self.waits: list[int] = []

        async def evaluate(self, script: str, argument: object | None = None) -> dict[str, object]:
            del argument
            if "touConsentTimestampPresent" in script:
                self.profile_calls += 1
                return {
                    "status": 200,
                    "id": "arena-user-1",
                    "touConsentFieldPresent": True,
                    "touConsentTimestampPresent": self.profile_calls > 1,
                }
            if "agree.click()" in script:
                return {"status": "absent"}
            self.consent_calls += 1
            return {"ok": True, "status": 200}

        async def wait_for_timeout(self, milliseconds: int) -> None:
            self.waits.append(milliseconds)

    page = Page()
    assert await _ensure_arena_tou_consent(page) == "accepted_via_api"
    assert page.profile_calls == 2
    assert page.consent_calls == 1


@pytest.mark.asyncio
async def test_arena_runtime_skips_consent_write_for_already_consented_profile() -> None:
    class Page:
        async def evaluate(self, script: str, argument: object | None = None) -> dict[str, object]:
            del argument
            assert "touConsentTimestampPresent" in script
            return {
                "status": 200,
                "id": "arena-user-1",
                "touConsentFieldPresent": True,
                "touConsentTimestampPresent": True,
            }

    assert await _ensure_arena_tou_consent(Page()) == "already_consented"


def _command(messages: list[dict[str, object]], **overrides: object) -> dict[str, object]:
    command: dict[str, object] = {
        "schemaVersion": 1,
        "requestId": "arena-test",
        "protocol": "CHAT_COMPLETIONS",
        "model": "Max",
        "stream": True,
        "messages": messages,
        "generation": {},
        "reasoning": {},
        "tools": [],
        "providerOptions": {},
        "controls": {},
    }
    command.update(overrides)
    return command


def test_arena_mapper_translates_search_and_signed_attachments() -> None:
    command = _command(
        [
            {"role": "system", "content": "Inspect carefully"},
            {
                "role": "user",
                "content": [
                    {"type": "input_text", "text": "Describe both inputs"},
                    {"type": "input_image", "image_url": _PNG, "filename": "sample.png"},
                    {
                        "type": "input_file",
                        "input_file": {"file_data": _PDF, "filename": "brief.pdf"},
                    },
                ],
            },
        ],
        providerOptions={"web_search": True},
    )
    body = build_arena_request(
        command,
        model_id=_MODEL_UUID,
        attachments=[
            {
                "name": "sample.png",
                "contentType": "image/png",
                "url": "https://storage.example.test/sample.png",
            },
            {
                "name": "brief.pdf",
                "contentType": "application/pdf",
                "url": "https://storage.example.test/brief.pdf",
            },
        ],
    )

    assert body["mode"] == "direct-battle"
    assert body["modality"] == "search"
    assert body["modelAId"] == _MODEL_UUID
    assert body["userMessage"]["content"].endswith("Describe both inputs")
    assert body["userMessage"]["experimental_attachments"] == [
        {
            "name": "sample.png",
            "contentType": "image/png",
            "url": "https://storage.example.test/sample.png",
        },
        {
            "name": "brief.pdf",
            "contentType": "application/pdf",
            "url": "https://storage.example.test/brief.pdf",
        },
    ]
    assert "rawRequest" not in json.dumps(body)


def test_arena_mapper_rejects_battle_modes_at_the_public_semantic_boundary() -> None:
    command = _command(
        [{"role": "user", "content": "hello"}],
        providerOptions={"mode": "direct-battle"},
    )

    with pytest.raises(ValueError, match="direct mode"):
        build_arena_request(command, model_id=_MODEL_UUID)


def test_arena_chat_stream_uses_the_official_recaptcha_v3_action() -> None:
    script = _arena_ndjson_stream_script(
        "__emit",
        "arena-site-key",
        arena_settings().arena_recaptcha_v2_site_key,
        arena_settings().arena_recaptcha_v2_timeout_seconds * 1000,
    )

    assert "window.grecaptcha?.enterprise" in script
    assert "enterprise.execute" in script
    assert "action: 'chat_submit'" in script
    assert "recaptchaV3Token: recaptcha.token" in script
    assert "recaptcha validation failed" in script
    assert "prompt failed" in script
    assert "enterprise.render" in script
    assert "recaptchaV2Token" in script
    assert "recaptcha_v2_required" in script
    assert "modelBId" not in script
    assert "return send(1, token)" in script
    assert "tokenLength" in script


def test_arena_media_sources_accept_inline_png_and_pdf_only() -> None:
    sources = arena_media_sources(
        [
            {
                "role": "user",
                "content": [
                    {"type": "input_image", "image_url": _PNG, "filename": "photo.png"},
                    {
                        "type": "input_file",
                        "input_file": {"file_data": _PDF, "filename": "brief.pdf"},
                    },
                ],
            }
        ]
    )

    assert [(item["filename"], item["mime_type"], item["size_bytes"]) for item in sources] == [
        ("photo.png", "image/png", 1),
        ("brief.pdf", "application/pdf", 8),
    ]

    with pytest.raises(ValueError, match="inline base64 data URL"):
        arena_media_sources(
            [
                {
                    "role": "user",
                    "content": [{"type": "input_image", "image_url": "https://example.test/a.png"}],
                }
            ]
        )


def test_arena_mapper_requires_page_upload_before_media_can_be_sent() -> None:
    command = _command([{"role": "user", "content": [{"type": "input_image", "image_url": _PNG}]}])

    with pytest.raises(ValueError, match="page-world uploader"):
        build_arena_request(command, model_id=_MODEL_UUID)

    body = build_arena_request(
        command,
        model_id=_MODEL_UUID,
        attachments=[
            {
                "name": "image.png",
                "contentType": "image/png",
                "url": "https://storage.example.test/image.png",
            }
        ],
    )
    assert body["userMessage"]["content"] == ""


def test_arena_model_catalog_preserves_media_and_search_capabilities() -> None:
    document = json.dumps(
        {
            "initialModels": [
                {
                    "id": _MODEL_UUID,
                    "publicName": "Max",
                    "displayName": "Max",
                    "userSelectable": True,
                    "capabilities": {
                        "inputCapabilities": {
                            "text": True,
                            "image": {"requiresUpload": True, "multipleImages": True},
                            "file": True,
                        },
                        "outputCapabilities": {"text": True, "search": True},
                    },
                }
            ]
        },
        separators=(",", ":"),
    )
    records = parse_arena_models(document)

    assert records[0]["id"] == "Max"
    capabilities = records[0]["metadata"]["arena_capabilities"]
    assert capabilities["inputCapabilities"]["image"]["requiresUpload"] is True
    assert capabilities["inputCapabilities"]["file"] is True
    assert capabilities["outputCapabilities"]["search"] is True


def test_arena_page_upload_script_uses_the_official_exported_uploader() -> None:
    script = _arena_upload_script()

    assert "generateUploadUrl" in script
    assert "getSignedUrl" in script
    assert "uploadFile" in script
    assert "experimental_attachments" not in script
    assert "new File([bytes]" in script


def test_arena_turbopack_init_script_captures_official_uploader_export() -> None:
    script = _arena_turbopack_uploader_init_script()

    assert "globalThis.TURBOPACK" in script
    assert "generateUploadUrl" in script
    assert "getSignedUrl" in script
    assert "__any2apiArenaUploadFile" in script
    assert "uploadFile" in script


def test_arena_password_setup_script_uses_current_magic_link_token() -> None:
    script = _arena_set_password_script()

    assert "new URL(window.location.href).searchParams.get('token')" in script
    assert "input.password" in script
    assert "credentials: 'include'" in script


def test_arena_anonymous_signup_script_uses_provisional_user_flow() -> None:
    script = _arena_anonymous_signup_script()

    assert "provisionalUserId" in script
    assert "recaptchaToken: ''" in script
    assert "user_country_code=" in script
    assert "credentials: 'include'" in script
    assert "localStorage" in script
    assert "sessionStorage" in script
    assert "provisionalIdTimeoutMs" in script


def test_arena_anonymous_signup_retries_missing_provisional_id_after_reload() -> None:
    class Page:
        def __init__(self) -> None:
            self.goto_calls = 0
            self.evaluate_calls = 0

        def goto(self, _: str, **__: object) -> None:
            self.goto_calls += 1

        def wait_for_timeout(self, _: int) -> None:
            return

        def evaluate(self, script: str, _: object) -> object:
            assert "provisionalUserId" in script
            self.evaluate_calls += 1
            if self.evaluate_calls == 1:
                return {"ok": False, "status": 400, "code": "PROVISIONAL_ID_MISSING"}
            return {
                "ok": True,
                "status": 200,
                "userId": "anonymous-user-2",
                "registeredCountryCode": "US",
            }

    result = _arena_anonymous_signup_with_retry(
        Page(),
        {"base_url": "https://arena.ai", "page_path": "/text/direct?model_a=max"},
        2,
    )

    assert result is not None
    assert result["userId"] == "anonymous-user-2"


def test_arena_password_setup_retries_transient_upstream_status() -> None:
    class Page:
        def __init__(self) -> None:
            self.evaluate_calls = 0
            self.waits: list[int] = []

        def wait_for_timeout(self, milliseconds: int) -> None:
            self.waits.append(milliseconds)

        def evaluate(self, script: str, _: object) -> object:
            assert "input.password" in script
            self.evaluate_calls += 1
            if self.evaluate_calls == 1:
                return {"ok": False, "status": 429, "success": False}
            return {"ok": True, "status": 200, "success": True}

    page = Page()
    result = _arena_set_password_with_retry(
        page,
        path="/nextjs-api/auth/set-password",
        password="TestPassword123!",
        timeout_ms=60_000,
        attempts=2,
    )

    assert result is not None
    assert result["success"] is True
    assert page.waits == [500]


def test_arena_verification_navigation_accepts_abort_after_target_loaded() -> None:
    class Page:
        def __init__(self) -> None:
            self.url = "https://arena.ai/text/direct?model_a=max"
            self.goto_calls = 0

        def goto(self, _: str, **__: object) -> None:
            self.goto_calls += 1
            self.url = "https://arena.ai/auth/verify?signup_intent_id=signup-1&token=one-time"
            raise RuntimeError("NS_BINDING_ABORTED")

        def wait_for_timeout(self, _: int) -> None:
            raise AssertionError("a loaded verification page must not be retried")

    page = Page()
    _arena_goto(
        page,
        "https://arena.ai/auth/verify?signup_intent_id=signup-1&token=one-time",
    )

    assert page.goto_calls == 1


def test_arena_sign_in_script_matches_official_email_session_exchange() -> None:
    script = _arena_sign_in_script()

    assert "input.email" in script
    assert "input.password" in script
    assert "shouldLinkHistory: false" in script
    assert "requiresVerification" in script
    assert "credentials: 'include'" in script


def test_arena_profile_probe_bypasses_pre_auth_cache() -> None:
    assert "cache: 'no-store'" in _arena_me_script()


def test_arena_verification_link_rejects_cdn_assets() -> None:
    link = "https://arena.ai/auth/verify?signup_intent_id=signup-1&token=one-time&type=email"

    assert _validate_arena_link(link) == link
    with pytest.raises(ValueError, match="host is invalid"):
        _validate_arena_link("https://cdn.arena.ai/assets/logo.png")


def test_arena_registration_uses_one_new_temp_mail_message_and_keeps_account_pending() -> None:
    class Page:
        def __init__(self) -> None:
            self.visited: list[str] = []
            self.signup: dict[str, object] = {}
            self.profile_calls = 0

        def goto(self, url: str, **_: object) -> None:
            self.visited.append(url)

        def reload(self, **_: object) -> None:
            self.visited.append("reload")

        def wait_for_timeout(self, milliseconds: int) -> None:
            assert milliseconds in {500, 1_000}

        def evaluate(self, script: str, argument: object | None = None) -> object:
            if "JSON.stringify(input.body)" in script:
                self.signup = dict(argument or {})
                return {"ok": True, "status": 200, "body": "{}"}
            if "provisionalUserId" in script:
                return {
                    "ok": True,
                    "status": 200,
                    "userId": "anonymous-user-1",
                    "registeredCountryCode": "JP",
                }
            if "input.email" in script:
                return {
                    "ok": True,
                    "status": 200,
                    "success": True,
                    "emailConfirmed": True,
                }
            if "input.password" in script:
                return {"ok": True, "status": 200, "success": True, "redirectTo": "/text/direct"}
            if "response.json()" in script:
                self.profile_calls += 1
                if self.profile_calls == 1:
                    return {"ok": False, "status": 401, "id": "", "email": ""}
                return {
                    "ok": True,
                    "status": 200,
                    "id": "arena-user-1",
                    "email": "a2a@example.test",
                }
            if "localStorage" in script:
                return {}
            if "navigator.userAgent" in script:
                return {"user_agent": "Mozilla/5.0", "os_name": "Windows"}
            raise AssertionError("unexpected Arena page evaluation")

    class Context:
        def cookies(self) -> list[dict[str, str]]:
            return [{"name": "arena-auth-prod-v1", "value": "redacted"}]

    class Mail:
        def __init__(self) -> None:
            self.seen: set[str] | None = None

        def wait_for_link_sync(self, mailbox: Mailbox, **kwargs: object) -> str:
            assert mailbox.address == "a2a@example.test"
            self.seen = kwargs["seen_ids"]
            link = (
                "https://arena.ai/auth/verify?signup_intent_id=signup-1&token=one-time&type=email"
            )
            cdn_link = "https://cdn.arena.ai/assets/logo.png"
            pattern = rf"https?://[^\s<>'\"]*{kwargs['host_pattern']}[^\s<>'\"]*"
            assert re.search(pattern, cdn_link, re.IGNORECASE) is None
            assert re.search(
                pattern,
                link,
                re.IGNORECASE,
            )
            return link

    page = Page()
    mail = Mail()
    result = register_with_magic_link(
        page,
        Context(),
        "camoufox",
        mail,
        Mailbox("a2a@example.test", "mail-jwt"),
        {"historical-message"},
        "TestPassword123!",
        {"runtime_options": {"base_url": "https://arena.ai"}},
        RegistrationTrace("arena"),
    )

    assert page.signup["body"]["email"] == "a2a@example.test"
    assert page.signup["body"]["marketingConsent"] is False
    assert mail.seen == {"historical-message"}
    assert result.external_id == "arena-user-1"
    assert result.ready_for_inference is False
    assert result.credential["password"] == "TestPassword123!"
    assert result.credential["authentication"] == "email_magic_link"
