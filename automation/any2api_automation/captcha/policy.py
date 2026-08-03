from __future__ import annotations

from contextvars import ContextVar, Token
from dataclasses import dataclass
from types import TracebackType
from typing import Any, Literal, cast

CaptchaAiMode = Literal["auto", "internal", "external"]


@dataclass(frozen=True)
class CaptchaAiPolicy:
    enabled: bool = True
    mode: CaptchaAiMode = "internal"

    @classmethod
    def from_payload(cls, payload: dict[str, Any]) -> CaptchaAiPolicy:
        raw = payload.get("captcha")
        if raw is None:
            return cls()
        if not isinstance(raw, dict):
            raise TypeError("captcha policy must be an object")
        enabled = raw.get("ai_enabled", True)
        if not isinstance(enabled, bool):
            raise TypeError("captcha ai_enabled must be boolean")
        mode = str(raw.get("ai_mode") or "internal").strip().lower()
        if mode not in {"auto", "internal", "external"}:
            raise ValueError("captcha ai_mode is unsupported")
        return cls(enabled=enabled, mode=cast(CaptchaAiMode, mode))


_current_policy: ContextVar[CaptchaAiPolicy | None] = ContextVar(
    "any2api_captcha_ai_policy", default=None
)


def current_captcha_policy() -> CaptchaAiPolicy:
    return _current_policy.get() or CaptchaAiPolicy()


class _CaptchaPolicyBinding:
    def __init__(self, policy: CaptchaAiPolicy) -> None:
        self.policy = policy
        self.token: Token[CaptchaAiPolicy | None] | None = None

    def __enter__(self) -> None:
        self.token = _current_policy.set(self.policy)

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        if self.token is not None:
            _current_policy.reset(self.token)


def bind_captcha_policy(policy: CaptchaAiPolicy) -> _CaptchaPolicyBinding:
    return _CaptchaPolicyBinding(policy)
