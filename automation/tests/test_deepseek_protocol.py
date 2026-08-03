import io
from contextlib import contextmanager

import pytest
from PIL import Image, ImageDraw

from any2api_automation.captcha.models import VisualAction
from any2api_automation.lifecycle.browser import BrowserResult
from any2api_automation.lifecycle.mail import Mailbox
from any2api_automation.lifecycle.registration import RegistrationTrace
from any2api_automation.observability import OperationFailure
from any2api_automation.providers import provider_registry
from any2api_automation.providers.deepseek import (
    DeepseekAutomationProvider,
    _BrowserReauthenticationRequired,
    _headers,
    _is_waf_challenge,
    _keepalive_sync,
    _reauthenticate_sync,
    _recover_registered_user,
    _registration_user,
    _request_profile,
    _require_success,
    _run_registration_browser,
    _set_birthday,
    _warm_up_hcaptcha,
)
from any2api_automation.providers.deepseek_challenge import (
    DeepseekHcaptchaChallenge,
    _animal_matrix_empty_targets,
    _challenge_evidence,
    _extract_task_image,
    _normalize_actions,
    _solver_prompt,
    _surface_artifact,
    _wait_for_completion,
)


def test_manifest_is_discovered_with_full_lifecycle_operations() -> None:
    provider = provider_registry.require("deepseek")

    assert provider.manifest.operations == ("register", "reauthenticate", "keepalive")
    assert provider.manifest.registration_attempt_mode == "single_identity"
    assert provider.manifest.challenge_types == (
        "hcaptcha_area_select",
        "hcaptcha_grid",
        "hcaptcha_semantic_drag",
    )


@pytest.mark.asyncio
async def test_unavailable_external_captcha_ai_fails_before_mailbox_creation(monkeypatch) -> None:
    async def unexpected_prepare(*args, **kwargs):
        raise AssertionError("mailbox must not be created")

    monkeypatch.setattr(
        "any2api_automation.providers.deepseek.registry.visual_ai_available",
        lambda policy: False,
    )
    monkeypatch.setattr(
        "any2api_automation.providers.deepseek.prepare_registration", unexpected_prepare
    )

    with pytest.raises(OperationFailure) as raised:
        await DeepseekAutomationProvider().register(
            {"captcha": {"ai_enabled": True, "ai_mode": "external"}}
        )

    assert raised.value.code == "challenge_failed"
    assert raised.value.stage == "started"
    assert "external is unavailable" in raised.value.message


def test_request_profile_uses_observed_official_headers() -> None:
    profile = _request_profile(
        {
            "X-Client-Bundle-Id": "official.bundle",
            "X-Client-Platform": "web",
            "X-Client-Version": "9.8.7",
            "X-Client-Locale": "en_US",
            "X-Client-Timezone-Offset": "32400",
        }
    )

    assert profile == {
        "bundle_id": "official.bundle",
        "platform": "web",
        "client_version": "9.8.7",
        "locale": "en_US",
        "timezone_offset": 32400,
    }
    headers = _headers({**profile, "token": "secret"}, with_token=True, token="secret")
    assert headers["X-Client-Version"] == "9.8.7"
    assert headers["Authorization"] == "Bearer secret"


@pytest.mark.asyncio
async def test_registration_browser_retries_reuse_one_identity(monkeypatch) -> None:
    calls: list[dict[str, object]] = []

    def run(*args, **kwargs):
        calls.append(kwargs["payload"])
        if len(calls) < 3:
            raise RuntimeError("challenge failed")
        return BrowserResult("user", "same@example.test", {"token": "value"})

    monkeypatch.setattr("any2api_automation.providers.deepseek.run_browser_flow", run)
    provider = DeepseekAutomationProvider()
    mailbox = Mailbox("same@example.test", "mail-jwt")

    result = await _run_registration_browser(
        provider,
        {},
        object(),  # type: ignore[arg-type]
        mailbox,
        "Password1!",
        RegistrationTrace("deepseek"),
    )

    assert result.external_id == "user"
    assert len(calls) == 3
    affinity_keys = {str(call["proxy_affinity_key"]) for call in calls}
    assert len(affinity_keys) == 1
    assert next(iter(affinity_keys)).startswith("flow-")
    assert mailbox.address not in next(iter(affinity_keys))
    assert [call["proxy_node_offset"] for call in calls] == [0, 1, 2]


