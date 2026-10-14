from __future__ import annotations

import asyncio
import email
import random
import re
import secrets
import string
import time
from collections.abc import Callable
from dataclasses import dataclass
from email import policy
from html import unescape
from typing import Any

import httpx

from ..config import settings


@dataclass(frozen=True)
class Mailbox:
    address: str
    jwt: str
    address_id: int | None = None


class BaseMailClient:
    """Abstract base for temporary disposable mail clients."""

    def __init__(
        self,
        *,
        request_timeout_seconds: float | None = None,
        poll_seconds: float | None = None,
        message_timeout_seconds: float | None = None,
    ) -> None:
        config = settings()
        self.timeout = float(request_timeout_seconds or 30)
        self.poll_seconds = float(poll_seconds or config.mail_poll_seconds)
        self.message_timeout_seconds = float(
            message_timeout_seconds or min(config.mail_timeout_seconds, 90.0)
        )

    async def create_address(self, local_part: str | None = None) -> Mailbox:
        raise NotImplementedError

    async def refresh(self, address: str) -> Mailbox:
        raise NotImplementedError

    async def wait_for_code(
        self,
        mailbox: Mailbox,
        *,
        pattern: str = r"(?<!\d)(\d{6})(?!\d)",
        timeout: float | None = None,
        predicate: Callable[[dict[str, Any]], bool] | None = None,
        seen_ids: set[str] | None = None,
    ) -> str:
        regex = re.compile(pattern, re.IGNORECASE)
        return await self._wait(mailbox, regex, timeout, predicate, seen_ids)

    async def wait_for_link(
        self,
        mailbox: Mailbox,
        *,
        host_pattern: str,
        timeout: float | None = None,
        seen_ids: set[str] | None = None,
    ) -> str:
        regex = re.compile(rf"https?://[^\s<>'\"]*{host_pattern}[^\s<>'\"]*", re.IGNORECASE)
        return unescape(await self._wait(mailbox, regex, timeout, None, seen_ids)).rstrip(".,);]")

    def wait_for_code_sync(
        self,
        mailbox: Mailbox,
        *,
        pattern: str = r"(?<!\d)(\d{6})(?!\d)",
        timeout: float | None = None,
        seen_ids: set[str] | None = None,
    ) -> str:
        return self._wait_sync(mailbox, re.compile(pattern, re.IGNORECASE), timeout, seen_ids)

    def wait_for_link_sync(
        self,
        mailbox: Mailbox,
        *,
        host_pattern: str,
        timeout: float | None = None,
        seen_ids: set[str] | None = None,
    ) -> str:
        regex = re.compile(rf"https?://[^\s<>'\"]*{host_pattern}[^\s<>'\"]*", re.IGNORECASE)
        return unescape(self._wait_sync(mailbox, regex, timeout, seen_ids)).rstrip(".,);]")

    def message_ids_sync(self, mailbox: Mailbox) -> set[str]:
        return {
            str(item.get("id") or item.get("message_id"))
            for item in self._fetch_mailbox_mails_sync(mailbox)
            if item.get("id") or item.get("message_id")
        }

    async def _fetch_mailbox_mails(self, mailbox: Mailbox) -> list[dict[str, Any]]:
        raise NotImplementedError

    def _fetch_mailbox_mails_sync(self, mailbox: Mailbox) -> list[dict[str, Any]]:
        raise NotImplementedError

    async def _wait(
        self,
        mailbox: Mailbox,
        regex: re.Pattern[str],
        timeout: float | None,
        predicate: Callable[[dict[str, Any]], bool] | None,
        seen_ids: set[str] | None,
    ) -> str:
        deadline = time.monotonic() + float(timeout or self.message_timeout_seconds)
        seen = set(seen_ids or ())
        polls = 0
        max_mail_count = 0
        last_error = "none"
        while time.monotonic() < deadline:
            polls += 1
            try:
                mails = await self._fetch_mailbox_mails(mailbox)
                max_mail_count = max(max_mail_count, len(mails))
                last_error = "none"
                for item in mails:
                    item_id = str(item.get("id") or item.get("message_id") or "")
                    if item_id and item_id in seen:
                        continue
                    if item_id:
                        seen.add(item_id)
                    if predicate and not predicate(item):
                        continue
                    match = regex.search(_mail_text(item))
                    if match:
                        return _match_value(match)
            except (httpx.HTTPError, ValueError) as error:
                last_error = type(error).__name__
            await asyncio.sleep(max(1, self.poll_seconds))
        raise TimeoutError(
            "temporary mailbox did not receive the expected message "
            f"(polls={polls}, max_mails={max_mail_count}, last_error={last_error})"
        )

    def _wait_sync(
        self,
        mailbox: Mailbox,
        regex: re.Pattern[str],
        timeout: float | None,
        seen_ids: set[str] | None,
    ) -> str:
        deadline = time.monotonic() + float(timeout or self.message_timeout_seconds)
        seen = set(seen_ids or ())
        polls = 0
        max_mail_count = 0
        last_error = "none"
        while time.monotonic() < deadline:
            polls += 1
            try:
                mails = self._fetch_mailbox_mails_sync(mailbox)
                max_mail_count = max(max_mail_count, len(mails))
                last_error = "none"
                for item in mails:
                    item_id = str(item.get("id") or item.get("message_id") or "")
                    if item_id and item_id in seen:
                        continue
                    if item_id:
                        seen.add(item_id)
                    match = regex.search(_mail_text(item))
                    if match:
                        return _match_value(match)
            except (httpx.HTTPError, ValueError) as error:
                last_error = type(error).__name__
            time.sleep(max(1, self.poll_seconds))
        raise TimeoutError(
            "temporary mailbox did not receive the expected message "
            f"(polls={polls}, max_mails={max_mail_count}, last_error={last_error})"
        )


