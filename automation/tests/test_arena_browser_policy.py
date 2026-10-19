import asyncio
import json
from copy import deepcopy
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest

from any2api_automation.browser_budget import BrowserProcessBudget
from any2api_automation.lifecycle.browser import BrowserResult, camoufox_config_from_options
from any2api_automation.lifecycle.mail import Mailbox
from any2api_automation.observability import OperationFailure
from any2api_automation.providers import arena, arena_browser, official_browser
from any2api_automation.providers.arena_browser import ArenaOfficialBrowserTransport
from any2api_automation.providers.arena_challenge import ArenaChallengeOutcome, ArenaChallengeResult
from any2api_automation.providers.runtime_rules import default_runtime_plan_for_provider


def _runtime():
    return ArenaOfficialBrowserTransport(
        "https://arena.ai",
        page_path="/text/direct?model_a=max",
        me_path="/api/me",
        chat_path="/nextjs-api/stream/create-evaluation",
    )


def _credential(backend="camoufox"):
    return {
        "arena_user_id": "account",
        "browser_execution_context": {
            "schema_version": 1,
            "backend": backend,
            "storage_state": {"cookies": [], "origins": []},
            "camoufox_config": {
                "navigator.platform": "Win32",
                "canvas:seed": 17,
                "fonts:spacing_seed": 19,
                "humanize": True,
            },
        },
    }


@pytest.mark.asyncio
async def test_readiness_and_reused_requests_do_not_depend_on_cursor_warmup(monkeypatch):
    page = SimpleNamespace(
        wait_for_timeout=AsyncMock(),
        mouse=SimpleNamespace(
            move=AsyncMock(side_effect=AssertionError("Cursor warmup must not run")),
            wheel=AsyncMock(side_effect=AssertionError("Cursor warmup must not run")),
        ),
    )
    terms = AsyncMock(return_value="absent")
    license_dialog = AsyncMock(return_value="absent")
    monkeypatch.setattr(arena_browser, "_ensure_arena_tou_consent", terms)
    monkeypatch.setattr(arena_browser, "_accept_arena_license_dialog_if_present", license_dialog)
    runtime = _runtime()
    await runtime.wait_until_ready(page, None)
    for _ in range(3):
        await runtime.before_stream_request(page)
    terms.assert_awaited_once_with(page)
    license_dialog.assert_awaited_once_with(page)
    page.mouse.move.assert_not_awaited()
    page.mouse.wheel.assert_not_awaited()


@pytest.mark.asyncio
@pytest.mark.parametrize("failure", ["terms", "license", "cancelled"])
async def test_readiness_preserves_consent_errors_and_cancellation(monkeypatch, caplog, failure):
    page = SimpleNamespace(wait_for_timeout=AsyncMock())
    terms = AsyncMock(return_value="absent")
    license_dialog = AsyncMock(return_value="absent")
    monkeypatch.setattr(arena_browser, "_ensure_arena_tou_consent", terms)
    monkeypatch.setattr(arena_browser, "_accept_arena_license_dialog_if_present", license_dialog)
    failed_consent = license_dialog if failure == "license" else terms
    failed_consent.side_effect = RuntimeError("private-page-context")
    if failure == "cancelled":
        failed_consent.side_effect = asyncio.CancelledError()
    runtime = _runtime()
    with caplog.at_level("DEBUG"):
        if failure == "cancelled":
            with pytest.raises(asyncio.CancelledError):
                await runtime.wait_until_ready(page, None)
        else:
            await runtime.wait_until_ready(page, None)
            assert f"arena_{failure} state=error error_type=RuntimeError" in caplog.text
            assert "private-page-context" not in caplog.text
    if failure != "license":
        license_dialog.assert_not_awaited()


