#!/usr/bin/env python3
"""Stage provider-scoped accounts from legacy SQLite stores into Any2API.

The script never writes credentials to its report. Imports are idempotent and are
staged as PENDING/disabled without lifecycle scheduling. Probes are an explicit,
bounded second phase.
"""

from __future__ import annotations

import argparse
import hashlib
import http.cookiejar
import json
import os
import re
import sqlite3
import sys
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Iterable


@dataclass(frozen=True)
class LegacyAccount:
    provider_id: str
    external_id: str
    email: str | None
    expires_at: str | None
    credential_expires_at: str | None
    metadata: dict[str, Any]
    credential: dict[str, Any]

    def key(self) -> tuple[str, str]:
        return self.provider_id, self.external_id


def connect(path: Path) -> sqlite3.Connection:
    if not path.is_file():
        raise FileNotFoundError(path)
    connection = sqlite3.connect(f"file:{path.resolve().as_posix()}?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    return connection


def json_object(value: Any) -> dict[str, Any]:
    if isinstance(value, dict):
        return dict(value)
    if not value:
        return {}
    try:
        parsed = json.loads(str(value))
    except json.JSONDecodeError:
        return {}
    return dict(parsed) if isinstance(parsed, dict) else {}


def iso_epoch(value: Any) -> str | None:
    if value is None or value == "":
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    if number > 10_000_000_000:
        number /= 1000
    try:
        return datetime.fromtimestamp(number, UTC).isoformat().replace("+00:00", "Z")
    except (OverflowError, OSError, ValueError):
        return None


def load_grok(path: Path) -> list[LegacyAccount]:
    with connect(path) as db:
        rows = db.execute(
            """
            SELECT a.*, p.enabled AS pool_enabled, p.weight AS pool_weight,
                   p.disabled_for_quota, p.disabled_reason, p.pool_status,
                   p.cooldown_until, p.last_error
            FROM accounts a LEFT JOIN account_pool p ON p.account_id = a.id
            ORDER BY a.id
            """
        ).fetchall()
    output: list[LegacyAccount] = []
    for row in rows:
        credential = json_object(row["payload_json"])
        sso = first_string(credential, "sso", "sso-rw", "sso_rw", "sso_cookie", "sso_token")
        if sso:
            credential.setdefault("sso", sso)
            credential.setdefault("sso-rw", sso)
        output.append(
            LegacyAccount(
                "grok",
                str(row["id"]),
                nullable(row["email"]),
                iso_epoch(row["expires_at"]),
                iso_epoch(row["expires_at"]),
                {
                    "migration_source": "grok2api-sqlite",
                    "legacy_user_id": nullable(row["user_id"]),
                    "legacy_team_id": nullable(row["team_id"]),
                    "legacy_pool_enabled": bool(row["pool_enabled"] or 0),
                    "legacy_pool_weight": int(row["pool_weight"] or 1),
                    "legacy_disabled_for_quota": bool(row["disabled_for_quota"] or 0),
                    "legacy_disabled_reason": nullable(row["disabled_reason"]),
                    "legacy_pool_status": nullable(row["pool_status"]),
                    "legacy_cooldown_until": iso_epoch(row["cooldown_until"]),
                    "legacy_last_error": nullable(row["last_error"]),
                },
                credential,
            )
        )
    return output


def load_longcat(path: Path) -> list[LegacyAccount]:
    with connect(path) as db:
        rows = db.execute("SELECT * FROM accounts ORDER BY id").fetchall()
    return [
        LegacyAccount(
            "longcat",
            str(row["id"]),
            nullable(row["email"]),
            None,
            None,
            {
                "migration_source": "longcat2api-sqlite",
                "legacy_enabled": bool(row["enabled"]),
                "legacy_is_valid": bool(row["is_valid"]),
                "legacy_auto_renew": bool(row["auto_renew"]),
                "legacy_error_count": int(row["error_count"] or 0),
                "legacy_region": nullable(row["region"]),
                "legacy_renew_error": nullable(row["renew_error"]),
            },
            compact(
                {
                    "cookie": row["cookie"],
                    "passport_token_key": row["passport_token"],
                    "lxsdk_cuid": row["lxsdk_cuid"],
                    "lxsdk_s": row["lxsdk_s"],
                    "email": row["email"],
                    "password": row["password"],
                    "mail_jwt": row["mail_jwt"],
                    "region": row["region"],
                }
            ),
        )
        for row in rows
    ]


def load_mimo(path: Path) -> list[LegacyAccount]:
    with connect(path) as db:
        rows = db.execute("SELECT * FROM accounts ORDER BY position, id").fetchall()
    output: list[LegacyAccount] = []
    for row in rows:
        credential = compact(
            {
                **json_object(row["extra_json"]),
                "service_token": row["service_token"],
                "user_id": row["user_id"],
                "xiaomichatbot_ph": row["xiaomichatbot_ph"],
                "email": row["email"],
                "password": row["password"],
                "pass_token": row["pass_token"],
                "c_user_id": row["c_user_id"],
                "device_id": row["device_id"],
                "mail_jwt": row["mail_jwt"],
                "region": row["region"],
            }
        )
        external_id = first_string(credential, "user_id", "c_user_id") or str(row["renew_key"])
        output.append(
            LegacyAccount(
                "mimo",
                external_id,
                nullable(row["email"]),
                None,
                None,
                {
                    "migration_source": "mimo2api-sqlite",
                    "legacy_renew_key": str(row["renew_key"]),
                    "legacy_position": int(row["position"]),
                    "legacy_is_valid": bool(row["is_valid"]),
                    "legacy_auto_renew": bool(row["auto_renew"]),
                    "legacy_last_test": nullable(row["last_test"]),
                    "legacy_last_renew": nullable(row["last_renew"]),
                    "legacy_renew_error": nullable(row["renew_error"]),
                    "legacy_region": nullable(row["region"]),
                },
                credential,
            )
        )
    return output


def load_qwen(path: Path) -> list[LegacyAccount]:
    with connect(path) as db:
        rows = db.execute("SELECT * FROM accounts ORDER BY id").fetchall()
    output: list[LegacyAccount] = []
    for row in rows:
        metadata = json_object(row["meta_json"])
        metadata.update(
            {
                "migration_source": "qwen2api-sqlite",
                "legacy_account_id": int(row["id"]),
                "legacy_status": str(row["status"]),
                "legacy_last_keepalive_ok": (
                    None if row["last_keepalive_ok"] is None else bool(row["last_keepalive_ok"])
                ),
                "legacy_success_count": int(row["success_count"] or 0),
                "legacy_fail_count": int(row["fail_count"] or 0),
                "legacy_last_error": nullable(row["last_error"]),
            }
        )
        external_id = nullable(row["user_id"]) or str(row["email"])
        output.append(
            LegacyAccount(
                "qwen",
                external_id,
                nullable(row["email"]),
                iso_epoch(row["token_expires_at"]),
                iso_epoch(row["token_expires_at"]),
                metadata,
                compact(
                    {
                        "token": row["token"],
                        "user_id": row["user_id"],
                        "email": row["email"],
                        "password": row["password"],
                        "name": row["name"],
                        "activate_url": row["activate_url"],
                        **json_object(row["meta_json"]),
                    }
                ),
            )
        )
    return output


class Any2ApiClient:
    def __init__(self, base_url: str, username: str, password: str) -> None:
        parsed = urllib.parse.urlparse(base_url)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ValueError("target must be an absolute HTTP(S) URL")
        self.base_url = base_url.rstrip("/")
        self.username = username
        self.password = password
        jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))

    def login(self) -> None:
        challenge = self.request("GET", "/api/admin/v1/login-challenge")
        answer = solve_expression(str(challenge["expression"]))
        nonce = solve_pow(str(challenge["challengeToken"]), int(challenge["difficulty"]))
        self.request(
            "POST",
            "/api/admin/v1/session",
            {
                "username": self.username,
                "password": self.password,
                "challengeToken": challenge["challengeToken"],
                "mathAnswer": str(answer),
                "powNonce": nonce,
            },
        )

    def list_accounts(self) -> list[dict[str, Any]]:
        result = self.request("GET", "/api/admin/v1/accounts")
        if not isinstance(result, list):
            raise RuntimeError("Any2API account list returned a non-list response")
        return result

    def import_account(self, account: LegacyAccount) -> dict[str, Any]:
        return self.request(
            "POST",
            "/api/admin/v1/accounts/import",
            {
                "providerId": account.provider_id,
                "externalId": account.external_id,
                "email": account.email,
                "expiresAt": account.expires_at,
                "credentialExpiresAt": account.credential_expires_at,
                "metadata": without_none(account.metadata),
                "priority": 0,
                "weight": 1,
                "maxConcurrency": 1,
                "status": "PENDING",
                "enabled": False,
                "credential": account.credential,
                "scheduleLifecycle": False,
            },
        )

    def schedule_probe(self, account_id: str, spread_seconds: int) -> None:
        self.request(
            "POST",
            f"/api/admin/v1/accounts/{urllib.parse.quote(account_id)}/probe",
            {"spreadSeconds": spread_seconds},
        )

    def request(self, method: str, path: str, body: Any = None) -> Any:
        data = None if body is None else json.dumps(body, separators=(",", ":")).encode()
        request = urllib.request.Request(
            self.base_url + path,
            data=data,
            method=method,
            headers={"Accept": "application/json", "Content-Type": "application/json"},
        )
        try:
            with self.opener.open(request, timeout=120) as response:
                payload = response.read()
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", "replace")[:800]
            raise RuntimeError(f"Any2API returned HTTP {error.code}: {detail}") from error
        return json.loads(payload) if payload else None