class TempMailClient(BaseMailClient):
    """Client for cloudflare_temp_email without leaking mailbox credentials."""

    def __init__(
        self,
        base_url: str | None = None,
        admin_password: str | None = None,
        site_password: str | None = None,
        domain: str | None = None,
        domains: list[str] | tuple[str, ...] | str | None = None,
        request_timeout_seconds: float | None = None,
        poll_seconds: float | None = None,
        message_timeout_seconds: float | None = None,
    ) -> None:
        super().__init__(
            request_timeout_seconds=request_timeout_seconds,
            poll_seconds=poll_seconds,
            message_timeout_seconds=message_timeout_seconds,
        )
        config = settings()
        self.base_url = str(config.mail_api_base if base_url is None else base_url).rstrip("/")
        self.admin_password = str(
            config.mail_admin_password if admin_password is None else admin_password
        )
        self.site_password = str(
            config.mail_site_password if site_password is None else site_password
        )
        self.domain = str(
            config.mail_domain if domain is None and domains is None else domain or ""
        ).strip()
        if isinstance(domains, str):
            domain_values = domains.split(",")
        else:
            domain_values = list(domains or ())
        self.domains = _available_domains(domain_values)
        if not self.base_url:
            raise ValueError("temporary mail API is not configured")

    async def create_address(self, local_part: str | None = None) -> Mailbox:
        local = local_part or _random_local_part()
        domain = self.domain or (secrets.choice(self.domains) if self.domains else None)
        domain = domain or await self._default_domain()
        headers = self._headers(admin=True)
        body: dict[str, Any] = {"name": local, "domain": domain}
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            response = await client.post(
                f"{self.base_url}/admin/new_address", headers=headers, json=body
            )
            response.raise_for_status()
            data = response.json()
        address = str(data.get("address") or f"{local}@{domain}").strip().lower()
        jwt = str(data.get("jwt") or data.get("token") or "").strip()
        if not jwt:
            raise RuntimeError("temporary mail API did not return a mailbox JWT")
        return Mailbox(address=address, jwt=jwt, address_id=_optional_int(data.get("id")))

    async def refresh(self, address: str) -> Mailbox:
        normalized = _validated_address(address)
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            listing = await client.get(
                f"{self.base_url}/admin/address", headers=self._admin_headers()
            )
            listing.raise_for_status()
            items = listing.json().get("results") or listing.json().get("addresses") or []
            match = next(
                (item for item in items if str(item.get("address") or "").lower() == normalized),
                None,
            )
            if match is None:
                raise ValueError("mailbox does not exist")
            address_id = _optional_int(match.get("id"))
            if address_id is None:
                raise RuntimeError("mailbox record omitted id")
            response = await client.get(
                f"{self.base_url}/admin/show_password/{address_id}",
                headers=self._headers(admin=True),
            )
            response.raise_for_status()
            data = response.json()
        jwt = str(data.get("jwt") or data.get("token") or "").strip()
        if not jwt:
            raise RuntimeError("temporary mail API did not refresh the mailbox JWT")
        return Mailbox(normalized, jwt, address_id)

    async def _fetch_mailbox_mails(self, mailbox: Mailbox) -> list[dict[str, Any]]:
        return await self._list_mails(mailbox.jwt)

    def _fetch_mailbox_mails_sync(self, mailbox: Mailbox) -> list[dict[str, Any]]:
        return self._list_mails_sync(mailbox.jwt)

    async def _list_mails(self, jwt: str) -> list[dict[str, Any]]:
        headers = self._headers(jwt=jwt)
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            for path in ("/api/parsed_mails", "/api/mails"):
                response = await client.get(
                    f"{self.base_url}{path}", headers=headers, params={"limit": 30, "offset": 0}
                )
                if response.status_code == 404:
                    continue
                response.raise_for_status()
                data = response.json()
                return list(data.get("results") or data.get("mails") or [])
        return []

    def _list_mails_sync(self, jwt: str) -> list[dict[str, Any]]:
        headers = self._headers(jwt=jwt)
        with httpx.Client(timeout=self.timeout) as client:
            for path in ("/api/parsed_mails", "/api/mails"):
                response = client.get(
                    f"{self.base_url}{path}", headers=headers, params={"limit": 30, "offset": 0}
                )
                if response.status_code == 404:
                    continue
                response.raise_for_status()
                data = response.json()
                return list(data.get("results") or data.get("mails") or [])
        return []

    async def _default_domain(self) -> str:
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            response = await client.get(
                f"{self.base_url}/open_api/settings", headers=self._headers()
            )
            response.raise_for_status()
            data = response.json()
        domains = _available_domains(data.get("domains") or data.get("defaultDomains") or [])
        if not domains:
            raise RuntimeError("temporary mail API exposes no domains")
        return secrets.choice(domains)

    def _admin_headers(self) -> dict[str, str]:
        return self._headers(admin=True)

    def _headers(self, *, admin: bool = False, jwt: str = "") -> dict[str, str]:
        headers = {"Accept": "application/json", "Content-Type": "application/json"}
        if self.site_password:
            headers["x-custom-auth"] = self.site_password
        if jwt:
            headers["Authorization"] = f"Bearer {jwt}"
        if not admin:
            return headers
        if not self.admin_password:
            raise ValueError("temporary mail admin password is not configured")
        headers["x-admin-auth"] = self.admin_password
        return headers


