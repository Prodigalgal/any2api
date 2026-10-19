from __future__ import annotations

import base64
import ctypes
import json
import struct
import time
from pathlib import Path
from typing import Any
from urllib.parse import quote

from ..blocking_work import run_blocking
from .base import validate_semantic_command
from .deepseek_settings import settings
from .multimodal import text_content
from .page_fetch_browser import PageFetchBrowserRuntime
from .runtime_rules import RuntimePlan

_RATE_BYTES = 136


def _load_native_pow() -> Any:
    for candidate in (
        Path(__file__).parent / "libdeepseek_pow.so",
        Path(__file__).parent / "deepseek_pow.so",
        Path(__file__).parent / "deepseek_pow.dll",
        Path("/app/lib/libdeepseek_pow.so"),
    ):
        if candidate.is_file():
            try:
                lib = ctypes.CDLL(str(candidate))
                lib.solve_pow_c.argtypes = [
                    ctypes.c_char_p,
                    ctypes.c_int,
                    ctypes.c_char_p,
                    ctypes.c_int,
                    ctypes.c_int,
                ]
                lib.solve_pow_c.restype = ctypes.c_int
                return lib
            except Exception:  # noqa: BLE001, S110
                pass
    return None


_NATIVE_POW_LIB = _load_native_pow()
_MASK = (1 << 64) - 1
DEEPSEEK_SESSION_PATH = "/api/v0/chat_session/create"
DEEPSEEK_POW_PATH = "/api/v0/chat/create_pow_challenge"
DEEPSEEK_COMPLETION_PATH = "/api/v0/chat/completion"
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
                {"target_path": DEEPSEEK_COMPLETION_PATH},
                ensure_ascii=True,
                separators=(",", ":"),
            ),
            timeout_ms=plan.active.rules.canary_timeout_seconds * 1000,
        )
        challenge = _challenge(pow_result)
        answer = await run_blocking(solve_pow, challenge)
        proof = build_pow_proof(challenge, answer)
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