def solve_expression(expression: str) -> int:
    match = re.fullmatch(r"\s*(\d+)\s*([x+])\s*(\d+)\s*", expression)
    if not match:
        raise ValueError("unsupported login math expression")
    left, operator, right = int(match.group(1)), match.group(2), int(match.group(3))
    return left * right if operator == "x" else left + right


def solve_pow(token: str, difficulty: int) -> int:
    full_bytes, remaining = divmod(difficulty, 8)
    for nonce in range(1 << 63):
        digest = hashlib.sha256(f"{token}:{nonce}".encode()).digest()
        if any(digest[index] != 0 for index in range(full_bytes)):
            continue
        if remaining and digest[full_bytes] & (0xFF << (8 - remaining)):
            continue
        return nonce
    raise RuntimeError("proof of work nonce was not found")


def expected_target_keys(accounts: Iterable[LegacyAccount]) -> set[tuple[str, str]]:
    keys: set[tuple[str, str]] = set()
    for account in accounts:
        keys.add(account.key())
        if account.provider_id == "grok" and first_string(
            account.credential, "sso", "sso-rw", "sso_rw", "sso_token"
        ):
            keys.add(("grok_web", account.external_id))
            keys.add(("grok_console", account.external_id))
    return keys


def inventory_hash(keys: Iterable[tuple[str, str]]) -> str:
    value = "\n".join(f"{provider}:{external}" for provider, external in sorted(keys))
    return hashlib.sha256(value.encode()).hexdigest()