@pytest.mark.asyncio
@pytest.mark.parametrize("saved_backend", ["patchright", "camoufox", None])
@pytest.mark.parametrize("launch_failure", [False, True])
async def test_arena_uses_only_camoufox_and_owns_its_session_until_cleanup(
    monkeypatch, saved_backend, launch_failure
):
    import camoufox.async_api

    credential = _credential(saved_backend)
    original = deepcopy(credential)
    budget = BrowserProcessBudget(1)
    monkeypatch.setattr(official_browser, "browser_process_budget", budget)
    monkeypatch.setattr(
        official_browser,
        "core_settings",
        lambda: SimpleNamespace(
            registration_headless=True,
            official_browser_session_pool_size=1,
            browser_cleanup_timeout_seconds=1,
        ),
    )
    tunnel = SimpleNamespace(release=Mock())
    monkeypatch.setattr(official_browser, "retain_proxy_tunnel", lambda _: tunnel)
    patchright = Mock(side_effect=AssertionError("Arena cannot launch Patchright"))
    monkeypatch.setattr(official_browser, "async_playwright", patchright)
    prepared = {
        "env": {
            "CAMOU_CONFIG_1": json.dumps(credential["browser_execution_context"]["camoufox_config"])
        }
    }
    prepare = Mock(return_value=prepared)
    monkeypatch.setattr(official_browser, "camoufox_launch_options", prepare)
    monkeypatch.setattr(official_browser, "official_build_id", AsyncMock(return_value="fixture"))
    monkeypatch.setattr(official_browser, "runtime_fingerprint", AsyncMock(return_value={}))
    page = SimpleNamespace(
        goto=AsyncMock(),
        expose_binding=AsyncMock(),
        add_init_script=AsyncMock(),
        is_closed=lambda: False,
    )
    context = SimpleNamespace(
        cookies=AsyncMock(return_value=[]),
        new_page=AsyncMock(return_value=page),
        storage_state=AsyncMock(return_value={"cookies": [], "origins": []}),
        close=AsyncMock(),
    )
    browser = SimpleNamespace(
        version="152.0.4", new_context=AsyncMock(return_value=context), close=AsyncMock()
    )
    manager = SimpleNamespace(__aenter__=AsyncMock(return_value=browser), __aexit__=AsyncMock())
    if launch_failure:
        manager.__aenter__.side_effect = TimeoutError("Camoufox unavailable")
    monkeypatch.setattr(camoufox.async_api, "AsyncCamoufox", Mock(return_value=manager))
    runtime = _runtime()
    runtime.wait_until_ready = AsyncMock()
    try:
        async with runtime.account_operation(credential):
            if launch_failure:
                with pytest.raises(TimeoutError, match="Camoufox unavailable"):
                    await runtime.session_for(
                        credential,
                        "http://proxy:8080",
                        default_runtime_plan_for_provider("arena").active,
                    )
            else:
                first = await runtime.session_for(
                    credential,
                    "http://proxy:8080",
                    default_runtime_plan_for_provider("arena").active,
                )
                patch = await runtime.credential_patch(first, credential)
                second = await runtime.session_for(
                    {**credential, **patch},
                    "http://proxy:8080",
                    default_runtime_plan_for_provider("arena").active,
                )
                assert first is second
                assert first.backend == "camoufox"
                assert patch["browser_execution_context"]["backend"] == "camoufox"
                assert (
                    patch["browser_execution_context"]["camoufox_config"]
                    == original["browser_execution_context"]["camoufox_config"]
                )
                assert browser.new_context.await_args.kwargs == {
                    "ignore_https_errors": True,
                    "storage_state": {"cookies": [], "origins": []},
                }
                manager.__aenter__.assert_awaited_once()
                assert budget.snapshot().in_use == 1
        prepare.assert_called_once_with(
            original["browser_execution_context"]["camoufox_config"],
            "http://proxy:8080",
            humanize=True,
            headless=False,
            exclude_default_addons=True,
        )
        patchright.assert_not_called()
        assert credential == original
    finally:
        await runtime.close()
    assert budget.snapshot().in_use == 0
    tunnel.release.assert_called_once()
    manager.__aexit__.assert_awaited_once()


