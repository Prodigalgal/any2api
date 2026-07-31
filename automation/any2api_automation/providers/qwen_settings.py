from functools import lru_cache

from .provider_config import AutomationProviderSettings


class QwenSettings(AutomationProviderSettings):
    qwen_base_url: str = "https://chat.qwen.ai"
    qwen_risk_headless: bool = True
    qwen_source: str = "web"
    qwen_captcha_appear_ms: int = 25000
    qwen_slider_drag_offset_px: float = 0.0
    qwen_slider_clear_streak: int = 15
    qwen_signup_attempts: int = 5
    qwen_slider_drag_budget_ms: int = 1000
    qwen_slider_tolerance_px: float = 2.5


@lru_cache
def settings() -> QwenSettings:
    return QwenSettings()
