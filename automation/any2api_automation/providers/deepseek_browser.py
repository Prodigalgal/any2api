from __future__ import annotations

import asyncio
import base64
import json
import time
from typing import Any
from urllib.parse import quote

from .deepseek_settings import settings
from .multimodal import text_content
from .page_fetch_browser import PageFetchBrowserRuntime
from .runtime_rules import RuntimePlan

_RATE_BYTES = 136
_MASK = (1 << 64) - 1
_ROUND_CONSTANTS = (
    0x0000000000000001,
    0x0000000000008082,
    0x800000000000808A,
    0x8000000080008000,
    0x000000000000808B,
    0x0000000080000001,
    0x8000000080008081,
    0x8000000000008009,
    0x000000000000008A,
    0x0000000000000088,
    0x0000000080008009,
    0x000000008000000A,
    0x000000008000808B,
    0x800000000000008B,
    0x8000000000008089,
    0x8000000000008003,
    0x8000000000008002,
    0x8000000000000080,
    0x000000000000800A,
    0x800000008000000A,
    0x8000000080008081,
    0x8000000000008080,
    0x0000000080000001,
    0x8000000080008008,
)
_ROTATIONS = (
    0,
    1,
    62,
    28,
    27,
    36,
    44,
    6,
    55,
    20,
    3,
    10,
    43,
    25,
    39,
    41,
    45,
    15,
    21,
    8,
    18,
    2,
    61,
    56,
    14,
)


