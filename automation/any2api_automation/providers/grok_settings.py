from functools import lru_cache

from .provider_config import AutomationProviderSettings


class GrokSettings(AutomationProviderSettings):
    grok_base_url: str = "https://cli-chat-proxy.grok.com/v1"
    grok_token_url: str = "https://auth.x.ai/oauth2/token"
    grok_client_id: str = "xai-grok-cli"
    grok_oauth_client_id: str = "b1a00492-073a-47ea-816f-4c329264a828"
    grok_oauth_redirect_uri: str = "http://127.0.0.1:1455/auth/callback"
    grok_oauth_scopes: str = (
        "openid profile email offline_access grok-cli:access api:access "
        "conversations:read conversations:write workspaces:read workspaces:write"
    )
    grok_client_version: str = "0.2.112"
    grok_oauth_client_surface: str = "ui"
    grok_oauth_referrer: str = "grok-build"
    grok_token_auth: str = "xai-grok-cli"
    grok_client_identifier: str = "grok-shell"
    grok_keepalive_model: str = "grok-4.5"
    grok_signup_url: str = "https://accounts.x.ai/sign-up?redirect=grok-com"
    grok_signin_url: str = "https://accounts.x.ai/sign-in?redirect=grok-com"
    grok_authorize_url: str = "https://auth.x.ai/oauth2/authorize"
    grok_turnstile_rounds: int = 2
    grok_turnstile_timeout_seconds: int = 55
    grok_turnstile_use_flow_proxy: bool = True
    grok_registration_attempts: int = 3
    grok_oauth_settle_seconds: float = 45
    grok_oauth_http_timeout_seconds: float = 30
    grok_oauth_poll_timeout_seconds: float = 60


@lru_cache
def settings() -> GrokSettings:
    return GrokSettings()
