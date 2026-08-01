from functools import lru_cache

from .provider_config import AutomationProviderSettings


class GlmSettings(AutomationProviderSettings):
    glm_base_url: str = "https://chat.z.ai"
    glm_captcha_script_url: str = (
        "https://o.alicdn.com/captcha-frontend/aliyunCaptcha/AliyunCaptcha.js"
    )
    glm_captcha_prefix: str = "no8xfe"
    glm_auth_captcha_region: str = "cn"
    glm_chat_captcha_region: str = "sgp"
    glm_auth_captcha_scene_id: str = "36qgs6xb"
    glm_chat_captcha_scene_id: str = "didk33e0"
    glm_captcha_attempts: int = 1
    glm_captcha_rounds_per_attempt: int = 4
    glm_captcha_timeout_seconds: int = 120
    glm_official_captcha_wait_seconds: int = 20
    glm_registration_browser_attempts: int = 8


@lru_cache
def settings() -> GlmSettings:
    return GlmSettings()