@pytest.mark.parametrize("humanize", [False, True])
def test_camoufox_replay_keeps_device_seeds_and_applies_requested_cursor_policy(
    monkeypatch, humanize
):
    import camoufox.utils

    config = _credential()["browser_execution_context"]["camoufox_config"]
    config["humanize"] = False
    original = deepcopy(config)

    def generated(**options):
        options["config"]["canvas:seed"] = 999
        return {"env": {"CAMOU_CONFIG_1": "discard", "CAMOU_CONFIG_2": "discard"}}

    launch = Mock(side_effect=generated)
    monkeypatch.setattr(camoufox.utils, "launch_options", launch)
    monkeypatch.setattr(camoufox.utils, "get_target_os", lambda _: "windows")
    monkeypatch.setattr(
        camoufox.utils, "get_env_vars", lambda value, _: {"CAMOU_CONFIG_1": json.dumps(value)}
    )
    prepared = official_browser.camoufox_launch_options(
        config, "http://proxy:8080", humanize=humanize, headless=False
    )
    replay = camoufox_config_from_options(prepared)
    assert replay == {**original, "humanize": humanize}
    assert config == original
    assert prepared["proxy"] == {"server": "http://proxy:8080"}
    assert launch.call_args.kwargs["headless"] is False
    assert launch.call_args.kwargs["geoip"] is False
    assert launch.call_args.kwargs["proxy"] is None
    assert "CAMOU_CONFIG_2" not in prepared["env"]


@pytest.mark.parametrize("exclude_default_addons", [False, True])
def test_new_camoufox_identity_is_generated_with_native_surfaces_and_proxy_geoip(
    monkeypatch, exclude_default_addons
):
    import camoufox.utils

    launch = Mock(return_value={})
    monkeypatch.setattr(camoufox.utils, "launch_options", launch)
    official_browser.camoufox_launch_options(
        {},
        "http://proxy:8080",
        humanize=True,
        headless=False,
        exclude_default_addons=exclude_default_addons,
    )
    options = launch.call_args.kwargs
    assert options["os"] == "windows"
    assert options["geoip"] is True
    assert options["humanize"] is True
    assert options["headless"] is False
    assert options["proxy"] == {"server": "http://proxy:8080"}
    assert not any(name in options for name in ("block_webgl", "block_images", "fingerprint"))
    if exclude_default_addons:
        from camoufox.addons import DefaultAddons

        assert options["exclude_addons"] == list(DefaultAddons)
    else:
        assert "exclude_addons" not in options


@pytest.mark.asyncio
@pytest.mark.parametrize("invalid_config", ["broken", [], 42])
async def test_invalid_saved_fingerprint_fails_without_launching_a_new_identity(
    monkeypatch, invalid_config
):
    credential = _credential("patchright")
    credential["browser_execution_context"]["camoufox_config"] = invalid_config
    tunnel = Mock(side_effect=AssertionError("Invalid state must not acquire a proxy"))
    monkeypatch.setattr(official_browser, "retain_proxy_tunnel", tunnel)
    runtime = _runtime()
    try:
        async with runtime.account_operation(credential):
            with pytest.raises(TypeError, match="Camoufox configuration must be an object"):
                await runtime.session_for(
                    credential,
                    "http://proxy:8080",
                    default_runtime_plan_for_provider("arena").active,
                )
        tunnel.assert_not_called()
    finally:
        await runtime.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("saved_humanize", [False, True])
