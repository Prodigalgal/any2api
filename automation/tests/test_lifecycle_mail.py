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


def test_mail_text_includes_body_text_and_body_html() -> None:
    value = _mail_text(
        {
            "subject": "Verification",
            "body_text": "Your code is 889922.",
            "body_html": "<p>Your code is 889922.</p>",
        }
    )
    assert "889922" in value


def test_create_mail_client_instantiates_correct_driver() -> None:
    from any2api_automation.lifecycle.mail import (
        MailTmClient,
        TempMailClient,
        TempMailIoClient,
        create_mail_client,
    )

    c1 = create_mail_client("tempmail_io")
    assert isinstance(c1, TempMailIoClient)

    c2 = create_mail_client("mail_tm")
    assert isinstance(c2, MailTmClient)

    c3 = create_mail_client("cloudflare", base_url="https://mail.test", admin_password="pass")
    assert isinstance(c3, TempMailClient)


def test_temp_mail_io_create_and_fetch_mails(monkeypatch) -> None:
    import asyncio

    from any2api_automation.lifecycle.mail import TempMailIoClient

    class FakeResponse:
        def __init__(self, data: dict, status: int = 200) -> None:
            self._data = data
            self.status_code = status

        def raise_for_status(self) -> None:
            pass

        def json(self) -> dict:
            return self._data

    class FakeAsyncClient:
        def __init__(self, **_kwargs) -> None:
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *_args) -> None:
            pass

        async def post(self, url: str, **kwargs) -> FakeResponse:
            if "email/new" in url:
                return FakeResponse({"email": "test@tanpony.com", "token": "tok123"})
            raise NotImplementedError(url)

        async def get(self, url: str, **kwargs) -> FakeResponse:
            if "domains" in url:
                return FakeResponse({"domains": [{"name": "tanpony.com", "type": "public"}]})
            if "messages" in url:
                return FakeResponse([{"id": "msg1", "body_text": "Your Qwen OTP is 392019"}])
            raise NotImplementedError(url)

    monkeypatch.setattr("any2api_automation.lifecycle.mail.httpx.AsyncClient", FakeAsyncClient)

    client = TempMailIoClient(base_url="https://api.internal.temp-mail.io/api/v3")
    mailbox = asyncio.run(client.create_address("myname"))
    assert mailbox.address == "test@tanpony.com"
    assert mailbox.jwt == "tok123"

    code = asyncio.run(client.wait_for_code(mailbox, timeout=5))
    assert code == "392019"


def test_mail_tm_create_and_fetch_mails(monkeypatch) -> None:
    import asyncio

    from any2api_automation.lifecycle.mail import MailTmClient

    class FakeResponse:
        def __init__(self, data: dict, status: int = 200) -> None:
            self._data = data
            self.status_code = status

        def raise_for_status(self) -> None:
            pass

        def json(self) -> dict:
            return self._data

    class FakeAsyncClient:
        def __init__(self, **_kwargs) -> None:
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *_args) -> None:
            pass

        async def post(self, url: str, **kwargs) -> FakeResponse:
            if "accounts" in url:
                return FakeResponse({"id": "acc1", "address": "test@maxxspace.com"})
            if "token" in url:
                return FakeResponse({"token": "jwt-tok-456"})
            raise NotImplementedError(url)

        async def get(self, url: str, **kwargs) -> FakeResponse:
            if "domains" in url:
                return FakeResponse(
                    {"hydra:member": [{"domain": "maxxspace.com", "isActive": True}]}
                )
            if "messages/msg1" in url:
                return FakeResponse({"id": "msg1", "text": "Your OTP code is 654321"})
            if "messages" in url:
                return FakeResponse({"hydra:member": [{"id": "msg1", "intro": "Your OTP code"}]})
            raise NotImplementedError(url)

    monkeypatch.setattr("any2api_automation.lifecycle.mail.httpx.AsyncClient", FakeAsyncClient)

    client = MailTmClient(base_url="https://api.mail.tm")
    mailbox = asyncio.run(client.create_address("myname"))
    assert mailbox.address.endswith("@maxxspace.com")
    assert mailbox.jwt == "jwt-tok-456"

    code = asyncio.run(client.wait_for_code(mailbox, timeout=5))
    assert code == "654321"


def test_mail_account_resolves_default_driver() -> None:
    from any2api_automation.lifecycle.account import mail_client
    from any2api_automation.lifecycle.mail import TempMailIoClient

    client = mail_client({}, default_driver="tempmail_io")
    assert isinstance(client, TempMailIoClient)


def test_create_mail_client_sanitizes_unrelated_base_url() -> None:
    from any2api_automation.lifecycle.mail import TempMailIoClient, create_mail_client

    client = create_mail_client("tempmail_io", base_url="https://apimail.custom.test")
    assert isinstance(client, TempMailIoClient)
    assert client.base_url == "https://api.internal.temp-mail.io/api/v3"


def test_prepare_registration_falls_back_on_error(monkeypatch) -> None:
    import asyncio

    from any2api_automation.lifecycle.account import prepare_registration
    from any2api_automation.lifecycle.mail import Mailbox, MailTmClient, TempMailIoClient

    async def fail_create(self, local_part=None):
        raise RuntimeError("temp-mail.io is down")

    async def success_create(self, local_part=None):
        return Mailbox("fallback@maxxspace.com", "fallback-jwt")

    monkeypatch.setattr(TempMailIoClient, "create_address", fail_create)
    monkeypatch.setattr(MailTmClient, "create_address", success_create)

    mail, mailbox, password = asyncio.run(prepare_registration({}, default_driver="tempmail_io"))
    assert isinstance(mail, MailTmClient)
    assert mailbox.address == "fallback@maxxspace.com"
    assert len(password) >= 12


def test_tempmail_plus_create_and_fetch_mails(monkeypatch) -> None:
    import asyncio

    from any2api_automation.lifecycle.mail import TempMailPlusClient, create_mail_client

    class FakeResponse:
        def __init__(self, data: dict | list, status_code: int = 200) -> None:
            self._data = data
            self.status_code = status_code

        def raise_for_status(self) -> None:
            pass

        def json(self) -> dict:
            return self._data

    class FakeAsyncClient:
        def __init__(self, **_kwargs) -> None:
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *_args) -> None:
            pass

        async def get(self, url: str, **_kwargs) -> FakeResponse:
            if "/mails/1001" in url:
                return FakeResponse(
                    {
                        "mail_id": 1001,
                        "text": "Please verify your email: https://arena.ai/nextjs-api/callback/email?token=sec_abc123",
                    }
                )
            if "/mails" in url:
                return FakeResponse({"mail_list": [{"mail_id": 1001, "subject": "Confirm Email"}]})
            raise NotImplementedError(url)

    monkeypatch.setattr("any2api_automation.lifecycle.mail.httpx.AsyncClient", FakeAsyncClient)

    client = create_mail_client("tempmail_plus")
    assert isinstance(client, TempMailPlusClient)

    mailbox = asyncio.run(client.create_address("arena_user"))
    assert mailbox.address.startswith("arena_user@")

    link = asyncio.run(client.wait_for_link(mailbox, host_pattern=r"arena\.ai", timeout=5))
    assert link == "https://arena.ai/nextjs-api/callback/email?token=sec_abc123"


def test_create_mail_client_random_driver() -> None:
    from any2api_automation.lifecycle.mail import (
        TempMailClient,
        TempMailIoClient,
        TempMailPlusClient,
        create_mail_client,
    )

    client = create_mail_client("random", base_url="https://apimail.test.example")
    assert isinstance(client, (TempMailClient, TempMailIoClient, TempMailPlusClient))