class DeepseekOfficialBrowserTransport(PageFetchBrowserRuntime):
    """Runs DeepSeek session creation, PoW and SSE in one Camoufox account page."""

    def __init__(self, base_url: str) -> None:
        super().__init__(
            "deepseek",
            base_url,
            allowed_domain_suffixes=("deepseek.com",),
            identity_fields=("email", "device_id", "token", "access_token"),
            page_url=base_url.rstrip("/") + "/sign_in",
        )

    async def models(
        self,
        credential: dict[str, Any],
        proxy_url: str,
        plan: RuntimePlan,
        *,
        runtime_options: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        device_id = _required(credential, "device_id")
        path = f"/api/v0/client/settings?did={quote(device_id, safe='')}&scope=model"
        return await self.request(
            credential,
            proxy_url,
            plan,
            method="GET",
            path=path,
            headers=_headers(credential, runtime_options),
            timeout_ms=plan.active.rules.canary_timeout_seconds * 1000,
        )

    async def chat_stream(
        self,
        credential: dict[str, Any],
        semantic_command: dict[str, Any],
        proxy_url: str,
        plan: RuntimePlan,
        *,
        runtime_options: dict[str, Any] | None = None,
    ):
        session_result = await self.request(
            credential,
            proxy_url,
            plan,
            method="POST",
            endpoint_key="session",
            headers=_headers(credential, runtime_options),
            body="{}",
            timeout_ms=plan.active.rules.canary_timeout_seconds * 1000,
        )
        session_id = _session_id(session_result)
        pow_result = await self.request(
            credential,
            proxy_url,
            plan,
            method="POST",
            endpoint_key="pow",
            headers=_headers(credential, runtime_options),
            body=json.dumps(
                {"target_path": "/api/v0/chat/completion"},
                ensure_ascii=True,
                separators=(",", ":"),
            ),
            timeout_ms=plan.active.rules.canary_timeout_seconds * 1000,
        )
        challenge = _challenge(pow_result)
        answer = await asyncio.to_thread(solve_pow, challenge)
        proof = base64.b64encode(
            json.dumps(
                {
                    "algorithm": challenge["algorithm"],
                    "challenge": challenge["challenge"],
                    "salt": challenge["salt"],
                    "answer": answer,
                    "signature": challenge["signature"],
                    "target_path": "/api/v0/chat/completion",
                },
                ensure_ascii=True,
                separators=(",", ":"),
            ).encode()
        ).decode()
        headers = _headers(credential, runtime_options)
        headers["X-DS-PoW-Response"] = proof
        body = build_deepseek_request(semantic_command, session_id)
        async for event in self.stream(
            credential,
            proxy_url,
            plan,
            method="POST",
            endpoint_key="completion",
            headers=headers,
            body=json.dumps(body, ensure_ascii=True, separators=(",", ":")),
            timeout_ms=300_000,
        ):
            yield event


def build_deepseek_request(command: dict[str, Any], session_id: str) -> dict[str, Any]:
    _validate_command(command)
    return {
        "chat_session_id": session_id,
        "parent_message_id": None,
        "model_type": str(command["model"]),
        "prompt": _prompt(command["messages"]),
        "ref_file_ids": [],
        "thinking_enabled": _thinking(command),
        "search_enabled": _search(command),
        "action": None,
        "preempt": False,
    }


def solve_pow(challenge: dict[str, Any]) -> int:
    _validate_challenge(challenge)
    difficulty = int(challenge["difficulty"])
    prefix = f"{challenge['salt']}_{int(challenge['expire_at'])}_".encode()
    target = bytes.fromhex(str(challenge["challenge"]))
    for answer in range(difficulty):
        if _hash_matches(prefix, answer, target):
            return answer
    raise RuntimeError("DeepSeek POW challenge has no solution in its search range")


def _hash_matches(prefix: bytes, answer: int, target: bytes) -> bool:
    suffix = str(answer).encode("ascii")
    if len(target) != 32 or len(prefix) + len(suffix) + 10 >= _RATE_BYTES:
        return False
    block = bytearray(_RATE_BYTES)
    block[: len(prefix)] = prefix
    offset = len(prefix)
    block[offset : offset + len(suffix)] = suffix
    length = offset + len(suffix)
    block[length] ^= 0x06
    block[_RATE_BYTES - 1] ^= 0x80
    state = [0] * 25
    for index, value in enumerate(block):
        state[index >> 3] ^= value << ((index & 7) << 3)
    _permute(state)
    actual = b"".join(
        ((state[index >> 3] >> ((index & 7) << 3)) & 0xFF).to_bytes(1, "little")
        for index in range(len(target))
    )
    return actual == target


def _permute(state: list[int]) -> None:
    for round_constant in _ROUND_CONSTANTS[1:]:
        columns = [
            state[x] ^ state[x + 5] ^ state[x + 10] ^ state[x + 15] ^ state[x + 20]
            for x in range(5)
        ]
        deltas = [columns[(x + 4) % 5] ^ _rotl(columns[(x + 1) % 5], 1) for x in range(5)]
        for y in range(5):
            for x in range(5):
                state[x + 5 * y] = (state[x + 5 * y] ^ deltas[x]) & _MASK
        moved = [0] * 25
        for y in range(5):
            for x in range(5):
                moved[y + 5 * ((2 * x + 3 * y) % 5)] = _rotl(
                    state[x + 5 * y], _ROTATIONS[x + 5 * y]
                )
        for y in range(5):
            for x in range(5):
                state[x + 5 * y] = (
                    moved[x + 5 * y] ^ ((~moved[(x + 1) % 5 + 5 * y]) & moved[(x + 2) % 5 + 5 * y])
                ) & _MASK
        state[0] = (state[0] ^ round_constant) & _MASK


def _rotl(value: int, count: int) -> int:
    if count == 0:
        return value & _MASK
    return ((value << count) | (value >> (64 - count))) & _MASK


def _headers(
    credential: dict[str, Any],
    runtime_options: dict[str, Any] | None = None,
) -> dict[str, str]:
    options = runtime_options if isinstance(runtime_options, dict) else {}
    config = settings()
    token = _required(credential, "token", "access_token")
    return {
        "Accept": "application/json, */*",
        "Content-Type": "application/json",
        "Authorization": f"Bearer {token}",
        "X-Client-Bundle-Id": str(
            credential.get("bundle_id") or options.get("bundle_id") or config.deepseek_bundle_id
        ),
        "X-Client-Platform": str(
            credential.get("platform") or options.get("platform") or config.deepseek_platform
        ),
        "X-Client-Version": str(
            credential.get("client_version")
            or options.get("client_version")
            or config.deepseek_client_version_fallback
        ),
        "X-Client-Locale": str(
            credential.get("locale") or options.get("locale") or config.deepseek_locale
        ),
        "X-Client-Timezone-Offset": str(
            credential.get("timezone_offset")
            or options.get("timezone_offset")
            or config.deepseek_timezone_offset_seconds
        ),
    }


def _session_id(result: dict[str, Any]) -> str:
    _require_success(result, "create session")
    value = _json_body(result)
    session_id = (
        value.get("data", {}).get("biz_data", {}).get("chat_session", {}).get("id", "")
        if isinstance(value.get("data"), dict)
        else ""
    )
    if not str(session_id).strip():
        raise RuntimeError("DeepSeek create session returned no id")
    return str(session_id).strip()


def _challenge(result: dict[str, Any]) -> dict[str, Any]:
    _require_success(result, "create POW challenge")
    value = _json_body(result)
    challenge = (
        value.get("data", {}).get("biz_data", {}).get("challenge", {})
        if isinstance(value.get("data"), dict)
        else {}
    )
    if not isinstance(challenge, dict):
        raise TypeError("DeepSeek POW response is missing challenge")
    return challenge


def _json_body(result: dict[str, Any]) -> dict[str, Any]:
    status = int(result.get("status") or 502)
    if status >= 400:
        raise RuntimeError(f"DeepSeek upstream returned HTTP {status}")
    try:
        value = json.loads(str(result.get("body") or ""))
    except json.JSONDecodeError as error:
        raise RuntimeError("DeepSeek upstream returned invalid JSON") from error
    if not isinstance(value, dict):
        raise TypeError("DeepSeek upstream returned an invalid envelope")
    return value


def _require_success(result: dict[str, Any], operation: str) -> None:
    value = _json_body(result)
    code = int(value.get("code") or 0)
    data = value.get("data") or {}
    biz_code = int(data.get("biz_code") or 0) if isinstance(data, dict) else -1
    if code != 0 or biz_code != 0:
        raise RuntimeError(f"DeepSeek {operation} was rejected code={code} biz_code={biz_code}")


def _prompt(messages: Any) -> str:
    if not isinstance(messages, list):
        raise TypeError("DeepSeek messages must be an array")
    sections: list[str] = []
    for message in messages:
        if not isinstance(message, dict):
            continue
        content = _content(message.get("content"))
        if content.strip():
            sections.append(f"[{str(message.get('role') or 'user').lower()}]\n{content}")
    if not sections:
        raise ValueError("DeepSeek prompt is empty")
    return "\n\n".join(sections)


def _content(value: Any) -> str:
    return text_content(value, "DeepSeek")


def _thinking(command: dict[str, Any]) -> bool:
    options = command["providerOptions"]
    if isinstance(options.get("thinking_enabled"), bool):
        return options["thinking_enabled"]
    raw = command.get("rawRequest")
    if isinstance(raw, dict) and isinstance(raw.get("enable_thinking"), bool):
        return raw["enable_thinking"]
    effort = str(
        command["reasoning"].get("effort")
        or (raw.get("reasoning_effort") if isinstance(raw, dict) else "")
        or ""
    ).lower()
    if effort:
        return effort not in {"none", "minimal"}
    return str(command.get("model") or "") == "expert"


def _search(command: dict[str, Any]) -> bool:
    options = command["providerOptions"]
    if isinstance(options.get("search_enabled"), bool):
        return options["search_enabled"]
    raw = command.get("rawRequest")
    if isinstance(raw, dict):
        for field in ("web_search", "enable_search", "search"):
            if isinstance(raw.get(field), bool):
                return raw[field]
    return any(
        isinstance(tool, dict)
        and str(tool.get("type") or "") in {"web_search", "web_search_preview", "search"}
        for tool in command.get("tools", [])
    )


def _validate_command(command: dict[str, Any]) -> None:
    if not isinstance(command, dict) or command.get("schemaVersion") != 1:
        raise ValueError("DeepSeek semantic command schema is unsupported")
    if not str(command.get("model") or "").strip():
        raise ValueError("DeepSeek semantic command requires a model")
    if not isinstance(command.get("messages"), list):
        raise TypeError("DeepSeek semantic command messages must be an array")
    for field in ("reasoning", "providerOptions"):
        if not isinstance(command.get(field), dict):
            raise TypeError(f"DeepSeek semantic command {field} must be an object")


def _validate_challenge(value: dict[str, Any]) -> None:
    if str(value.get("algorithm") or "") != "DeepSeekHashV1":
        raise ValueError("unsupported DeepSeek POW algorithm")
    challenge = str(value.get("challenge") or "")
    if len(challenge) != 64 or any(char not in "0123456789abcdefABCDEF" for char in challenge):
        raise ValueError("DeepSeek POW challenge must be a SHA3-256 digest")
    for field in ("salt", "signature", "target_path"):
        if not str(value.get(field) or "").strip():
            raise ValueError("DeepSeek POW challenge is incomplete")
    difficulty = int(value.get("difficulty") or 0)
    if difficulty < 1 or difficulty > 2_000_000:
        raise ValueError("DeepSeek POW difficulty is outside the safe range")
    expire_at = int(value.get("expire_at") or 0)
    if expire_at > 0 and expire_at <= int(time.time() * 1000):
        raise RuntimeError("DeepSeek POW challenge has expired")


def _required(source: dict[str, Any], *fields: str) -> str:
    for field in fields:
        value = str(source.get(field) or "").strip()
        if value:
            return value
    raise ValueError(f"DeepSeek credential requires one of: {', '.join(fields)}")
