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


class TempMailClient:
    """Client for cloudflare_temp_email without leaking mailbox credentials."""

    def __init__(
        self,
        base_url: str | None = None,
        admin_password: str | None = None,
        site_password: str | None = None,
        domain: str | None = None,
        timeout: float = 30,
    ) -> None:
        config = settings()
        self.base_url = str(base_url or config.mail_api_base).rstrip("/")
        self.admin_password = str(admin_password or config.mail_admin_password)
        self.site_password = str(site_password or config.mail_site_password)
        self.domain = str(domain or config.mail_domain).strip()
        self.timeout = timeout
        if not self.base_url:
            raise ValueError("temporary mail API is not configured")

    async def create_address(self, local_part: str | None = None) -> Mailbox:
        local = local_part or _random_local_part()
        domain = self.domain or await self._default_domain()
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
    ) -> str:
        regex = re.compile(rf"https?://[^\s<>'\"]*{host_pattern}[^\s<>'\"]*", re.IGNORECASE)
        return unescape(await self._wait(mailbox, regex, timeout, None, None)).rstrip(".,);]")

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
    ) -> str:
        regex = re.compile(rf"https?://[^\s<>'\"]*{host_pattern}[^\s<>'\"]*", re.IGNORECASE)
        return unescape(self._wait_sync(mailbox, regex, timeout, None)).rstrip(".,);]")

    def message_ids_sync(self, mailbox: Mailbox) -> set[str]:
        return {
            str(item.get("id") or item.get("message_id"))
            for item in self._list_mails_sync(mailbox.jwt)
            if item.get("id") or item.get("message_id")
        }

    async def _wait(
        self,
        mailbox: Mailbox,
        regex: re.Pattern[str],
        timeout: float | None,
        predicate: Callable[[dict[str, Any]], bool] | None,
        seen_ids: set[str] | None,
    ) -> str:
        config = settings()
        deadline = time.monotonic() + float(timeout or config.mail_timeout_seconds)
        seen = set(seen_ids or ())
        polls = 0
        max_mail_count = 0
        last_error = "none"
        while time.monotonic() < deadline:
            polls += 1
            try:
                mails = await self._list_mails(mailbox.jwt)
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
            await asyncio.sleep(max(1, config.mail_poll_seconds))
        raise TimeoutError(
            "temporary mailbox did not receive the expected message "
            f"(polls={polls}, max_mails={max_mail_count}, last_error={last_error})"
        )

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

    def _wait_sync(
        self,
        mailbox: Mailbox,
        regex: re.Pattern[str],
        timeout: float | None,
        seen_ids: set[str] | None,
    ) -> str:
        config = settings()
        deadline = time.monotonic() + float(timeout or config.mail_timeout_seconds)
        seen = set(seen_ids or ())
        polls = 0
        max_mail_count = 0
        last_error = "none"
        while time.monotonic() < deadline:
            polls += 1
            try:
                mails = self._list_mails_sync(mailbox.jwt)
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
            time.sleep(max(1, config.mail_poll_seconds))
        raise TimeoutError(
            "temporary mailbox did not receive the expected message "
            f"(polls={polls}, max_mails={max_mail_count}, last_error={last_error})"
        )

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


def _mail_text(item: dict[str, Any]) -> str:
    parts = [str(item.get(key) or "") for key in ("subject", "text", "html", "raw", "source")]
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
