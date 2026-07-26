from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="ANY2API_AUTOMATION_", extra="ignore")

    service_name: str = "any2api-automation"
    internal_token: str = ""
    redis_url: str = "redis://localhost:6379/0"
    java_base_url: str = "http://localhost:8080"
    browser_realtime_capacity: int = 1
    browser_batch_capacity: int = 2
    captcha_ocr_concurrency: int = 4
    captcha_slider_concurrency: int = 2
    captcha_recognizer_concurrency: int = 1


@lru_cache
def settings() -> Settings:
    return Settings()
