from .base import AutomationProvider, AutomationProviderManifest


class MimoAutomationProvider(AutomationProvider):
    manifest = AutomationProviderManifest(
        id="mimo",
        browser_backend="http",
        fallback_backend=None,
        isolation="request",
        challenge_types=("ocr",),
    )
