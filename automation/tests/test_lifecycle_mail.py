import re
from unittest.mock import patch

from any2api_automation.lifecycle.mail import (
    TempMailClient,
    _available_domains,
    _mail_text,
    _match_value,
)


def test_mail_text_extracts_multipart_body_without_logging_secrets() -> None:
    raw = """From: no-reply@example.test
Subject: Verify your account
Content-Type: multipart/alternative; boundary=part

--part
Content-Type: text/plain; charset=utf-8

Your verification code is 472901.
--part--
"""
    assert "472901" in _mail_text({"raw": raw})


def test_mail_text_includes_html_activation_links() -> None:
    value = _mail_text({"html": '<a href="https://chat.qwen.ai/activate?id=abc">Activate</a>'})
    assert "https://chat.qwen.ai/activate?id=abc" in value


def test_mail_headers_match_cloudflare_temp_email_contract() -> None:
    client = TempMailClient(
        base_url="https://mail.example.test",
        admin_password="admin-secret",
        site_password="site-secret",
        domain="example.test",
    )

    assert client._headers() == {
        "Accept": "application/json",
        "Content-Type": "application/json",
        "x-custom-auth": "site-secret",
    }
    assert client._headers(jwt="mail-jwt")["Authorization"] == "Bearer mail-jwt"
    assert client._headers(admin=True)["x-admin-auth"] == "admin-secret"


def test_match_value_returns_the_populated_alternative_group() -> None:
    match = re.search(r"code: (\d{4,8})|fallback (\d{6})", "fallback 472901")

    assert match is not None
    assert _match_value(match) == "472901"


def test_available_domains_normalizes_deduplicates_and_filters_disabled_entries() -> None:
    assert _available_domains(
        [
            " MAIL-ONE.EXAMPLE.TEST ",
            {"name": "mail-two.example.test", "enabled": True},
            {"domain": "disabled.example", "disabled": True},
            {"name": "inactive.example", "active": False},
            "mail-one.example.test",
            "",
        ]
    ) == ["mail-one.example.test", "mail-two.example.test"]


def test_default_domain_randomly_selects_an_available_domain(monkeypatch) -> None:
    class Response:
        def raise_for_status(self) -> None:
            return None

        def json(self) -> dict[str, list[str]]:
            return {"domains": ["mail-one.example.test", "mail-two.example.test"]}

    class AsyncClient:
        def __init__(self, **_kwargs) -> None:
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *_args) -> None:
            return None

        async def get(self, *_args, **_kwargs) -> Response:
            return Response()

    monkeypatch.setattr("any2api_automation.lifecycle.mail.httpx.AsyncClient", AsyncClient)
    client = TempMailClient(base_url="https://mail.example.test", admin_password="secret")

    with patch(
        "any2api_automation.lifecycle.mail.secrets.choice",
        return_value="mail-two.example.test",
    ):
        import asyncio

        assert asyncio.run(client._default_domain()) == "mail-two.example.test"
