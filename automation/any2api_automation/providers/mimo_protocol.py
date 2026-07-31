from __future__ import annotations

import base64
import hashlib
import json
import random
import re
import secrets
import string
import time
from dataclasses import dataclass
from typing import Any
from urllib.parse import parse_qs, urljoin, urlparse

import httpx
from cryptography.hazmat.primitives import padding, serialization
from cryptography.hazmat.primitives.asymmetric import padding as asymmetric_padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

from ..captcha.registry import registry as captcha_registry
from ..config import settings as core_settings
from ..lifecycle.mail import Mailbox, TempMailClient
from .mimo_settings import settings

_CAPTCHA_ERRORS = {87001, 70014, 1200212}
_REGIONS = (
    "US",
    "SG",
    "JP",
    "HK",
    "TW",
    "GB",
    "DE",
    "FR",
    "IT",
    "ES",
    "NL",
    "AU",
    "CA",
    "KR",
    "IN",
    "ID",
    "TH",
    "MY",
    "PH",
    "VN",
    "BR",
    "MX",
)
_RSA_DER_PATTERN = re.compile(r"MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQ[A-Za-z0-9+/=]{150,260}")
_RSA_TERNARY_PATTERN = re.compile(
    rf'\?\s*["\']({_RSA_DER_PATTERN.pattern})["\']\s*:\s*["\']({_RSA_DER_PATTERN.pattern})["\']'
)
_CAPTCHA_VISION_PROMPT = (
    "This is a Xiaomi registration image captcha containing distorted letters, digits, or "
    "simple Chinese characters. Output only the captcha characters with no spaces, quotes, "
    "punctuation, or explanation. Guess the most likely 3 to 8 characters if unclear."
)


@dataclass(frozen=True)
class MimoRegistrationResult:
    email: str
    credential: dict[str, Any]


