from functools import lru_cache

from .provider_config import AutomationProviderSettings


class MimoSettings(AutomationProviderSettings):
    mimo_base_url: str = "https://aistudio.xiaomimimo.com"
    mimo_account_url: str = "https://account.xiaomi.com"
    mimo_service_id: str = "xiaomichatbot"
    mimo_registration_region: str = "RANDOM"
    mimo_registration_public_key_der: str = ""
    mimo_registration_aes_iv: str = "0102030405060708"
    mimo_registration_captcha_attempts: int = 10
    mimo_registration_local_captcha_attempts: int = 3
    mimo_timezone: str = "Asia/Shanghai"


@lru_cache
def settings() -> MimoSettings:
    return MimoSettings()