@pytest.mark.parametrize("saved_offset", [0, 2])
async def test_reauthentication_reuses_account_fingerprint_storage_and_proxy(
    monkeypatch, saved_humanize, saved_offset
):
    provider = arena.ArenaAutomationProvider()
    credential = {
        **_credential(),
        "email": "account@example.test",
        "password": "fixture-password",
        "proxy_affinity_key": "saved-node-affinity",
        "proxy_node_offset": saved_offset,
    }
    credential["browser_execution_context"]["camoufox_config"]["humanize"] = saved_humanize
    original = deepcopy(credential)
    result = BrowserResult(
        external_id="account", email="account@example.test", credential=deepcopy(credential)
    )
    execute = AsyncMock(return_value=result)
    monkeypatch.setattr(arena, "run_blocking", execute)
    monkeypatch.setattr(arena, "mail_client", lambda _: None)
    try:
        response = await provider.reauthenticate(
            {"credential": credential, "proxy_affinity_key": "different", "proxy_node_offset": 3}
        )
        options = execute.await_args.kwargs
        assert options["preferred"] == "camoufox"
        assert options["fallback"] is None
        assert options["launch_profile"].headless is False
        assert options["launch_profile"].humanize is True
        assert options["launch_profile"].camoufox_exclude_default_addons is True
        assert options["launch_profile"].camoufox_config == {
            **original["browser_execution_context"]["camoufox_config"],
            "humanize": True,
        }
        assert options["context_profile"].options("camoufox") == {
            "ignore_https_errors": True,
            "storage_state": {"cookies": [], "origins": []},
        }
        assert options["payload"]["proxy_affinity_key"] == "saved-node-affinity"
        assert options["payload"]["proxy_node_offset"] == saved_offset
        assert response["credential"]["proxy_node_offset"] == saved_offset
        assert response["credential_patch"]["proxy_node_offset"] == saved_offset
        assert options["payload"]["strict_proxy_affinity"] is True
        assert credential == original
        assert provider.manifest.fallback_backend is None
        assert provider.browser_context_profile().options("camoufox") == {
            "ignore_https_errors": True
        }
    finally:
        await provider.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("success_attempt", [1, 2])
async def test_registration_persists_the_effective_proxy_context(monkeypatch, success_attempt):
    provider = arena.ArenaAutomationProvider()
    mail = SimpleNamespace(
        create_address=AsyncMock(return_value=Mailbox("synthetic@example.test", "fixture-mail")),
        message_ids_sync=lambda _: set(),
    )
    monkeypatch.setattr(arena, "mail_client", lambda *args, **kwargs: mail)
    monkeypatch.setattr(arena, "create_mail_client", lambda *args, **kwargs: mail)
    attempts = []

    def run_flow(*args, **kwargs):
        attempts.append(kwargs["payload"])
        if len(attempts) < success_attempt:
            raise OperationFailure(
                code="arena_domain_rejected", stage="form_submitted", message="fixture rejection"
            )
        return BrowserResult(
            external_id="synthetic",
            email="synthetic@example.test",
            credential={"cookie": "fixture-cookie"},
        )

    async def run(function, *args, **kwargs):
        return function(*args, **kwargs)

    monkeypatch.setattr(arena, "run_browser_flow", run_flow)
    monkeypatch.setattr(arena, "run_blocking", run)
    payload = {"password": "FixturePassword123!", "proxy_node_offset": 2}
    original = deepcopy(payload)
    try:
        result = await provider.register(payload)
        assert result["credential"]["proxy_affinity_key"] == attempts[-1]["proxy_affinity_key"]
        assert result["credential"]["proxy_node_offset"] == 2 + success_attempt - 1
        assert attempts[-1]["strict_proxy_affinity"] is True
        assert payload == original
    finally:
        await provider.close()


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "outcome,should_finish",
    [
        (ArenaChallengeOutcome.EXHAUSTED, True),
        (ArenaChallengeOutcome.INVALID, True),
        (ArenaChallengeOutcome.SUBMITTED, False),
        (ArenaChallengeOutcome.CLOSED, False),
    ],
)
async def test_challenge_failure_finishes_only_the_pending_request_widget(
    monkeypatch, outcome, should_finish
):
    runtime = _runtime()
    monkeypatch.setattr(
        runtime,
        "_solve_recaptcha_v2",
        AsyncMock(return_value=ArenaChallengeResult(outcome, 3, 0)),
    )
    evaluate = AsyncMock()
    monkeypatch.setattr(arena_browser, "evaluate_in_page_world", evaluate)
    try:
        await runtime._handle_page_recaptcha_v2(object(), "current-request")
        assert evaluate.await_count == int(should_finish)
        if should_finish:
            assert evaluate.await_args.args[-1] == {
                "requestId": "current-request",
                "reason": outcome.value,
            }
            assert "finishChallenge" in evaluate.await_args.args[1]
            assert ".abort(" not in evaluate.await_args.args[1]
    finally:
        await runtime.close()
