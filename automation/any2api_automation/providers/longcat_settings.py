from functools import lru_cache

from .provider_config import AutomationProviderSettings


class LongcatSettings(AutomationProviderSettings):
    longcat_base_url: str = "https://longcat.chat"
    longcat_app_key: str = "fe_com.sankuai.friday.fe.longcat"
    longcat_language: str = "zh"
    longcat_requested_with: str = "XMLHttpRequest"
    longcat_keepalive_agent_id: str = "1"
    longcat_passport_url: str = "https://passport.mykeeta.com"
    longcat_join_key: str = "1101498_851697727"
    longcat_token_id: str = "5oTEq210UBLUcm4tcuuy6A"
    longcat_service: str = "consumer"
    longcat_region: str = "HK"
    longcat_city_id: str = "810001"
    longcat_risk_cost_id: str = "119801"
    longcat_theme: str = "longcat"
    longcat_locale: str = "en"
    longcat_h5guard_wait_ms: int = 8000
    longcat_navigation_timeout_ms: int = 120000
    longcat_otp_ui_timeout_ms: int = 120000
    longcat_after_action_ms: int = 6000
    longcat_submit_attempts: int = 4
    longcat_yoda_attempts: int = 2
    longcat_registration_attempts: int = 3
    longcat_slider_tolerance_px: float = 1.0
    longcat_slider_loop_ms: int = 1400
    longcat_slider_drag_seconds: float = 0.55
    longcat_slider_kick_fraction: float = 0.82
    longcat_slider_fudge_px: float = 0.0
    longcat_slider_distance_scale: float = 1.0


@lru_cache
def settings() -> LongcatSettings:
    return LongcatSettings()