def _fast_search_pow(
    prefix: bytes,
    target_words: tuple[int, int, int, int],
    start: int,
    end: int,
) -> int:
    tw0, tw1, tw2, tw3 = target_words
    lp = len(prefix)
    buf = bytearray(_RATE_BYTES)
    buf[:lp] = prefix
    buf[135] = 0x80

    m = _MASK
    rc_list = _ROUND_CONSTANTS[1:]
    for ans in range(start, end):
        suffix = str(ans).encode("ascii")
        ls = len(suffix)
        buf[lp : lp + ls] = suffix
        buf[lp + ls] = 0x06
        w = list(struct.unpack("<17Q", buf))
        buf[lp + ls] = 0
        w.extend([0] * 8)

        w0, w1, w2, w3, w4 = w[0], w[1], w[2], w[3], w[4]
        w5, w6, w7, w8, w9 = w[5], w[6], w[7], w[8], w[9]
        w10, w11, w12, w13, w14 = w[10], w[11], w[12], w[13], w[14]
        w15, w16, w17, w18, w19 = w[15], w[16], w[17], w[18], w[19]
        w20, w21, w22, w23, w24 = w[20], w[21], w[22], w[23], w[24]

        for rc in rc_list:
            c0 = w0 ^ w5 ^ w10 ^ w15 ^ w20
            c1 = w1 ^ w6 ^ w11 ^ w16 ^ w21
            c2 = w2 ^ w7 ^ w12 ^ w17 ^ w22
            c3 = w3 ^ w8 ^ w13 ^ w18 ^ w23
            c4 = w4 ^ w9 ^ w14 ^ w19 ^ w24

            d0 = c4 ^ (((c1 << 1) | (c1 >> 63)) & m)
            d1 = c0 ^ (((c2 << 1) | (c2 >> 63)) & m)
            d2 = c1 ^ (((c3 << 1) | (c3 >> 63)) & m)
            d3 = c2 ^ (((c4 << 1) | (c4 >> 63)) & m)
            d4 = c3 ^ (((c0 << 1) | (c0 >> 63)) & m)

            s0 = w0 ^ d0
            s1 = w1 ^ d1
            s2 = w2 ^ d2
            s3 = w3 ^ d3
            s4 = w4 ^ d4
            s5 = w5 ^ d0
            s6 = w6 ^ d1
            s7 = w7 ^ d2
            s8 = w8 ^ d3
            s9 = w9 ^ d4
            s10 = w10 ^ d0
            s11 = w11 ^ d1
            s12 = w12 ^ d2
            s13 = w13 ^ d3
            s14 = w14 ^ d4
            s15 = w15 ^ d0
            s16 = w16 ^ d1
            s17 = w17 ^ d2
            s18 = w18 ^ d3
            s19 = w19 ^ d4
            s20 = w20 ^ d0
            s21 = w21 ^ d1
            s22 = w22 ^ d2
            s23 = w23 ^ d3
            s24 = w24 ^ d4

            b0 = s0
            b1 = ((s6 << 44) | (s6 >> 20)) & m
            b2 = ((s12 << 43) | (s12 >> 21)) & m
            b3 = ((s18 << 21) | (s18 >> 43)) & m
            b4 = ((s24 << 14) | (s24 >> 50)) & m
            b5 = ((s3 << 28) | (s3 >> 36)) & m
            b6 = ((s9 << 20) | (s9 >> 44)) & m
            b7 = ((s10 << 3) | (s10 >> 61)) & m
            b8 = ((s16 << 45) | (s16 >> 19)) & m
            b9 = ((s22 << 61) | (s22 >> 3)) & m
            b10 = ((s1 << 1) | (s1 >> 63)) & m
            b11 = ((s7 << 6) | (s7 >> 58)) & m
            b12 = ((s13 << 25) | (s13 >> 39)) & m
            b13 = ((s19 << 8) | (s19 >> 56)) & m
            b14 = ((s20 << 18) | (s20 >> 46)) & m
            b15 = ((s4 << 27) | (s4 >> 37)) & m
            b16 = ((s5 << 36) | (s5 >> 28)) & m
            b17 = ((s11 << 10) | (s11 >> 54)) & m
            b18 = ((s17 << 15) | (s17 >> 49)) & m
            b19 = ((s23 << 56) | (s23 >> 8)) & m
            b20 = ((s2 << 62) | (s2 >> 2)) & m
            b21 = ((s8 << 55) | (s8 >> 9)) & m
            b22 = ((s14 << 39) | (s14 >> 25)) & m
            b23 = ((s15 << 41) | (s15 >> 23)) & m
            b24 = ((s21 << 2) | (s21 >> 62)) & m

            w0 = (b0 ^ ((~b1) & b2)) ^ rc
            w1 = b1 ^ ((~b2) & b3)
            w2 = b2 ^ ((~b3) & b4)
            w3 = b3 ^ ((~b4) & b0)
            w4 = b4 ^ ((~b0) & b1)
            w5 = b5 ^ ((~b6) & b7)
            w6 = b6 ^ ((~b7) & b8)
            w7 = b7 ^ ((~b8) & b9)
            w8 = b8 ^ ((~b9) & b5)
            w9 = b9 ^ ((~b5) & b6)
            w10 = b10 ^ ((~b11) & b12)
            w11 = b11 ^ ((~b12) & b13)
            w12 = b12 ^ ((~b13) & b14)
            w13 = b13 ^ ((~b14) & b10)
            w14 = b14 ^ ((~b10) & b11)
            w15 = b15 ^ ((~b16) & b17)
            w16 = b16 ^ ((~b17) & b18)
            w17 = b17 ^ ((~b18) & b19)
            w18 = b18 ^ ((~b19) & b15)
            w19 = b19 ^ ((~b15) & b16)
            w20 = b20 ^ ((~b21) & b22)
            w21 = b21 ^ ((~b22) & b23)
            w22 = b22 ^ ((~b23) & b24)
            w23 = b23 ^ ((~b24) & b20)
            w24 = b24 ^ ((~b20) & b21)

        if w0 == tw0 and w1 == tw1 and w2 == tw2 and w3 == tw3:
            return ans
    return -1