def ref(key: tuple[str, str]) -> str:
    return hashlib.sha256(f"{key[0]}:{key[1]}".encode()).hexdigest()[:16]


def nullable(value: Any) -> str | None:
    text = "" if value is None else str(value).strip()
    return text or None


def compact(value: dict[str, Any]) -> dict[str, Any]:
    return {key: item for key, item in value.items() if item not in (None, "")}


def without_none(value: dict[str, Any]) -> dict[str, Any]:
    return {key: item for key, item in value.items() if item is not None}


def first_string(value: dict[str, Any], *keys: str) -> str:
    for key in keys:
        item = value.get(key)
        if isinstance(item, str) and item.strip():
            return item.strip()
    return ""


def sanitize_error(value: str) -> str:
    result = re.sub(
        r"(?i)(bearer|token|password|cookie|authorization)([\s:=]+)[^\s,;}]+",
        r"\1\2<redacted>",
        value,
    )
    result = re.sub(
        r"[A-Z0-9._%+-]{1,64}@[A-Z0-9.-]+\.[A-Z]{2,}",
        "<redacted-email>",
        result,
        flags=re.IGNORECASE,
    )
    return result[:400]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--grok-db", type=Path)
    parser.add_argument("--longcat-db", type=Path)
    parser.add_argument("--mimo-db", type=Path)
    parser.add_argument("--qwen-db", type=Path)
    parser.add_argument("--target")
    parser.add_argument("--admin-username", default="admin")
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--schedule-probes", action="store_true")
    parser.add_argument("--probe-spread-seconds", type=int, default=86_400)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    loaders = [
        (args.grok_db, load_grok),
        (args.longcat_db, load_longcat),
        (args.mimo_db, load_mimo),
        (args.qwen_db, load_qwen),
    ]
    accounts: list[LegacyAccount] = []
    for path, loader in loaders:
        if path is not None:
            accounts.extend(loader(path))
    duplicates = [key for key, count in Counter(item.key() for item in accounts).items() if count > 1]
    if duplicates:
        raise RuntimeError(f"source snapshots contain {len(duplicates)} duplicate provider identities")
    source_keys = {item.key() for item in accounts}
    expected_keys = expected_target_keys(accounts)
    report: dict[str, Any] = {
        "generatedAt": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
        "mode": "apply" if args.apply else "dry-run",
        "sourceCounts": dict(sorted(Counter(item.provider_id for item in accounts).items())),
        "sourceTotal": len(accounts),
        "sourceInventorySha256": inventory_hash(source_keys),
        "expectedTargetCounts": dict(sorted(Counter(key[0] for key in expected_keys).items())),
        "expectedTargetTotal": len(expected_keys),
        "expectedTargetInventorySha256": inventory_hash(expected_keys),
        "credentialPresence": {
            provider: sum(1 for item in accounts if item.provider_id == provider and item.credential)
            for provider in sorted({item.provider_id for item in accounts})
        },
        "imported": 0,
        "updated": 0,
        "failed": 0,
        "failures": [],
        "reconciled": False,
        "scheduledProbes": 0,
    }
    if args.apply:
        password = os.environ.get("ANY2API_MIGRATION_ADMIN_PASSWORD", "")
        if not args.target or not password:
            raise RuntimeError("--target and ANY2API_MIGRATION_ADMIN_PASSWORD are required with --apply")
        client = Any2ApiClient(args.target, args.admin_username, password)
        client.login()
        existing = {
            (str(item.get("providerId", "")), str(item.get("externalId", "")))
            for item in client.list_accounts()
        }
        for account in accounts:
            try:
                client.import_account(account)
                if account.key() in existing:
                    report["updated"] += 1
                else:
                    report["imported"] += 1
            except Exception as error:  # report secret-silent identity and bounded error only
                report["failed"] += 1
                report["failures"].append(
                    {"provider": account.provider_id, "ref": ref(account.key()),
                     "error": sanitize_error(str(error))}
                )
        target_rows = client.list_accounts()
        target_by_key = {
            (str(item.get("providerId", "")), str(item.get("externalId", ""))): item
            for item in target_rows
        }
        missing = sorted(expected_keys - set(target_by_key))
        report["missingTargetRefs"] = [
            {"provider": key[0], "ref": ref(key)} for key in missing
        ]
        report["reconciled"] = report["failed"] == 0 and not missing
        if args.schedule_probes and report["reconciled"]:
            if not 1 <= args.probe_spread_seconds <= 604_800:
                raise ValueError("probe spread must be between 1 second and 7 days")
            for key in sorted(expected_keys):
                client.schedule_probe(str(target_by_key[key]["id"]), args.probe_spread_seconds)
                report["scheduledProbes"] += 1
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")
    print(json.dumps({key: report[key] for key in (
        "mode", "sourceCounts", "sourceTotal", "expectedTargetCounts",
        "expectedTargetTotal", "imported", "updated", "failed", "reconciled",
        "scheduledProbes")}, separators=(",", ":")))
    return 0 if report["failed"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