def test_keepalive_requires_authenticated_model_catalog(monkeypatch) -> None:
    class Response:
        status_code = 200

        def raise_for_status(self) -> None:
            return None

        def json(self):
            return {
                "code": 0,
                "data": {
                    "biz_code": 0,
                    "biz_data": {"settings": {"model_configs": {"value": []}}},
                },
            }

    class Client:
        def get(self, *args, **kwargs):
            assert kwargs["headers"]["Authorization"] == "Bearer token"
            return Response()

    @contextmanager
    def session(*args, **kwargs):
        yield Client()

    monkeypatch.setattr("any2api_automation.providers.deepseek._session", session)

    result = _keepalive_sync({}, {"token": "token", "device_id": "device"})

    assert result["healthy"] is True
    assert result["auth_expired"] is False


def test_rejected_envelopes_do_not_look_healthy() -> None:
    with pytest.raises(RuntimeError, match="biz_code=3"):
        _require_success({"code": 0, "data": {"biz_code": 3}}, "registration")


def test_registration_nested_rejection_is_not_misclassified_as_missing_identity() -> None:
    with pytest.raises(RuntimeError, match="nested_code=7"):
        _registration_user(
            {
                "code": 0,
                "data": {
                    "biz_code": 0,
                    "biz_data": {"code": 7, "msg": "rejected"},
                },
            }
        )


def test_registration_user_can_be_recovered_by_same_browser_login() -> None:
    class Page:
        def evaluate(self, script, args):
            assert "/api/v0/users/login" in script
            assert args["email"] == "same@example.test"
            assert args["password"] == "Password1!"
            assert args["device_id"] == "device"
            return {
                "status": 200,
                "body": {
                    "code": 0,
                    "data": {
                        "biz_code": 0,
                        "biz_data": {
                            "user": {
                                "id": "user",
                                "token": "token",
                                "email": "s***@example.test",
                            }
                        },
                    },
                },
            }

    user = _recover_registered_user(
        Page(),
        "same@example.test",
        "Password1!",
        "device",
        _request_profile({}),
    )

    assert user["id"] == "user"
    assert user["token"] == "token"


def test_birthday_activation_preserves_created_account_on_transient_failure() -> None:
    class Page:
        attempts = 0

        def evaluate(self, *args):
            self.attempts += 1
            if self.attempts < 3:
                raise RuntimeError("temporary network failure")
            return {"status": 200, "code": 0, "bizCode": 0}

        def wait_for_timeout(self, timeout):
            assert timeout in {750, 1500}

    assert _set_birthday(Page(), "token", _request_profile({})) == "set"


def test_reauthentication_completes_pending_birthday(monkeypatch) -> None:
    class Response:
        status_code = 200

        def raise_for_status(self) -> None:
            return None

        def json(self):
            return {
                "code": 0,
                "data": {
                    "biz_code": 0,
                    "biz_data": {"user": {"id": "user", "token": "replacement"}},
                },
            }

    class Client:
        def post(self, url, **kwargs):
            if url.endswith("/login"):
                assert kwargs["json"]["email"] == "same@example.test"
            else:
                assert url.endswith("/set_birthday")
                assert kwargs["headers"]["Authorization"] == "Bearer replacement"
            return Response()

    @contextmanager
    def session(*args, **kwargs):
        yield Client()

    monkeypatch.setattr("any2api_automation.providers.deepseek._session", session)
    result = _reauthenticate_sync(
        {},
        {
            "email": "same@example.test",
            "password": "Password1!",
            "device_id": "device",
            "birthday_status": "pending",
        },
    )

    assert result["healthy"] is True
    assert result["credential_patch"] == {
        "token": "replacement",
        "birthday_status": "set",
    }


def test_reauthentication_detects_empty_aws_waf_challenge(monkeypatch) -> None:
    class Response:
        def __init__(self) -> None:
            self.status_code = 202
            self.content = b""
            self.headers = {
                "content-type": "text/html; charset=UTF-8",
                "x-amzn-waf-action": "challenge",
            }

    class Client:
        def post(self, *args, **kwargs):
            return Response()

    @contextmanager
    def session(*args, **kwargs):
        yield Client()

    monkeypatch.setattr("any2api_automation.providers.deepseek._session", session)

    assert _is_waf_challenge(Response()) is True
    with pytest.raises(_BrowserReauthenticationRequired):
        _reauthenticate_sync(
            {},
            {
                "email": "same@example.test",
                "password": "Password1!",
                "device_id": "device",
            },
        )


