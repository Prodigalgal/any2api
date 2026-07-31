from pydantic_settings import BaseSettings, SettingsConfigDict


class AutomationProviderSettings(BaseSettings):
    """Provider-local settings base that preserves the public environment namespace."""

    model_config = SettingsConfigDict(
        env_prefix="ANY2API_AUTOMATION_",
        extra="ignore",
    )
