from copy import deepcopy
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from any2api_automation.providers import official_browser
from any2api_automation.providers.arena_browser import ArenaOfficialBrowserTransport


def _runtime():
    return ArenaOfficialBrowserTransport(
        "https://arena.ai",
        page_path="/text/direct?model_a=max",
        me_path="/api/me",
        chat_path="/nextjs-api/stream/create-evaluation",
    )


def _risk_cookie(**overrides):
    return {
        "name": "_GRECAPTCHA",
        "value": "synthetic-risk-state",
        "domain": "www.google.com",
        "path": "/recaptcha",
        "secure": True,
        "httpOnly": True,
        "sameSite": "None",
        **overrides,
    }


@pytest.mark.asyncio
@pytest.mark.parametrize("domain", ["www.google.com", ".www.google.com", "www.recaptcha.net"])
async def test_arena_saves_and_restores_only_its_own_official_risk_cookie(monkeypatch, domain):
    cookie = _risk_cookie(domain=domain)
    arena_cookie = {"name": "arena-auth-prod-v1", "value": "synthetic", "domain": ".arena.ai"}
    arena_origin = {"origin": "https://arena.ai", "localStorage": []}
    state = {
        "cookies": [cookie, arena_cookie, _risk_cookie(name="NID")],
        "origins": [arena_origin, {"origin": "https://www.google.com", "localStorage": []}],
    }
    original = deepcopy(state)
    credential = {
        "arena_user_id": "synthetic-account",
        "browser_execution_context": {
            "schema_version": 1,
            "backend": "camoufox",
            "storage_state": {"cookies": [], "origins": []},
        },
    }
    before = deepcopy(credential)
    fingerprint = {"canvas:seed": 17}
    session = SimpleNamespace(
        context=SimpleNamespace(storage_state=AsyncMock(return_value=state)),
        page=object(),
        backend="camoufox",
        camoufox_config=fingerprint,
    )
    monkeypatch.setattr(official_browser, "runtime_fingerprint", AsyncMock(return_value={}))
    runtime = _runtime()
    try:
        patch = await runtime.credential_patch(session, credential)
        restored = runtime._storage_state(patch["browser_execution_context"])
        assert restored == {"cookies": [arena_cookie, cookie], "origins": [arena_origin]}
        assert patch["browser_execution_context"]["camoufox_config"] == fingerprint
        assert state == original
        assert credential == before
        restored["cookies"][-1]["value"] = "changed-copy"
        assert patch["browser_execution_context"]["storage_state"]["cookies"][-1] == cookie
        assert state == original
    finally:
        await runtime.close()


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "overrides",
    [
        {"name": "NID"},
        {"name": "SID"},
        {"name": "recaptchaToken"},
        {"domain": "google.com"},
        {"domain": "accounts.google.com"},
        {"domain": "www.google.com.attacker.test"},
        {"domain": "attacker-www.google.com"},
        {"domain": "..www.google.com"},
        {"domain": None},
        {"domain": ["www.google.com"]},
        {"path": "/"},
        {"path": "/recaptcha-attacker"},
        {"secure": False},
        {"secure": "true"},
        {"secure": 1},
    ],
)
async def test_arena_rejects_unrelated_or_unsafe_verification_cookie_state(overrides):
    state = {"cookies": [_risk_cookie(**overrides)], "origins": []}
    runtime = _runtime()
    try:
        assert runtime.filter_storage_state(state) == {"cookies": [], "origins": []}
    finally:
        await runtime.close()


@pytest.mark.asyncio
async def test_arena_filter_does_not_merge_accounts_or_invent_missing_cookie_state():
    first = {"cookies": [_risk_cookie(value="account-one")], "origins": []}
    second = {"cookies": [_risk_cookie(value="account-two")], "origins": []}
    runtime = _runtime()
    try:
        assert runtime.filter_storage_state(first) == first
        assert runtime.filter_storage_state(second) == second
        assert runtime.filter_storage_state({}) == {"cookies": [], "origins": []}
        assert runtime.filter_storage_state({"cookies": [None, "broken"], "origins": []}) == {
            "cookies": [],
            "origins": [],
        }
        assert runtime.filter_storage_state(first) == first
    finally:
        await runtime.close()
