from __future__ import annotations

import json
from copy import deepcopy
from unittest.mock import AsyncMock

import pytest

from any2api_automation.providers.qwen import _launch_profile_for_fingerprint
from any2api_automation.providers.qwen_fingerprint import (
    camoufox_launch_options,
    new_qwen_fingerprint,
    normalize_qwen_fingerprint,
    patchright_cdp_commands,
)
from any2api_automation.providers.qwen_risk import (
    NativeBrowserRequest,
    QwenNativeBrowserTransport,
)


def test_patchright_registration_fingerprints_are_dynamic_and_coherent() -> None:
    fingerprints = [new_qwen_fingerprint("patchright") for _ in range(24)]

    combinations = {
        (
            value["browser_profile"],
            value["screen"]["width"],
            value["hardware_concurrency"],
            value["color_scheme"],
        )
        for value in fingerprints
    }

    assert len({value["variant_id"] for value in fingerprints}) == len(fingerprints)
    assert len(combinations) > 1
    for value in fingerprints:
        major = value["browser_profile"].removeprefix("chrome")
        assert f"Chrome/{major}." in value["user_agent"]
        commands = dict(patchright_cdp_commands(value))
        metadata = commands["Network.setUserAgentOverride"]["userAgentMetadata"]
        assert metadata["fullVersion"] == f"{major}.0.0.0"
        assert commands["Emulation.setHardwareConcurrencyOverride"] == {
            "hardwareConcurrency": value["hardware_concurrency"]
        }


def test_camoufox_generated_fingerprint_replays_every_persisted_field() -> None:
    fingerprint = new_qwen_fingerprint("camoufox")
    prepared = camoufox_launch_options(
        fingerprint,
        headless=True,
        proxy_url="",
    )
    chunks = sorted(
        (int(name.rsplit("_", 1)[1]), value)
        for name, value in prepared["env"].items()
        if name.startswith("CAMOU_CONFIG_")
    )
    restored_config = json.loads("".join(str(value) for _, value in chunks))

    assert restored_config == fingerprint["camoufox_config"]
    assert restored_config["canvas:seed"] == fingerprint["camoufox_config"]["canvas:seed"]
    assert restored_config["audio:seed"] == fingerprint["camoufox_config"]["audio:seed"]
    assert "humanize" not in restored_config
    assert "humanize:maxTime" not in restored_config
    assert fingerprint["interaction_profile"]["camoufox_native_humanize"] is False
    for name, value in fingerprint["firefox_user_prefs"].items():
        assert prepared["firefox_user_prefs"][name] == value

    registration_profile = _launch_profile_for_fingerprint(fingerprint)
    assert registration_profile.humanize is False
    assert registration_profile.camoufox_config == fingerprint["camoufox_config"]


def test_camoufox_legacy_fingerprint_migrates_without_rotating_device_identity() -> None:
    current = new_qwen_fingerprint("camoufox")
    legacy = deepcopy(current)
    legacy["schema_version"] = 1
    legacy.pop("interaction_profile")
    legacy["camoufox_config"]["humanize"] = True
    legacy["camoufox_config"]["humanize:maxTime"] = 1.5

    migrated = normalize_qwen_fingerprint(legacy)

    assert migrated["schema_version"] == 2
    assert migrated["variant_id"] == current["variant_id"]
    assert migrated["user_agent"] == current["user_agent"]
    assert migrated["camoufox_config"]["canvas:seed"] == current["camoufox_config"]["canvas:seed"]
    assert migrated["camoufox_config"]["audio:seed"] == current["camoufox_config"]["audio:seed"]
    assert "humanize" not in migrated["camoufox_config"]
    assert "humanize:maxTime" not in migrated["camoufox_config"]


def test_qwen_fingerprint_rejects_profile_user_agent_drift() -> None:
    fingerprint = new_qwen_fingerprint("patchright")
    fingerprint["browser_profile"] = "chrome142"
    fingerprint["user_agent"] = (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/145.0.0.0 Safari/537.36"
    )

    with pytest.raises(ValueError, match="profile does not match"):
        normalize_qwen_fingerprint(fingerprint)


@pytest.mark.asyncio
async def test_legacy_account_keeps_one_generated_fingerprint_within_its_session() -> None:
    class FakeCdp:
        async def send(self, _method: str, _params: dict[str, str]) -> None:
            return None

    class FakePage:
        async def add_init_script(self, _script: str) -> None:
            return None

    class FakeContext:
        async def add_init_script(self, _script: str) -> None:
            return None

        async def add_cookies(self, _cookies: list[dict[str, str]]) -> None:
            return None

        async def new_page(self) -> FakePage:
            return FakePage()

        async def new_cdp_session(self, _page: FakePage) -> FakeCdp:
            return FakeCdp()

        async def close(self) -> None:
            return None

    class FakeBrowser:
        def __init__(self) -> None:
            self.options: list[dict[str, object]] = []

        async def new_context(self, **options: object) -> FakeContext:
            self.options.append(options)
            return FakeContext()

        def is_connected(self) -> bool:
            return True

    transport = QwenNativeBrowserTransport()
    transport._browser = FakeBrowser()
    transport._load_page_runtime = AsyncMock()
    fingerprint = new_qwen_fingerprint("patchright")
    with pytest.MonkeyPatch.context() as monkeypatch:
        monkeypatch.setattr(
            "any2api_automation.providers.qwen_risk.new_qwen_fingerprint",
            lambda _backend: fingerprint,
        )
        request = NativeBrowserRequest(
            account_id="11111111-1111-4111-8111-111111111111",
            path="/api/v2/chats/new",
            bearer_token="token-value-that-is-long-enough",
        )

        first = await transport._session_for(request)
        second = await transport._session_for(request)

    assert second is first
    assert first.browser_fingerprint
    assert first.fingerprint_digest
    assert len(transport._browser.options) == 1


@pytest.mark.asyncio
async def test_patchright_context_uses_the_transport_proxy_binding() -> None:
    class FakeCdp:
        async def send(self, _method: str, _params: dict[str, str]) -> None:
            return None

    class FakePage:
        async def add_init_script(self, _script: str) -> None:
            return None

    class FakeContext:
        async def add_init_script(self, _script: str) -> None:
            return None

        async def add_cookies(self, _cookies: list[dict[str, str]]) -> None:
            return None

        async def new_page(self) -> FakePage:
            return FakePage()

        async def new_cdp_session(self, _page: FakePage) -> FakeCdp:
            return FakeCdp()

        async def close(self) -> None:
            return None

    class FakeBrowser:
        def __init__(self) -> None:
            self.options: dict[str, object] = {}

        async def new_context(self, **options: object) -> FakeContext:
            self.options = options
            return FakeContext()

        def is_connected(self) -> bool:
            return True

    browser = FakeBrowser()
    fingerprint = new_qwen_fingerprint("patchright")
    transport = QwenNativeBrowserTransport()
    transport._browser = browser
    transport._proxy_binding = AsyncMock(return_value=("http://proxy.internal:8080", "binding-1"))
    transport._load_page_runtime = AsyncMock()
    request = NativeBrowserRequest(
        account_id="11111111-1111-4111-8111-111111111111",
        path="/api/v2/chats/new",
        bearer_token="token-value-that-is-long-enough",
        browser_fingerprint=fingerprint,
        transport_session_id="a" * 32,
    )

    session = await transport._session_for(request)

    assert browser.options["proxy"] == {"server": "http://proxy.internal:8080"}
    assert session.proxy_url == "http://proxy.internal:8080"
    assert session.proxy_binding_id == "binding-1"