class XiaomiProtocolClient:
    """Provider-owned Xiaomi registration and service-token exchange protocol."""

    def __init__(self, proxy_url: str) -> None:
        config = settings()
        self.account = config.mimo_account_url.rstrip("/")
        self.studio = config.mimo_base_url.rstrip("/")
        self.sid = config.mimo_service_id
        self.client = httpx.Client(
            proxy=proxy_url or None,
            timeout=httpx.Timeout(core_settings().registration_timeout_seconds),
            follow_redirects=False,
            headers={
                "Accept": "application/json, text/plain, */*",
                "Accept-Language": "en-US,en;q=0.9",
                "User-Agent": core_settings().provider_user_agent,
            },
        )

    def close(self) -> None:
        self.client.close()

    def register(
        self,
        mail: TempMailClient,
        mailbox: Mailbox,
        password: str,
        payload: dict[str, Any],
    ) -> MimoRegistrationResult:
        config = settings()
        region = _region(str(payload.get("region") or config.mimo_registration_region))
        device_id = _device_id()
        self.client.cookies.set("sdkVersion", "accountsdk-18.8.15", domain="account.xiaomi.com")
        self.client.cookies.set("deviceId", device_id, domain="account.xiaomi.com")
        public_key = self._official_public_key()
        encrypted_email, encrypted_password, eui = _encrypt_credentials(
            mailbox.address,
            password,
            public_key,
            config.mimo_registration_aes_iv,
        )

        for attempt in range(1, max(1, config.mimo_registration_captcha_attempts) + 1):
            image = self._captcha_image()
            candidates = _captcha_candidates(captcha_registry.solve_text_sync(image))
            if attempt >= max(1, config.mimo_registration_local_captcha_attempts):
                visual = captcha_registry.solve_visual_text_sync(image, _CAPTCHA_VISION_PROMPT)
                if visual is not None:
                    candidates.extend(
                        candidate
                        for candidate in _captcha_candidates([visual])
                        if candidate not in candidates
                    )
            if not candidates:
                continue
            ticket_ok = False
            for candidate in candidates:
                ticket = self._json(
                    "POST",
                    f"{self.account}/pass/sendEmailRegTicket",
                    headers=self._registration_headers(eui),
                    data={
                        "email": encrypted_email,
                        "password": encrypted_password,
                        "region": region,
                        "sid": self.sid,
                        "icode": candidate,
                        "_json": "true",
                    },
                )
                code = _number(ticket.get("code"))
                if code == 0 or ticket.get("code") in {None, "0"}:
                    ticket_ok = True
                    break
                if code not in _CAPTCHA_ERRORS:
                    raise RuntimeError(
                        "Xiaomi registration ticket request was rejected "
                        f"with code {code}: {_upstream_message(ticket)}"
                    )
            if ticket_ok:
                break
        else:
            raise RuntimeError("Xiaomi image captcha attempts were exhausted")

        code = mail.wait_for_code_sync(mailbox)
        verified = self._json(
            "POST",
            f"{self.account}/pass/verifyEmailRegTicket",
            headers=self._registration_headers(eui),
            data={
                "ticket": code,
                "region": region,
                "email": encrypted_email,
                "env": "web",
                "qs": f"%3Fsid%3D{self.sid}%26_json%3Dtrue",
                "isAcceptLicense": "true",
                "sid": self.sid,
                "password": encrypted_password,
                "policyName": "globalmiaccount",
                "callback": f"{self.studio}/sts",
                "deviceFingerprint": hashlib.md5(
                    f"{device_id}-{time.time_ns()}".encode(), usedforsecurity=False
                ).hexdigest(),
                "_json": "true",
            },
        )
        verify_code = _number(verified.get("code"))
        if verify_code != 0 and verified.get("code") not in {None, "0"}:
            raise RuntimeError("Xiaomi registration email verification was rejected")

        initial = {
            "pass_token": str(verified.get("passToken") or self._cookie("passToken")),
            "user_id": str(verified.get("userId") or self._cookie("userId")),
            "c_user_id": str(verified.get("cUserId") or self._cookie("cUserId")),
            "device_id": device_id,
        }
        if not initial["pass_token"]:
            raise RuntimeError("Xiaomi registration completed without passToken")
        exchanged = self.exchange_pass_token(initial)
        self._validate_service(exchanged)
        return MimoRegistrationResult(
            email=mailbox.address,
            credential={
                "email": mailbox.address,
                "password": password,
                "mail_jwt": mailbox.jwt,
                "region": region,
                **initial,
                **exchanged,
                "registration_backend": "xiaomi-http",
            },
        )

    def exchange_pass_token(self, current: dict[str, Any]) -> dict[str, Any]:
        device_id = str(current.get("device_id") or _device_id())
        self.client.cookies.clear()
        cookie_values = {
            "sdkVersion": "accountsdk-18.8.15",
            "deviceId": device_id,
            "passToken": str(current.get("pass_token") or ""),
            "userId": str(current.get("user_id") or ""),
            "cUserId": str(current.get("c_user_id") or ""),
        }
        if not cookie_values["passToken"]:
            raise ValueError("credential requires pass_token")
        for name, value in cookie_values.items():
            if value:
                self.client.cookies.set(name, value, domain="account.xiaomi.com")

        start = self._login_url()
        for _ in range(15):
            if not start:
                break
            response = self.client.get(
                start,
                headers={"Referer": f"{self.studio}/", "Cookie": self._cookie_header()},
            )
            location = response.headers.get("location")
            if location and response.status_code in {301, 302, 303, 307, 308}:
                start = _normalize_location(location, str(response.url), self.studio)
                if (
                    self._service_token()
                    and self._cookie("xiaomichatbot_ph")
                    and "open-apis" in start
                ):
                    break
                continue
            payload = _parse_json(response.text)
            raw_location = payload.get("location")
            start = (
                _normalize_location(str(raw_location), str(response.url), self.studio)
                if raw_location
                else ""
            )

        service_token = self._service_token()
        phase = self._cookie("xiaomichatbot_ph")
        if not service_token or not phase:
            raise RuntimeError("Xiaomi passToken exchange did not return MiMo service cookies")
        return {
            "service_token": service_token,
            "xiaomichatbot_ph": phase,
            "user_id": self._cookie("userId") or current.get("user_id"),
            "pass_token": self._cookie("passToken") or current.get("pass_token"),
            "c_user_id": self._cookie("cUserId") or current.get("c_user_id"),
            "device_id": device_id,
        }

    def reauthenticate_password(
        self,
        current: dict[str, Any],
        mail: TempMailClient,
        mailbox: Mailbox,
    ) -> dict[str, Any]:
        email = str(current.get("email") or "").strip()
        password = str(current.get("password") or "")
        if not email or not password:
            raise ValueError("MiMo password reauthentication requires email and password")
        device_id = str(current.get("device_id") or _device_id())
        self.client.cookies.set("sdkVersion", "accountsdk-18.8.15", domain="account.xiaomi.com")
        self.client.cookies.set("deviceId", device_id, domain="account.xiaomi.com")
        login = self._json(
            "GET",
            f"{self.account}/pass/serviceLogin",
            params={"sid": self.sid, "_json": "true"},
            headers={
                "Referer": f"{self.account}/fe/service/login/password",
                "Cookie": self._cookie_header(),
            },
        )
        auth = self._json(
            "POST",
            f"{self.account}/pass/serviceLoginAuth2",
            headers={
                "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
                "Origin": self.account,
                "Referer": f"{self.account}/fe/service/login/password",
                "X-Requested-With": "XMLHttpRequest",
                "Cookie": self._cookie_header(),
            },
            data={
                "bizDeviceType": "",
                "needTheme": "false",
                "theme": "",
                "showActiveX": "false",
                "serviceParam": str(login.get("serviceParam") or ""),
                "callback": str(login.get("callback") or f"{self.studio}/sts"),
                "qs": str(login.get("qs") or "%3Fsid%3Dxiaomichatbot%26_json%3Dtrue"),
                "sid": self.sid,
                "_sign": str(login.get("_sign") or ""),
                "user": email,
                "cc": "+86",
                "hash": hashlib.md5(password.encode(), usedforsecurity=False).hexdigest().upper(),
                "_json": "true",
                "policyName": "miaccount",
                "captCode": "",
            },
        )
        pass_token = str(auth.get("passToken") or self._cookie("passToken"))
        user_id = str(auth.get("userId") or self._cookie("userId") or current.get("user_id") or "")
        c_user_id = str(
            auth.get("cUserId") or self._cookie("cUserId") or current.get("c_user_id") or ""
        )
        security_status = _number(auth.get("securityStatus"))
        location = str(auth.get("location") or "")
        if location and security_status == 0:
            followed = self._follow_for_pass_token(location)
            pass_token = pass_token or followed["pass_token"]
            user_id = followed["user_id"] or user_id
            c_user_id = followed["c_user_id"] or c_user_id
        elif security_status == 16:
            notification_url = str(auth.get("notificationUrl") or "")
            context = parse_qs(urlparse(notification_url).query).get("context", [""])[0]
            if not context:
                raise RuntimeError("Xiaomi identity verification omitted context")
            reference = (
                f"{self.account}/fe/service/identity/verifyEmail?sid={self.sid}"
                f"&context={context}&_locale=zh_CN"
            )
            identity = self._json(
                "GET",
                f"{self.account}/identity/list",
                params={
                    "sid": self.sid,
                    "supportedMask": "0",
                    "_locale": "zh_CN",
                    "context": context,
                },
                headers={
                    "Referer": notification_url,
                    "X-Requested-With": "XMLHttpRequest",
                    "Cookie": self._cookie_header(),
                },
            )
            flag = str(identity.get("flag") or 8)
            self._json(
                "GET",
                f"{self.account}/identity/auth/verifyEmail",
                params={"_flag": flag, "_json": "true"},
                headers={
                    "Referer": reference,
                    "X-Requested-With": "XMLHttpRequest",
                    "Cookie": self._cookie_header(),
                },
            )
            seen_ids = mail.message_ids_sync(mailbox)
            sent = self._json(
                "POST",
                f"{self.account}/identity/auth/sendEmailTicket",
                headers={
                    "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
                    "Origin": self.account,
                    "Referer": reference,
                    "X-Requested-With": "XMLHttpRequest",
                    "Cookie": self._cookie_header(),
                },
                data={"_flag": flag, "_json": "true"},
            )
            if sent.get("code") not in {None, 0, "0"}:
                raise RuntimeError("Xiaomi email ticket request failed: " + _upstream_message(sent))
            code = mail.wait_for_code_sync(mailbox, seen_ids=seen_ids)
            verified = self._json(
                "POST",
                f"{self.account}/identity/auth/verifyEmail",
                headers={
                    "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
                    "Origin": self.account,
                    "Referer": reference,
                    "X-Requested-With": "XMLHttpRequest",
                    "Cookie": self._cookie_header(),
                },
                data={"ticket": code, "_json": "true"},
            )
            if _number(verified.get("code")) != 0 or not verified.get("location"):
                raise RuntimeError(
                    "Xiaomi email verification failed: " + _upstream_message(verified)
                )
            followed = self._follow_for_pass_token(str(verified["location"]))
            pass_token = followed["pass_token"]
            user_id = followed["user_id"] or user_id
            c_user_id = followed["c_user_id"] or c_user_id
        elif not pass_token:
            raise RuntimeError("Xiaomi password login failed: " + _upstream_message(auth))
        if not pass_token:
            raise RuntimeError("Xiaomi password login did not return passToken")
        exchanged = self.exchange_pass_token(
            {
                **current,
                "pass_token": pass_token,
                "user_id": user_id,
                "c_user_id": c_user_id,
                "device_id": device_id,
            }
        )
        return {
            **exchanged,
            "pass_token": pass_token,
            "user_id": str(exchanged.get("user_id") or user_id),
            "c_user_id": str(exchanged.get("c_user_id") or c_user_id),
            "device_id": device_id,
        }

    def _follow_for_pass_token(self, start: str) -> dict[str, str]:
        location = _normalize_location(start, self.account, self.studio)
        for _ in range(20):
            if not location:
                break
            response = self.client.get(
                location,
                headers={
                    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Referer": f"{self.account}/",
                    "Cookie": self._cookie_header(),
                },
            )
            redirect = response.headers.get("location")
            if redirect and response.status_code in {301, 302, 303, 307, 308}:
                next_location = _normalize_location(redirect, str(response.url), self.studio)
                if f"{urlparse(self.studio).netloc}/sts" in next_location and self._cookie(
                    "passToken"
                ):
                    break
                location = next_location
                continue
            payload = _parse_json(response.text)
            raw_location = payload.get("location")
            location = (
                _normalize_location(str(raw_location), str(response.url), self.studio)
                if raw_location
                else ""
            )
        pass_token = self._cookie("passToken")
        if not pass_token:
            raise RuntimeError("Xiaomi login completed without passToken")
        return {
            "pass_token": pass_token,
            "user_id": self._cookie("userId"),
            "c_user_id": self._cookie("cUserId"),
        }

    def _login_url(self) -> str:
        response = self.client.get(
            f"{self.studio}/open-apis/user/info",
            headers={"Referer": f"{self.studio}/", "Cookie": self._cookie_header()},
        )
        payload = _parse_json(response.text)
        if payload.get("loginUrl"):
            return str(payload["loginUrl"])
        response = self.client.get(
            f"{self.account}/pass/serviceLogin",
            params={"sid": self.sid, "_json": "true"},
            headers={"Referer": f"{self.account}/", "Cookie": self._cookie_header()},
        )
        payload = _parse_json(response.text)
        if not payload.get("location"):
            raise RuntimeError("could not obtain Xiaomi login URL")
        return str(payload["location"])

    def _validate_service(self, credential: dict[str, Any]) -> None:
        response = self.client.get(
            f"{self.studio}/open-apis/bot/config",
            headers={
                "Referer": f"{self.studio}/",
                "x-timezone": settings().mimo_timezone,
                "Cookie": (
                    f"serviceToken={credential['service_token']}; "
                    f"userId={credential['user_id']}; "
                    f"xiaomichatbot_ph={credential['xiaomichatbot_ph']}"
                ),
            },
        )
        if response.status_code in {401, 403}:
            raise RuntimeError("MiMo service credential validation failed")
        response.raise_for_status()

    def _captcha_image(self) -> bytes:
        response = self.client.get(
            f"{self.account}/pass/getCode",
            params={"icodeType": "register", "_": str(int(time.time() * 1000))},
            headers={
                "Accept": "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
                "Referer": f"{self.account}/fe/service/register",
            },
        )
        response.raise_for_status()
        if not response.content:
            raise RuntimeError("Xiaomi captcha response was empty")
        return response.content

    def _official_public_key(self) -> str:
        configured = settings().mimo_registration_public_key_der.strip()
        response = self.client.get(f"{self.account}/fe/service/register", params={"sid": self.sid})
        response.raise_for_status()
        sources: list[tuple[str, str]] = [(str(response.url), response.text)]
        scripts = re.findall(r'<script[^>]+src=["\']([^"\']+)["\']', response.text, re.IGNORECASE)
        for source in scripts[:24]:
            try:
                asset = self.client.get(urljoin(str(response.url), source))
                if asset.is_success:
                    sources.append((str(asset.url), asset.text))
            except httpx.HTTPError:
                continue
        for asset_url in _webpack_crypto_assets(sources):
            try:
                asset = self.client.get(asset_url)
                if asset.is_success:
                    sources.append((str(asset.url), asset.text))
            except httpx.HTTPError:
                continue
        discovered = _select_registration_public_key(
            sources,
            account_host=urlparse(self.account).hostname or "",
        )
        if discovered:
            return discovered
        if configured:
            return configured
        raise RuntimeError("Xiaomi registration public key could not be discovered")

    def _registration_headers(self, eui: str) -> dict[str, str]:
        return {
            "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
            "Origin": self.account,
            "Referer": f"{self.account}/fe/service/register",
            "X-Requested-With": "XMLHttpRequest",
            "EUI": eui,
        }

    def _json(self, method: str, url: str, **kwargs: Any) -> dict[str, Any]:
        response = self.client.request(method, url, **kwargs)
        if response.status_code >= 500:
            raise RuntimeError(f"Xiaomi protocol returned HTTP {response.status_code}")
        return _parse_json(response.text)

    def _cookie(self, name: str) -> str:
        value = ""
        for cookie in self.client.cookies.jar:
            if cookie.name == name:
                value = _normalized_cookie_value(cookie.value)
        return value

    def _service_token(self) -> str:
        return self._cookie("serviceToken") or self._cookie(f"{self.sid}_serviceToken")

    def _cookie_header(self) -> str:
        values: dict[str, str] = {}
        for cookie in self.client.cookies.jar:
            value = _normalized_cookie_value(cookie.value)
            if value and value != "EXPIRED":
                values[cookie.name] = value
        return "; ".join(f"{name}={value}" for name, value in values.items())