class TempMailIoClient(BaseMailClient):
    """Client for public temp-mail.io service providing top-level .com domains."""

    def __init__(
        self,
        base_url: str | None = None,
        domain: str | None = None,
        domains: list[str] | tuple[str, ...] | str | None = None,
        request_timeout_seconds: float | None = None,
        poll_seconds: float | None = None,
        message_timeout_seconds: float | None = None,
    ) -> None:
        super().__init__(
            request_timeout_seconds=request_timeout_seconds,
            poll_seconds=poll_seconds,
            message_timeout_seconds=message_timeout_seconds,
        )
        config = settings()
        self.base_url = str(config.tempmail_io_base_url if base_url is None else base_url).rstrip(
            "/"
        )
        self.domain = str(
            config.mail_domain if domain is None and domains is None else domain or ""
        ).strip()
        if isinstance(domains, str):
            domain_values = domains.split(",")
        else:
            domain_values = list(domains or ())
        self.domains = _available_domains(domain_values)

    async def create_address(self, local_part: str | None = None) -> Mailbox:
        local = local_part or _random_local_part()
        domain = self.domain or (secrets.choice(self.domains) if self.domains else None)
        domain = domain or await self._default_domain()
        body: dict[str, Any] = {"name": local, "domain": domain}
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            response = await client.post(f"{self.base_url}/email/new", json=body)
            response.raise_for_status()
            data = response.json()
        address = str(data.get("email") or f"{local}@{domain}").strip().lower()
        token = str(data.get("token") or "").strip()
        return Mailbox(address=address, jwt=token)

    async def refresh(self, address: str) -> Mailbox:
        normalized = _validated_address(address)
        return Mailbox(address=normalized, jwt="")

    async def _default_domain(self) -> str:
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            response = await client.get(f"{self.base_url}/domains")
            response.raise_for_status()
            data = response.json()
        domain_list = data.get("domains") if isinstance(data, dict) else data
        domains = _available_domains(
            [
                d.get("name")
                for d in (domain_list or [])
                if isinstance(d, dict) and d.get("type") == "public"
            ]
        )
        if not domains:
            domains = ["yzcalo.com", "tanpony.com", "lnovic.com"]
        return secrets.choice(domains)

    async def _fetch_mailbox_mails(self, mailbox: Mailbox) -> list[dict[str, Any]]:
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            response = await client.get(f"{self.base_url}/email/{mailbox.address}/messages")
            if response.status_code == 404:
                return []
            response.raise_for_status()
            data = response.json()
            return list(data if isinstance(data, list) else data.get("messages") or [])

    def _fetch_mailbox_mails_sync(self, mailbox: Mailbox) -> list[dict[str, Any]]:
        with httpx.Client(timeout=self.timeout) as client:
            response = client.get(f"{self.base_url}/email/{mailbox.address}/messages")
            if response.status_code == 404:
                return []
            response.raise_for_status()
            data = response.json()
            return list(data if isinstance(data, list) else data.get("messages") or [])


