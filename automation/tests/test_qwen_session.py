from __future__ import annotations

from unittest.mock import AsyncMock

import pytest
from pydantic import ValidationError

from any2api_automation.providers.qwen_fingerprint import new_qwen_fingerprint
from any2api_automation.providers.qwen_risk import (
    NativeBrowserRequest,
    QwenNativeBrowserTransport,
    _AccountBrowserSession,
)
from any2api_automation.providers.qwen_session import (
    browser_state_digest,
    normalize_browser_state,
)

BASE_URL = "https://chat.qwen.ai"


def _state(cookie_value: str = "device") -> dict[str, object]:
    return {
        "cookies": [
            {
                "name": "cna",
                "value": cookie_value,
                "domain": ".qwen.ai",
                "path": "/",
                "expires": -1,
                "httpOnly": False,
                "secure": True,
                "sameSite": "Lax",
            },
            {
                "name": "cbc",
                "value": "risk",
                "domain": ".alibaba.com",
                "path": "/",
                "expires": -1,
                "httpOnly": True,
                "secure": True,
                "sameSite": "None",
            },
        ],
        "origins": [
            {
                "origin": BASE_URL,
                "localStorage": [{"name": "token", "value": "account-token"}],
                "indexedDB": [],
            }
        ],
    }


def test_qwen_browser_state_preserves_qwen_and_baxia_storage() -> None:
    normalized = normalize_browser_state(_state(), BASE_URL)

    assert normalized["schema_version"] == 1
    assert {cookie["domain"] for cookie in normalized["storage_state"]["cookies"]} == {
        ".qwen.ai",
        ".alibaba.com",
    }
    assert normalized["storage_state"]["origins"][0]["indexedDB"] == []


def test_qwen_browser_state_preserves_the_official_aplus_device_blob() -> None:
    state = _state()
    state["origins"][0]["localStorage"].append(
        {"name": "APLUS_CORE_fixture", "value": "x" * 200_000}
    )

    normalized = normalize_browser_state(state, BASE_URL)

    item = normalized["storage_state"]["origins"][0]["localStorage"][-1]
    assert item["name"] == "APLUS_CORE_fixture"
    assert len(item["value"]) == 200_000


def test_qwen_browser_state_rejects_untrusted_cookie_domains() -> None:
    state = _state()
    state["cookies"][0]["domain"] = ".example.com"

    with pytest.raises(ValueError, match="cross-origin cookie"):
        normalize_browser_state(state, BASE_URL)


def test_qwen_native_request_rejects_untrusted_persisted_state() -> None:
    state = _state()
    state["origins"][0]["origin"] = "https://example.com"

    with pytest.raises(ValidationError, match="cross-origin storage"):
        NativeBrowserRequest(
            account_id="11111111-1111-4111-8111-111111111111",
            path="/api/v2/chats/new",
            bearer_token="token-value-that-is-long-enough",
            browser_state=state,
        )


@pytest.mark.asyncio
async def test_qwen_native_transport_returns_only_changed_browser_state() -> None:
    class FakeContext:
        async def storage_state(self, **_kwargs: object) -> dict[str, object]:
            return _state()

    transport = QwenNativeBrowserTransport()
    transport._user_agent = "Mozilla/5.0 Chrome/146.0.0.0"
    transport._browser_profile = "chrome146"
    session = _AccountBrowserSession("account", FakeContext(), object(), "current")
    request = NativeBrowserRequest(
        account_id="11111111-1111-4111-8111-111111111111",
        path="/api/v2/chats/new",
        bearer_token="token-value-that-is-long-enough",
    )

    patch = await transport._credential_patch(session, request)
    unchanged = await transport._credential_patch(
        session, request.model_copy(update={"browser_state": patch["browser_state"]})
    )

    assert patch["cookies"] == {"cna": "device", "cbc": "risk"}
    assert patch["browser_profile"] == "chrome146"
    assert unchanged == {}


@pytest.mark.asyncio
async def test_qwen_native_transport_restores_isolated_context_per_account() -> None:
    class FakeCdp:
        async def send(self, _method: str, _params: dict[str, str]) -> None:
            return None

    class FakePage:
        async def add_init_script(self, _script: str) -> None:
            return None

    class FakeContext:
        def __init__(self) -> None:
            self.page = FakePage()

        async def add_init_script(self, _script: str) -> None:
            return None

        async def add_cookies(self, _cookies: list[dict[str, str]]) -> None:
            return None

        async def new_page(self) -> object:
            return self.page

        async def new_cdp_session(self, _page: object) -> FakeCdp:
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

    browser = FakeBrowser()
    transport = QwenNativeBrowserTransport()
    transport._browser = browser
    transport._browser_profile = "chrome146"
    transport._user_agent = "Mozilla/5.0 Chrome/146.0.0.0"
    transport._load_page_runtime = AsyncMock()
    fingerprint = new_qwen_fingerprint("patchright")
    first = NativeBrowserRequest(
        account_id="11111111-1111-4111-8111-111111111111",
        path="/api/v2/chats/new",
        bearer_token="first-token-value-that-is-long-enough",
        browser_state=_state("first-device"),
        browser_fingerprint=fingerprint,
    )
    second = NativeBrowserRequest(
        account_id="22222222-2222-4222-8222-222222222222",
        path="/api/v2/chats/new",
        bearer_token="second-token-value-that-is-long-enough",
        browser_state=_state("second-device"),
        browser_fingerprint=fingerprint,
    )

    first_session = await transport._session_for(first)
    second_session = await transport._session_for(second)

    assert first_session.context is not second_session.context
    assert browser.options[0]["storage_state"]["cookies"][0]["value"] == "first-device"
    assert browser.options[1]["storage_state"]["cookies"][0]["value"] == "second-device"

    first_session.state_digest = browser_state_digest(_state("request-rotated"), BASE_URL)
    assert await transport._session_for(first) is first_session

    external_rotation = first.model_copy(
        update={"browser_state": normalize_browser_state(_state("external"), BASE_URL)}
    )
    replaced = await transport._session_for(external_rotation)
    assert replaced is not first_session
