from functools import lru_cache

from .provider_config import AutomationProviderSettings


class MinmaxSettings(AutomationProviderSettings):
    minmax_base_url: str = "https://agent.minimax.io"
    minmax_stream_base_url: str = "https://agent-stream.minimax.io"
    minmax_account_url: str = "https://account.minimax.io"
    minmax_client_id: str = "agent-minimax"
    minmax_require_dynamic_proxy: bool = True
    minmax_version_code: str = ""
    minmax_signature_salt: str = ""
    minmax_yy_salt: str = ""
    minmax_profile_asset_hosts: str = "cdn.hailuo.ai,cdn.hailuoai.com"
    minmax_profile_device_platform: str = "web"
    minmax_profile_biz_id: str = "3"
    minmax_profile_app_id: str = "3001"
    minmax_profile_language: str = "en"


@lru_cache
def settings() -> MinmaxSettings:
    return MinmaxSettings()