class MailTmClient(BaseMailClient):
    """Client for public mail.tm / mail.gw standard REST service."""

    def __init__(
        self,
        base_url: str | None = None,
        domain: str | None = None,
        domains: list[str] | tuple[str, ...] | str | None = None,
        request_timeout_seconds: float | None = None,
        poll_seconds: float | None = None,
        message_timeout_seconds: float | None = None,
    ) -> None:
        super().__init__(
            request_timeout_seconds=request_timeout_seconds,
            poll_seconds=poll_seconds,
            message_timeout_seconds=message_timeout_seconds,
        )
        config = settings()
        self.base_url = str(config.mail_tm_base_url if base_url is None else base_url).rstrip("/")
        self.domain = str(
            config.mail_domain if domain is None and domains is None else domain or ""
        ).strip()
        if isinstance(domains, str):
            domain_values = domains.split(",")
        else:
            domain_values = list(domains or ())
        self.domains = _available_domains(domain_values)

    async def create_address(self, local_part: str | None = None) -> Mailbox:
        local = local_part or _random_local_part()
        domain = self.domain or (secrets.choice(self.domains) if self.domains else None)
        domain = domain or await self._default_domain()
        address = f"{local}@{domain}".lower()
        password = "P@" + secrets.token_hex(8)
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            resp_acc = await client.post(
                f"{self.base_url}/accounts", json={"address": address, "password": password}
            )
            resp_acc.raise_for_status()
            resp_tok = await client.post(
                f"{self.base_url}/token", json={"address": address, "password": password}
            )
            resp_tok.raise_for_status()
            token = str(resp_tok.json().get("token") or "")
        return Mailbox(address=address, jwt=token)

    async def refresh(self, address: str) -> Mailbox:
        normalized = _validated_address(address)
        return Mailbox(address=normalized, jwt="")

    async def _default_domain(self) -> str:
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            response = await client.get(f"{self.base_url}/domains")
            response.raise_for_status()
            data = response.json()
        members = data.get("hydra:member") or []
        domains = _available_domains(
            [
                d.get("domain")
                for d in members
                if isinstance(d, dict) and d.get("isActive") is not False
            ]
        )
        if not domains:
            domains = ["maxxspace.com"]
        return secrets.choice(domains)

    async def _fetch_mailbox_mails(self, mailbox: Mailbox) -> list[dict[str, Any]]:
        headers = {"Authorization": f"Bearer {mailbox.jwt}"}
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            response = await client.get(f"{self.base_url}/messages", headers=headers)
            if response.status_code == 404:
                return []
            response.raise_for_status()
            data = response.json()
            messages = data.get("hydra:member") or []
            detailed: list[dict[str, Any]] = []
            for item in messages:
                msg_id = item.get("id")
                if msg_id:
                    detail_resp = await client.get(
                        f"{self.base_url}/messages/{msg_id}", headers=headers
                    )
                    if detail_resp.status_code == 200:
                        detailed.append(detail_resp.json())
                        continue
                detailed.append(item)
            return detailed

    def _fetch_mailbox_mails_sync(self, mailbox: Mailbox) -> list[dict[str, Any]]:
        headers = {"Authorization": f"Bearer {mailbox.jwt}"}
        with httpx.Client(timeout=self.timeout) as client:
            response = client.get(f"{self.base_url}/messages", headers=headers)
            if response.status_code == 404:
                return []
            response.raise_for_status()
            data = response.json()
            messages = data.get("hydra:member") or []
            detailed: list[dict[str, Any]] = []
            for item in messages:
                msg_id = item.get("id")
                if msg_id:
                    detail_resp = client.get(f"{self.base_url}/messages/{msg_id}", headers=headers)
                    if detail_resp.status_code == 200:
                        detailed.append(detail_resp.json())
                        continue
                detailed.append(item)
            return detailed


