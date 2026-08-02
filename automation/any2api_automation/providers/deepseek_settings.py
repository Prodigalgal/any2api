from functools import lru_cache

from pydantic import Field

from .provider_config import AutomationProviderSettings


class DeepseekSettings(AutomationProviderSettings):
    deepseek_base_url: str = "https://chat.deepseek.com"
    deepseek_client_version_fallback: str = "2.3.0"
    deepseek_bundle_id: str = "com.deepseek.chat"
    deepseek_platform: str = "web"
    deepseek_locale: str = "en_US"
    deepseek_timezone_offset_seconds: int = 32400
    deepseek_hcaptcha_attempts: int = Field(default=5, ge=1, le=10)
    deepseek_hcaptcha_timeout_seconds: int = Field(default=180, ge=30, le=300)
    deepseek_registration_browser_attempts: int = Field(default=4, ge=1, le=8)


@lru_cache
def settings() -> DeepseekSettings:
    return DeepseekSettings()
