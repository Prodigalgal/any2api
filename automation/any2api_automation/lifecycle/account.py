from __future__ import annotations

import secrets
import string
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from .mail import Mailbox, TempMailClient


@dataclass(frozen=True)
class RegistrationPasswordPolicy:
    generator: Callable[[], str]
    min_length: int = 12
    max_length: int | None = None

    def resolve(self, supplied: Any) -> str:
        password = str(supplied or self.generator()).strip()
        if len(password) < self.min_length:
            raise ValueError(
                f"registration password must contain at least {self.min_length} characters"
            )
        if self.max_length is not None and len(password) > self.max_length:
            raise ValueError(
                f"registration password must contain at most {self.max_length} characters"
            )
        return password


async def prepare_registration(
    payload: dict[str, Any],
    *,
    password_policy: RegistrationPasswordPolicy | None = None,
) -> tuple[TempMailClient, Mailbox, str]:
    mail = mail_client(payload)
    mail_payload = payload.get("mail") or {}
    mailbox = await mail.create_address(str(mail_payload.get("local_part") or "") or None)
    policy = password_policy or RegistrationPasswordPolicy(strong_password)
    password = policy.resolve(payload.get("password"))
    return mail, mailbox, password


def mail_client(payload: dict[str, Any]) -> TempMailClient:
    mail_payload = payload.get("mail") or {}
    if not isinstance(mail_payload, dict):
        raise TypeError("mail must be an object")
    return TempMailClient(
        base_url=mail_payload.get("base_url"),
        admin_password=mail_payload.get("admin_password"),
        site_password=mail_payload.get("site_password"),
        domain=mail_payload.get("domain"),
        domains=mail_payload.get("domains"),
        request_timeout_seconds=mail_payload.get("request_timeout_seconds"),
        poll_seconds=mail_payload.get("poll_seconds"),
        message_timeout_seconds=mail_payload.get("message_timeout_seconds"),
    )


def flow_max_attempts(payload: dict[str, Any], fallback: int, maximum: int = 10) -> int:
    try:
        value = int(payload.get("flow_max_attempts", fallback))
    except (TypeError, ValueError):
        value = fallback
    return max(1, min(maximum, value))


def strong_password(length: int = 20) -> str:
    alphabet = string.ascii_letters + string.digits + "!@#$%"
    while True:
        value = "".join(secrets.choice(alphabet) for _ in range(length))
        if (
            any(character.islower() for character in value)
            and any(character.isupper() for character in value)
            and any(character.isdigit() for character in value)
            and any(character in "!@#$%" for character in value)
        ):
            return value


def credential(payload: dict[str, Any]) -> dict[str, Any]:
    value = payload.get("credential")
    if not isinstance(value, dict):
        raise TypeError("credential must be an object")
    return value


def required(value: dict[str, Any], *names: str) -> str:
    for name in names:
        result = str(value.get(name) or "").strip()
        if result:
            return result
    raise ValueError(f"credential requires one of: {', '.join(names)}")