class TempMailPlusClient(BaseMailClient):
    """Client for public tempmail.plus service providing @mailto.plus and @fexpost.com domains."""

    def __init__(
        self,
        base_url: str | None = None,
        domain: str | None = None,
        domains: list[str] | tuple[str, ...] | str | None = None,
        request_timeout_seconds: float | None = None,
        poll_seconds: float | None = None,
        message_timeout_seconds: float | None = None,
    ) -> None:
        super().__init__(
            request_timeout_seconds=request_timeout_seconds,
            poll_seconds=poll_seconds,
            message_timeout_seconds=message_timeout_seconds,
        )
        self.base_url = str(base_url or "https://tempmail.plus/api").rstrip("/")
        self.domain = str(domain or "").strip()
        if isinstance(domains, str):
            domain_values = domains.split(",")
        else:
            domain_values = list(domains or ())
        self.domains = _available_domains(domain_values) or ["mailto.plus", "fexpost.com"]

    async def create_address(self, local_part: str | None = None) -> Mailbox:
        local = local_part or _random_local_part()
        domain = self.domain or secrets.choice(self.domains)
        address = f"{local}@{domain}".lower()
        return Mailbox(address=address, jwt="")

    async def refresh(self, address: str) -> Mailbox:
        normalized = _validated_address(address)
        return Mailbox(address=normalized, jwt="")

    async def _fetch_mailbox_mails(self, mailbox: Mailbox) -> list[dict[str, Any]]:
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            response = await client.get(
                f"{self.base_url}/mails", params={"email": mailbox.address, "limit": 20}
            )
            if response.status_code == 404:
                return []
            response.raise_for_status()
            data = response.json()
            mail_list = data.get("mail_list") or []
            detailed: list[dict[str, Any]] = []
            for item in mail_list:
                mail_id = item.get("mail_id")
                if mail_id:
                    detail_resp = await client.get(
                        f"{self.base_url}/mails/{mail_id}", params={"email": mailbox.address}
                    )
                    if detail_resp.status_code == 200:
                        detailed.append(detail_resp.json())
                        continue
                detailed.append(item)
            return detailed

    def _fetch_mailbox_mails_sync(self, mailbox: Mailbox) -> list[dict[str, Any]]:
        with httpx.Client(timeout=self.timeout) as client:
            response = client.get(
                f"{self.base_url}/mails", params={"email": mailbox.address, "limit": 20}
            )
            if response.status_code == 404:
                return []
            response.raise_for_status()
            data = response.json()
            mail_list = data.get("mail_list") or []
            detailed: list[dict[str, Any]] = []
            for item in mail_list:
                mail_id = item.get("mail_id")
                if mail_id:
                    detail_resp = client.get(
                        f"{self.base_url}/mails/{mail_id}", params={"email": mailbox.address}
                    )
                    if detail_resp.status_code == 200:
                        detailed.append(detail_resp.json())
                        continue
                detailed.append(item)
            return detailed


class GuerrillaMailClient(BaseMailClient):
    """Client for public Guerrilla Mail service."""

    def __init__(
        self,
        base_url: str | None = None,
        domain: str | None = None,
        domains: list[str] | tuple[str, ...] | str | None = None,
        request_timeout_seconds: float | None = None,
        poll_seconds: float | None = None,
        message_timeout_seconds: float | None = None,
    ) -> None:
        super().__init__(
            request_timeout_seconds=request_timeout_seconds,
            poll_seconds=poll_seconds or 3.0,
            message_timeout_seconds=message_timeout_seconds,
        )
        self.base_url = str(base_url or "https://api.guerrillamail.com/ajax.php").rstrip("/")

    async def create_address(self, local_part: str | None = None) -> Mailbox:
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            resp = await client.get(self.base_url, params={"f": "get_email_address"})
            resp.raise_for_status()
            data = resp.json()
            sid = str(data.get("sid_token") or "")
            addr = str(data.get("email_addr") or "").strip().lower()
            if local_part:
                user_clean = re.sub(r"[^a-zA-Z0-9_.-]", "", local_part)
                if user_clean:
                    user_resp = await client.post(
                        self.base_url,
                        data={"f": "set_email_user", "email_user": user_clean, "sid_token": sid},
                    )
                    if user_resp.status_code == 200:
                        user_data = user_resp.json()
                        addr = str(user_data.get("email_addr") or addr).strip().lower()
            return Mailbox(address=addr, jwt=sid)

    async def refresh(self, address: str) -> Mailbox:
        normalized = _validated_address(address)
        return Mailbox(address=normalized, jwt="")

    async def _fetch_mailbox_mails(self, mailbox: Mailbox) -> list[dict[str, Any]]:
        sid = mailbox.jwt
        if not sid:
            return []
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            resp = await client.get(
                self.base_url, params={"f": "check_email", "seq": "0", "sid_token": sid}
            )
            if resp.status_code != 200:
                return []
            data = resp.json()
            raw_list = data.get("list") or []
            detailed: list[dict[str, Any]] = []
            for item in raw_list:
                msg_id = item.get("mail_id")
                if msg_id and not item.get("mail_body"):
                    detail_resp = await client.get(
                        self.base_url,
                        params={"f": "fetch_email", "email_id": msg_id, "sid_token": sid},
                    )
                    if detail_resp.status_code == 200:
                        item = detail_resp.json()
                item_dict = dict(item)
                item_dict["id"] = str(msg_id or item.get("mail_id") or "")
                item_dict["subject"] = item.get("mail_subject") or ""
                item_dict["text"] = item.get("mail_body") or item.get("mail_excerpt") or ""
                item_dict["html"] = item.get("mail_body") or ""
                detailed.append(item_dict)
            return detailed

    def _fetch_mailbox_mails_sync(self, mailbox: Mailbox) -> list[dict[str, Any]]:
        sid = mailbox.jwt
        if not sid:
            return []
        with httpx.Client(timeout=self.timeout) as client:
            resp = client.get(
                self.base_url, params={"f": "check_email", "seq": "0", "sid_token": sid}
            )
            if resp.status_code != 200:
                return []
            data = resp.json()
            raw_list = data.get("list") or []
            detailed: list[dict[str, Any]] = []
            for item in raw_list:
                msg_id = item.get("mail_id")
                if msg_id and not item.get("mail_body"):
                    detail_resp = client.get(
                        self.base_url,
                        params={"f": "fetch_email", "email_id": msg_id, "sid_token": sid},
                    )
                    if detail_resp.status_code == 200:
                        item = detail_resp.json()
                item_dict = dict(item)
                item_dict["id"] = str(msg_id or item.get("mail_id") or "")
                item_dict["subject"] = item.get("mail_subject") or ""
                item_dict["text"] = item.get("mail_body") or item.get("mail_excerpt") or ""
                item_dict["html"] = item.get("mail_body") or ""
                detailed.append(item_dict)
            return detailed