def _normalized_cookie_value(value: str) -> str:
    if len(value) >= 2 and value.startswith('"') and value.endswith('"'):
        return value[1:-1]
    return value


def _encrypt_credentials(
    email: str,
    password: str,
    public_key_der: str,
    iv_text: str,
) -> tuple[str, str, str]:
    key_chars = string.ascii_letters + string.digits + "!@#$%^&*"
    key = "".join(secrets.choice(key_chars) for _ in range(16)).encode()
    iv = iv_text.encode()
    if len(iv) != 16:
        raise ValueError("MiMo registration AES IV must be 16 bytes")

    def encrypt(value: str) -> str:
        padder = padding.PKCS7(128).padder()
        padded = padder.update(value.encode()) + padder.finalize()
        cipher = Cipher(algorithms.AES(key), modes.CBC(iv)).encryptor()
        return base64.b64encode(cipher.update(padded) + cipher.finalize()).decode()

    public_key = serialization.load_der_public_key(base64.b64decode(public_key_der))
    wrapped = public_key.encrypt(  # type: ignore[union-attr]
        base64.b64encode(key),
        asymmetric_padding.PKCS1v15(),
    )
    eui = f"{base64.b64encode(wrapped).decode()}.{base64.b64encode(b'email,password').decode()}"
    return encrypt(email), encrypt(password), eui