@pytest.mark.asyncio
async def test_reauthentication_falls_back_to_isolated_browser(monkeypatch) -> None:
    def http_login(*args, **kwargs):
        raise _BrowserReauthenticationRequired

    def browser_flow(flow, **kwargs):
        assert kwargs["preferred"] == "patchright"
        assert kwargs["payload"]["proxy_check_url"].startswith("https://")
        return BrowserResult(
            external_id="user",
            email="same@example.test",
            credential={"token": "replacement", "birthday_status": "set"},
            metadata={"reauthentication_transport": "browser"},
            ready_for_inference=False,
        )

    monkeypatch.setattr("any2api_automation.providers.deepseek._reauthenticate_sync", http_login)
    monkeypatch.setattr("any2api_automation.providers.deepseek.run_browser_flow", browser_flow)

    result = await DeepseekAutomationProvider().reauthenticate(
        {
            "credential": {
                "email": "same@example.test",
                "password": "Password1!",
                "device_id": "device",
            }
        }
    )

    assert result["healthy"] is True
    assert result["ready_for_inference"] is False
    assert result["credential_patch"]["token"] == "replacement"
    assert result["metadata_patch"]["authentication"] == "password_browser"


def test_hcaptcha_warmup_requires_official_main_feature() -> None:
    class Response:
        status = 200
        url = "https://chat.deepseek.com/api/v0/client/settings?did=device&scope=main"

        def json(self):
            return {
                "code": 0,
                "data": {
                    "biz_code": 0,
                    "biz_data": {"settings": {"chat_hcaptcha": {"value": True}}},
                },
            }

    class ResponseInfo:
        value = Response()

    class Expected:
        def __enter__(self):
            return ResponseInfo()

        def __exit__(self, *args):
            return None

    class Page:
        def expect_response(self, predicate, timeout):
            assert predicate(Response()) is True
            assert timeout == 120_000
            return Expected()

        def goto(self, url, **kwargs):
            assert url.endswith("/sign_in")

    _warm_up_hcaptcha(Page(), "https://chat.deepseek.com")


def test_hcaptcha_completion_is_bound_to_provider_response() -> None:
    challenge = DeepseekHcaptchaChallenge()

    challenge.solve(object(), completed=lambda: True)

    assert challenge.last_diagnostic == "provider_response"


def test_hcaptcha_waits_for_delayed_provider_response(monkeypatch) -> None:
    clock = {"now": 0.0}

    class Page:
        def wait_for_timeout(self, milliseconds):
            clock["now"] += milliseconds / 1000

    monkeypatch.setattr(
        "any2api_automation.providers.deepseek_challenge.time.monotonic",
        lambda: clock["now"],
    )

    completed = _wait_for_completion(
        Page(),
        lambda: clock["now"] >= 1.0,
        timeout_seconds=5.0,
    )

    assert completed is True
    assert clock["now"] == pytest.approx(1.0)


def test_hcaptcha_task_crop_excludes_prompt_header_and_maps_back_to_surface() -> None:
    image = Image.new("RGB", (500, 470), "white")
    draw = ImageDraw.Draw(image)
    for y in range(135, 455, 20):
        for x in range(10, 490, 20):
            color = (30, 120, 210) if (x + y) // 20 % 2 else (230, 90, 50)
            draw.rectangle((x, y, x + 19, y + 19), fill=color)
    encoded = io.BytesIO()
    image.save(encoded, format="PNG")

    task = _extract_task_image(encoded.getvalue())

    assert task is not None
    assert task.top > 0.2
    assert task.height > 0.5
    with Image.open(io.BytesIO(task.image)) as cropped:
        assert cropped.width > 450
        assert cropped.height > 280


def test_hcaptcha_evidence_contains_only_normalized_prompt_actions_and_artifact() -> None:
    evidence = _challenge_evidence(
        " Move the wheel: into the empty space. ",
        "deepseek-hcaptcha-fixture.png",
        [
            VisualAction(type="click", at=(0.25, 0.5)),
            VisualAction(type="drag", start=(0.1, 0.2), end=(0.8, 0.7)),
        ],
    )

    assert evidence == (
        "prompt=Move the wheel; into the empty space.:"
        "actions=click(0.250,0.500),drag(0.100,0.200;0.800,0.700):"
        "artifact=deepseek-hcaptcha-fixture.png"
    )