class MaildropClient(BaseMailClient):
    """Client for public maildrop.cc GraphQL service."""

    def __init__(
        self,
        base_url: str | None = None,
        domain: str | None = None,
        domains: list[str] | tuple[str, ...] | str | None = None,
        request_timeout_seconds: float | None = None,
        poll_seconds: float | None = None,
        message_timeout_seconds: float | None = None,
    ) -> None:
        super().__init__(
            request_timeout_seconds=request_timeout_seconds,
            poll_seconds=poll_seconds,
            message_timeout_seconds=message_timeout_seconds,
        )
        self.base_url = str(base_url or "https://api.maildrop.cc/graphql").rstrip("/")

    async def create_address(self, local_part: str | None = None) -> Mailbox:
        local = re.sub(r"[^a-z0-9]", "", str(local_part or _random_local_part()).lower())
        if not local:
            local = "a2a" + secrets.token_hex(4)
        address = f"{local}@maildrop.cc"
        return Mailbox(address=address, jwt="")

    async def refresh(self, address: str) -> Mailbox:
        normalized = _validated_address(address)
        return Mailbox(address=normalized, jwt="")

    async def _fetch_mailbox_mails(self, mailbox: Mailbox) -> list[dict[str, Any]]:
        user = mailbox.address.split("@")[0].lower()
        inbox_query = "query($m: String!) { inbox(mailbox: $m) { id headerfrom subject date } }"
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            resp = await client.post(
                self.base_url,
                json={"query": inbox_query, "variables": {"m": user}},
            )
            if resp.status_code != 200:
                return []
            data = resp.json().get("data") or {}
            inbox = data.get("inbox") or []
            detailed: list[dict[str, Any]] = []
            for item in inbox:
                msg_id = item.get("id")
                if msg_id:
                    msg_query = (
                        "query($m: String!, $id: String!) { "
                        "message(mailbox: $m, id: $id) { id headerfrom subject date data html } }"
                    )
                    detail_resp = await client.post(
                        self.base_url,
                        json={"query": msg_query, "variables": {"m": user, "id": msg_id}},
                    )
                    if detail_resp.status_code == 200:
                        msg_data = (detail_resp.json().get("data") or {}).get("message")
                        if msg_data:
                            detailed.append(
                                {
                                    "id": str(msg_data.get("id") or msg_id),
                                    "subject": msg_data.get("subject") or "",
                                    "text": msg_data.get("data") or "",
                                    "html": msg_data.get("html") or "",
                                    "from": msg_data.get("headerfrom") or "",
                                }
                            )
                            continue
                detailed.append(item)
            return detailed

    def _fetch_mailbox_mails_sync(self, mailbox: Mailbox) -> list[dict[str, Any]]:
        user = mailbox.address.split("@")[0].lower()
        inbox_query = "query($m: String!) { inbox(mailbox: $m) { id headerfrom subject date } }"
        with httpx.Client(timeout=self.timeout) as client:
            resp = client.post(
                self.base_url,
                json={"query": inbox_query, "variables": {"m": user}},
            )
            if resp.status_code != 200:
                return []
            data = resp.json().get("data") or {}
            inbox = data.get("inbox") or []
            detailed: list[dict[str, Any]] = []
            for item in inbox:
                msg_id = item.get("id")
                if msg_id:
                    msg_query = (
                        "query($m: String!, $id: String!) { "
                        "message(mailbox: $m, id: $id) { id headerfrom subject date data html } }"
                    )
                    detail_resp = client.post(
                        self.base_url,
                        json={"query": msg_query, "variables": {"m": user, "id": msg_id}},
                    )
                    if detail_resp.status_code == 200:
                        msg_data = (detail_resp.json().get("data") or {}).get("message")
                        if msg_data:
                            detailed.append(
                                {
                                    "id": str(msg_data.get("id") or msg_id),
                                    "subject": msg_data.get("subject") or "",
                                    "text": msg_data.get("data") or "",
                                    "html": msg_data.get("html") or "",
                                    "from": msg_data.get("headerfrom") or "",
                                }
                            )
                            continue
                detailed.append(item)
            return detailed


