from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="ANY2API_AUTOMATION_", extra="ignore")

    service_name: str = "any2api-automation"
    internal_token: str = ""
    redis_url: str = "redis://localhost:6379/0"
    java_base_url: str = "http://localhost:8080"
    browser_realtime_capacity: int = 1
    browser_batch_capacity: int = 2
    browser_transport_allowed_origins: str = ""
    browser_transport_session_ttl_seconds: int = 180
    browser_transport_max_buffered_bytes: int = 12 * 1024 * 1024
    browser_clearance_timeout_seconds: int = 120
    browser_clearance_attempts: int = 2
    captcha_ocr_concurrency: int = 4
    captcha_slider_concurrency: int = 2
    captcha_recognizer_concurrency: int = 1
    captcha_ai_enabled: bool = False
    public_api_key: str = Field(default="", validation_alias="ANY2API_PUBLIC_API_KEY")
    captcha_ai_api_base: str = ""
    captcha_ai_api_key: str = ""
    captcha_ai_model: str = ""
    captcha_ai_prompt_prefix: str = ""
    captcha_ai_action_samples: int = 5
    captcha_ai_action_sample_timeout_seconds: int = 60
    captcha_ai_timeout_seconds: int = 90
    captcha_diagnostics_dir: str = ""
    registration_headless: bool = True
    registration_timeout_seconds: int = 360
    registration_proxy_url: str = ""
    registration_use_dynamic_proxy: bool = False
    dynamic_proxy_subscription_url: str = ""
    dynamic_proxy_singbox_path: str = "/usr/local/bin/sing-box"
    dynamic_proxy_max_attempts: int = 5
    dynamic_proxy_lease_seconds: int = 900
    dynamic_proxy_distributed_leases: bool = True
    mail_api_base: str = ""
    mail_admin_password: str = ""
    mail_site_password: str = ""
    mail_domain: str = ""
    mail_poll_seconds: float = 4.0
    mail_timeout_seconds: int = 240
    provider_user_agent: str = (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/150 Safari/537.36"
    )


@lru_cache
def settings() -> Settings:
    return Settings()
