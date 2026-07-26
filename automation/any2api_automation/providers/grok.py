from .base import AutomationProvider, AutomationProviderManifest


class GrokAutomationProvider(AutomationProvider):
    manifest = AutomationProviderManifest(
        id="grok",
        browser_backend="camoufox",
        fallback_backend="patchright",
        isolation="process",
        challenge_types=("turnstile",),
    )