class TempMailLolClient(BaseMailClient):
    """Client for public tempmail.lol tokenized API."""

    def __init__(
        self,
        base_url: str | None = None,
        domain: str | None = None,
        domains: list[str] | tuple[str, ...] | str | None = None,
        request_timeout_seconds: float | None = None,
        poll_seconds: float | None = None,
        message_timeout_seconds: float | None = None,
    ) -> None:
        super().__init__(
            request_timeout_seconds=request_timeout_seconds,
            poll_seconds=poll_seconds or 3.5,
            message_timeout_seconds=message_timeout_seconds,
        )
        self.base_url = str(base_url or "https://api.tempmail.lol").rstrip("/")

    async def create_address(self, local_part: str | None = None) -> Mailbox:
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            resp = await client.get(f"{self.base_url}/generate")
            resp.raise_for_status()
            data = resp.json()
            address = str(data.get("address") or "").strip().lower()
            token = str(data.get("token") or "").strip()
            return Mailbox(address=address, jwt=token)

    async def refresh(self, address: str) -> Mailbox:
        normalized = _validated_address(address)
        return Mailbox(address=normalized, jwt="")

    async def _fetch_mailbox_mails(self, mailbox: Mailbox) -> list[dict[str, Any]]:
        token = mailbox.jwt
        if not token:
            return []
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            resp = await client.get(f"{self.base_url}/auth/{token}")
            if resp.status_code != 200:
                return []
            data = resp.json()
            emails = data.get("email") or data.get("emails") or []
            if isinstance(emails, dict):
                emails = [emails]
            items: list[dict[str, Any]] = []
            for idx, item in enumerate(emails):
                items.append(
                    {
                        "id": str(item.get("id") or idx),
                        "subject": item.get("subject") or "",
                        "text": item.get("body") or item.get("text") or "",
                        "html": item.get("html") or "",
                        "from": item.get("from") or "",
                    }
                )
            return items

    def _fetch_mailbox_mails_sync(self, mailbox: Mailbox) -> list[dict[str, Any]]:
        token = mailbox.jwt
        if not token:
            return []
        with httpx.Client(timeout=self.timeout) as client:
            resp = client.get(f"{self.base_url}/auth/{token}")
            if resp.status_code != 200:
                return []
            data = resp.json()
            emails = data.get("email") or data.get("emails") or []
            if isinstance(emails, dict):
                emails = [emails]
            items: list[dict[str, Any]] = []
            for idx, item in enumerate(emails):
                items.append(
                    {
                        "id": str(item.get("id") or idx),
                        "subject": item.get("subject") or "",
                        "text": item.get("body") or item.get("text") or "",
                        "html": item.get("html") or "",
                        "from": item.get("from") or "",
                    }
                )
            return items


