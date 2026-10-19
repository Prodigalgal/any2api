import json
from copy import deepcopy
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest

from any2api_automation.browser_budget import BrowserProcessBudget
from any2api_automation.lifecycle.browser import BrowserResult, camoufox_config_from_options
from any2api_automation.providers import arena, official_browser
from any2api_automation.providers.arena_browser import ArenaOfficialBrowserTransport
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


def test_new_camoufox_identity_is_generated_with_native_surfaces_and_proxy_geoip(monkeypatch):
    import camoufox.utils

    launch = Mock(return_value={})
    monkeypatch.setattr(camoufox.utils, "launch_options", launch)
    official_browser.camoufox_launch_options({}, "http://proxy:8080", humanize=True, headless=False)
    options = launch.call_args.kwargs
    assert options["os"] == "windows"
    assert options["geoip"] is True
    assert options["humanize"] is True
    assert options["headless"] is False
    assert options["proxy"] == {"server": "http://proxy:8080"}
    assert not any(name in options for name in ("block_webgl", "block_images", "fingerprint"))


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
async def test_reauthentication_reuses_account_fingerprint_storage_and_proxy(
    monkeypatch, saved_humanize
):
    provider = arena.ArenaAutomationProvider()
    credential = {
        **_credential(),
        "email": "account@example.test",
        "password": "fixture-password",
        "proxy_affinity_key": "saved-node-affinity",
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
        await provider.reauthenticate(
            {"credential": credential, "proxy_affinity_key": "different", "proxy_node_offset": 3}
        )
        options = execute.await_args.kwargs
        assert options["preferred"] == "camoufox"
        assert options["fallback"] is None
        assert options["launch_profile"].headless is False
        assert options["launch_profile"].humanize is True
        assert options["launch_profile"].camoufox_config == {
            **original["browser_execution_context"]["camoufox_config"],
            "humanize": True,
        }
        assert options["context_profile"].options("camoufox") == {
            "ignore_https_errors": True,
            "storage_state": {"cookies": [], "origins": []},
        }
        assert options["payload"]["proxy_affinity_key"] == "saved-node-affinity"
        assert options["payload"]["proxy_node_offset"] == 0
        assert options["payload"]["strict_proxy_affinity"] is True
        assert credential == original
        assert provider.manifest.fallback_backend is None
        assert provider.browser_context_profile().options("camoufox") == {
            "ignore_https_errors": True
        }
    finally:
        await provider.close()