def _webpack_crypto_assets(sources: list[tuple[str, str]]) -> list[str]:
    assets: list[str] = []
    for source_url, source in sources:
        values: dict[str, list[str]] = {}
        for chunk_id, value in re.findall(r'(\d+):"([A-Za-z0-9_-]+)"', source):
            values.setdefault(chunk_id, []).append(value)
        base = source_url.rsplit("/", 1)[0] + "/"
        for candidates in values.values():
            names = [
                value
                for value in candidates
                if re.search(r"crypto|register|account", value, re.IGNORECASE)
            ]
            hashes = [value for value in candidates if re.fullmatch(r"[a-f0-9]{8,}", value)]
            for name in names:
                for digest in hashes:
                    if digest != name:
                        assets.append(urljoin(base, f"{name}.{digest}.chunk.js"))
    return list(dict.fromkeys(assets))


def _select_registration_public_key(sources: list[tuple[str, str]], *, account_host: str) -> str:
    preview_host = "preview" in account_host.lower()
    for _, source in sources:
        for match in _RSA_TERNARY_PATTERN.finditer(source):
            context = source[max(0, match.start() - 500) : match.end() + 500]
            if "0102030405060708" not in context or "setPublicKey" not in context:
                continue
            return match.group(1 if preview_host else 2)

    unique = list(
        dict.fromkeys(
            match.group(0) for _, source in sources for match in _RSA_DER_PATTERN.finditer(source)
        )
    )
    return unique[0] if len(unique) == 1 else ""