@pytest.mark.parametrize(
    "instruction",
    (
        "Drag one of the animals into the empty spot to complete the pattern",
        "Place the correct animal into the empty spot to complete the pattern",
    ),
)
def test_hcaptcha_animal_matrix_prompt_encodes_the_observed_single_drag_rule(
    instruction: str,
) -> None:
    prompt = _solver_prompt(instruction)

    assert "Return exactly ONE drag" in prompt
    assert "same species row" in prompt
    assert "other candidate is a decoy" in prompt
    assert "multiple drag actions only when" in prompt


def test_hcaptcha_other_prompts_do_not_receive_the_animal_matrix_rule() -> None:
    prompt = _solver_prompt("Select all matching images")

    assert "Return exactly ONE drag" not in prompt


def test_hcaptcha_animal_matrix_actions_snap_to_candidate_and_grid_centers() -> None:
    prompt = "Drag one of the animals into the empty spot to complete the pattern"
    samples = (
        VisualAction(type="drag", start=(0.11, 0.15), end=(0.43, 0.60)),
        VisualAction(type="drag", start=(0.08, 0.17), end=(0.25, 0.625)),
        VisualAction(type="drag", start=(0.11, 0.15), end=(0.39, 0.68)),
        VisualAction(type="drag", start=(0.11, 0.15), end=(0.43, 0.62)),
    )

    normalized = [_normalize_actions(prompt, [action])[0] for action in samples]

    assert normalized == [VisualAction(type="drag", start=(0.11, 0.14), end=(0.40, 0.64))] * 4


def test_hcaptcha_animal_matrix_targets_are_restricted_to_cv_empty_cells() -> None:
    prompt = "Place the correct animal into the empty spot to complete the pattern"
    action = VisualAction(type="drag", start=(0.11, 0.14), end=(0.88, 0.64))

    normalized = _normalize_actions(prompt, [action], ((0.40, 0.14), (0.40, 0.64)))

    assert normalized == [VisualAction(type="drag", start=(0.11, 0.14), end=(0.40, 0.64))]


def test_hcaptcha_animal_matrix_cv_detects_low_detail_empty_cells() -> None:
    prompt = "Drag one of the animals into the empty spot to complete the pattern"
    image = Image.new("RGB", (484, 336), (45, 35, 55))
    draw = ImageDraw.Draw(image)
    empty = {(0.88, 0.14), (0.40, 0.64)}
    for y in (0.14, 0.39, 0.64, 0.88):
        for x in (0.40, 0.56, 0.72, 0.88):
            center_x, center_y = round(x * image.width), round(y * image.height)
            if (x, y) in empty:
                draw.rectangle(
                    (center_x - 22, center_y - 22, center_x + 22, center_y + 22),
                    fill=(55, 45, 65),
                )
                continue
            for offset in range(-22, 23, 4):
                color = (230, 210, 80) if offset % 8 == 0 else (40, 190, 120)
                draw.line(
                    (center_x - 22, center_y + offset, center_x + 22, center_y - offset),
                    fill=color,
                    width=2,
                )
    encoded = io.BytesIO()
    image.save(encoded, format="PNG")

    targets = _animal_matrix_empty_targets(prompt, encoded.getvalue())

    assert targets == ((0.88, 0.14), (0.40, 0.64))


def test_hcaptcha_action_snapping_does_not_change_other_challenges_or_grid_sources() -> None:
    action = VisualAction(type="drag", start=(0.5, 0.5), end=(0.8, 0.8))

    assert _normalize_actions("Select all boats", [action]) == [action]
    assert _normalize_actions(
        "Drag one of the animals into the empty spot to complete the pattern", [action]
    ) == [action]


def test_hcaptcha_post_action_artifact_is_best_effort(monkeypatch) -> None:
    monkeypatch.setattr(
        "any2api_automation.providers.deepseek_challenge.record_captcha_artifact",
        lambda label, image: f"{label}-{len(image)}.png",
    )

    class Surface:
        def screenshot(self, *, type):
            assert type == "png"
            return b"after"

    assert _surface_artifact(Surface(), "deepseek-after") == "deepseek-after-5.png"

    class ReplacedSurface:
        def screenshot(self, *, type):
            raise RuntimeError("frame replaced")

    assert _surface_artifact(ReplacedSurface(), "deepseek-after") == ""