def solve_pow(challenge: dict[str, Any]) -> int:
    _validate_challenge(challenge)
    difficulty = int(challenge["difficulty"])
    prefix = f"{challenge['salt']}_{int(challenge['expire_at'])}_".encode()
    target = bytes.fromhex(str(challenge["challenge"]))
    if len(target) != 32:
        raise ValueError("DeepSeek POW challenge must be a SHA3-256 digest")

    if _NATIVE_POW_LIB is not None:
        try:
            ans = _NATIVE_POW_LIB.solve_pow_c(prefix, len(prefix), target, 0, difficulty)
            if ans >= 0:
                return ans
        except Exception:  # noqa: BLE001, S110
            pass

    target_words = struct.unpack("<4Q", target)
    ans = _fast_search_pow(prefix, target_words, 0, difficulty)
    if ans >= 0:
        return ans
    raise RuntimeError("DeepSeek POW challenge has no solution in its search range")


def build_pow_proof(challenge: dict[str, Any], answer: int) -> str:
    """Serialize the provider-signed PoW result for the completion endpoint."""

    _validate_challenge(challenge)
    if isinstance(answer, bool) or not isinstance(answer, int):
        raise TypeError("DeepSeek POW answer must be an integer")
    difficulty = int(challenge["difficulty"])
    if answer < 0 or answer >= difficulty:
        raise ValueError("DeepSeek POW answer is outside the challenge range")
    return base64.b64encode(
        json.dumps(
            {
                "algorithm": challenge["algorithm"],
                "challenge": challenge["challenge"],
                "salt": challenge["salt"],
                "answer": answer,
                "signature": challenge["signature"],
                "target_path": challenge["target_path"],
            },
            ensure_ascii=True,
            separators=(",", ":"),
        ).encode()
    ).decode()


def _hash_matches(prefix: bytes, answer: int, target: bytes) -> bool:
    if len(target) != 32 or len(prefix) + len(str(answer)) + 10 >= _RATE_BYTES:
        return False
    target_words = struct.unpack("<4Q", target)
    return _fast_search_pow(prefix, target_words, answer, answer + 1) == answer


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
    if status < 200 or status >= 300:
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
        if code == 40003 or (
            isinstance(data, dict) and "invalid token" in str(data.get("msg", "")).lower()
        ):
            from .api_transport import ApiActionError

            raise ApiActionError(
                f"DeepSeek {operation} authorization expired code={code} biz_code={biz_code}",
                status=401,
                body=json.dumps(value),
            )
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
    controls = command["controls"]
    if isinstance(controls.get("enable_thinking"), bool):
        return controls["enable_thinking"]
    effort = str(
        command["reasoning"].get("effort") or controls.get("reasoning_effort") or ""
    ).lower()
    if effort:
        return effort not in {"none", "minimal"}
    return str(command.get("model") or "") == "expert"


def _search(command: dict[str, Any]) -> bool:
    options = command["providerOptions"]
    if isinstance(options.get("search_enabled"), bool):
        return options["search_enabled"]
    controls = command["controls"]
    for field in ("web_search", "enable_search", "search"):
        if isinstance(controls.get(field), bool):
            return controls[field]
    return any(
        isinstance(tool, dict)
        and str(tool.get("type") or "") in {"web_search", "web_search_preview", "search"}
        for tool in command.get("tools", [])
    )


def _validate_command(command: dict[str, Any]) -> None:
    validate_semantic_command(command, "DeepSeek")


def _validate_challenge(value: dict[str, Any]) -> None:
    if str(value.get("algorithm") or "") != "DeepSeekHashV1":
        raise ValueError("unsupported DeepSeek POW algorithm")
    challenge = str(value.get("challenge") or "")
    if len(challenge) != 64 or any(char not in "0123456789abcdefABCDEF" for char in challenge):
        raise ValueError("DeepSeek POW challenge must be a SHA3-256 digest")
    for field in ("salt", "signature", "target_path"):
        if not str(value.get(field) or "").strip():
            raise ValueError("DeepSeek POW challenge is incomplete")
    if str(value["target_path"]).strip() != DEEPSEEK_COMPLETION_PATH:
        raise ValueError("DeepSeek POW challenge target path is unsupported")
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