def _captcha_candidates(estimates: list[Any]) -> list[str]:
    values: list[str] = []
    for estimate in sorted(estimates, key=lambda item: item.confidence, reverse=True):
        value = re.sub(r"[^0-9A-Za-z\u4e00-\u9fff]", "", str(estimate.value))[:12]
        if len(value) < 3:
            continue
        variants = (value, value.lower(), value.upper()) if value.isascii() else (value,)
        for candidate in variants:
            if candidate not in values:
                values.append(candidate)
    return values


def _parse_json(value: str) -> dict[str, Any]:
    text = value.removeprefix("&&&START&&&")
    try:
        parsed = json.loads(text)
        return parsed if isinstance(parsed, dict) else {}
    except json.JSONDecodeError:
        return {}


def _upstream_message(payload: dict[str, Any]) -> str:
    value = payload.get("desc") or payload.get("description") or payload.get("reason") or "unknown"
    return re.sub(r"\s+", " ", str(value)).strip()[:240]


def _normalize_location(location: str, base: str, studio: str) -> str:
    normalized = re.sub(r"^http://aistudio\.xiaomimimo\.com", studio, location)
    return urljoin(base, normalized)


def _region(value: str) -> str:
    normalized = value.strip().upper()
    if normalized in {"CN", "ZH", "CHINA", "PRC"}:
        raise ValueError("MiMo registration does not support mainland China region")
    if normalized in {"", "RANDOM", "AUTO", "*"}:
        return random.SystemRandom().choice(_REGIONS)
    return normalized


def _number(value: Any) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return -1


def _device_id() -> str:
    raw = f"{time.time_ns()}-{secrets.token_hex(16)}".encode()
    return "wb" + hashlib.md5(raw, usedforsecurity=False).hexdigest()[:12]