def create_mail_client(
    driver: str | None = None,
    *,
    base_url: str | None = None,
    admin_password: str | None = None,
    site_password: str | None = None,
    domain: str | None = None,
    domains: list[str] | tuple[str, ...] | str | None = None,
    request_timeout_seconds: float | None = None,
    poll_seconds: float | None = None,
    message_timeout_seconds: float | None = None,
) -> BaseMailClient:
    normalized = str(driver or "").strip().lower()
    config = settings()
    if not normalized or normalized == "auto":
        if not config.mail_api_base and not base_url:
            normalized = "tempmail_io"
        else:
            normalized = "cloudflare"
    elif normalized in {"random", "shuffle"}:
        candidates = [
            "tempmail_io",
            "tempmail_plus",
            "guerrillamail",
            "maildrop",
            "tempmail_lol",
        ]
        if config.mail_api_base or base_url:
            candidates.append("cloudflare")
        normalized = secrets.choice(candidates)

    if normalized in {"tempmail_io", "temp-mail.io", "tempmail"}:
        target_base = (
            base_url
            if base_url and ("temp-mail.io" in base_url or "tempmail" in base_url)
            else None
        )
        return TempMailIoClient(
            base_url=target_base,
            domain=domain,
            domains=domains,
            request_timeout_seconds=request_timeout_seconds,
            poll_seconds=poll_seconds,
            message_timeout_seconds=message_timeout_seconds,
        )
    if normalized in {"tempmail_plus", "tempmail.plus", "mailto_plus", "mailto.plus"}:
        target_base = (
            base_url
            if base_url and ("tempmail.plus" in base_url or "mailto.plus" in base_url)
            else None
        )
        return TempMailPlusClient(
            base_url=target_base,
            domain=domain,
            domains=domains,
            request_timeout_seconds=request_timeout_seconds,
            poll_seconds=poll_seconds,
            message_timeout_seconds=message_timeout_seconds,
        )
    if normalized in {"guerrillamail", "guerrilla", "guerrilla_mail"}:
        target_base = (
            base_url if base_url and ("guerrillamail" in base_url) else None
        )
        return GuerrillaMailClient(
            base_url=target_base,
            domain=domain,
            domains=domains,
            request_timeout_seconds=request_timeout_seconds,
            poll_seconds=poll_seconds,
            message_timeout_seconds=message_timeout_seconds,
        )
    if normalized in {"maildrop", "maildrop_cc", "maildrop.cc"}:
        target_base = (
            base_url if base_url and ("maildrop" in base_url) else None
        )
        return MaildropClient(
            base_url=target_base,
            domain=domain,
            domains=domains,
            request_timeout_seconds=request_timeout_seconds,
            poll_seconds=poll_seconds,
            message_timeout_seconds=message_timeout_seconds,
        )
    if normalized in {"tempmail_lol", "tempmail.lol", "tempmaillol"}:
        target_base = (
            base_url if base_url and ("tempmail.lol" in base_url) else None
        )
        return TempMailLolClient(
            base_url=target_base,
            domain=domain,
            domains=domains,
            request_timeout_seconds=request_timeout_seconds,
            poll_seconds=poll_seconds,
            message_timeout_seconds=message_timeout_seconds,
        )
    if normalized in {"mail_tm", "mail.tm", "mailtm"}:
        target_base = (
            base_url if base_url and ("mail.tm" in base_url or "mail.gw" in base_url) else None
        )
        return MailTmClient(
            base_url=target_base,
            domain=domain,
            domains=domains,
            request_timeout_seconds=request_timeout_seconds,
            poll_seconds=poll_seconds,
            message_timeout_seconds=message_timeout_seconds,
        )
    return TempMailClient(
        base_url=base_url,
        admin_password=admin_password,
        site_password=site_password,
        domain=domain,
        domains=domains,
        request_timeout_seconds=request_timeout_seconds,
        poll_seconds=poll_seconds,
        message_timeout_seconds=message_timeout_seconds,
    )


def _mail_text(item: dict[str, Any]) -> str:
    parts = [
        str(item.get(key) or "")
        for key in (
            "subject",
            "text",
            "body_text",
            "html",
            "body_html",
            "intro",
            "raw",
            "source",
        )
    ]
    raw = str(item.get("raw") or "")
    if raw:
        try:
            message = email.message_from_string(raw, policy=policy.default)
            for part in message.walk():
                if part.get_content_type() in {"text/plain", "text/html"}:
                    parts.append(str(part.get_content()))
        except Exception:  # noqa: BLE001,S110 - malformed third-party mail is skipped
            pass
    return unescape("\n".join(parts))


def _match_value(match: re.Match[str]) -> str:
    if match.lastindex:
        return next((value for value in match.groups() if value is not None), match.group(0))
    return match.group(0)


def _random_local_part() -> str:
    alphabet = string.ascii_lowercase + string.digits
    return "a2a-" + "".join(secrets.choice(alphabet) for _ in range(random.randint(10, 14)))


def _available_domains(values: list[object]) -> list[str]:
    domains: list[str] = []
    for value in values:
        if isinstance(value, dict):
            if value.get("enabled") is False or value.get("active") is False:
                continue
            if value.get("disabled") is True:
                continue
            candidate = value.get("name") or value.get("domain")
        else:
            candidate = value
        domain = str(candidate or "").strip().lower()
        if domain and domain not in domains:
            domains.append(domain)
    return domains


def _validated_address(value: str) -> str:
    result = value.strip().lower()
    if not re.fullmatch(r"[^@\s]+@[^@\s]+\.[^@\s]+", result):
        raise ValueError("invalid mailbox address")
    return result


def _optional_int(value: Any) -> int | None:
    try:
        return int(value) if value is not None else None
    except (TypeError, ValueError):
        return None
