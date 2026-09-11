from functools import lru_cache

from pydantic import Field

from .provider_config import AutomationProviderSettings


class ArenaSettings(AutomationProviderSettings):
    """Arena Web runtime and the single-identity email registration settings."""

    arena_base_url: str = "https://arena.ai"
    arena_page_path: str = "/text/direct?model_a=max"
    arena_registration_path: str = "/nextjs-api/sign-up/magic-link"
    arena_password_path: str = "/nextjs-api/auth/set-password"
    arena_me_path: str = "/api/me"
    arena_chat_path: str = "/nextjs-api/stream/create-evaluation"
    arena_recaptcha_site_key: str = Field(
        default="6LeTGMcsAAAAALuIlkVwIxaAuZA8VledA6d3Nnb0",
        min_length=20,
        max_length=128,
    )
    arena_recaptcha_v2_site_key: str = Field(
        default="6Le3_cYsAAAAAGwWOK2RLDgNI15Bh8C0yLBOL1yL",
        min_length=20,
        max_length=128,
    )
    arena_recaptcha_v2_timeout_seconds: int = Field(default=60, ge=10, le=180)
    arena_full_name: str = "Any2API Test"
    arena_registration_mail_timeout_seconds: int = Field(default=240, ge=30, le=3600)
    arena_max_prompt_bytes: int = Field(default=512 * 1024, ge=1, le=2 * 1024 * 1024)
    arena_max_attachment_bytes: int = Field(default=20 * 1024 * 1024, ge=1, le=50 * 1024 * 1024)
    arena_max_total_attachment_bytes: int = Field(
        default=40 * 1024 * 1024, ge=1, le=100 * 1024 * 1024
    )
    arena_max_attachments: int = Field(default=10, ge=1, le=10)


@lru_cache
def settings() -> ArenaSettings:
    return ArenaSettings()
