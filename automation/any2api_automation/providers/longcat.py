from .base import AutomationProvider, AutomationProviderManifest


class LongcatAutomationProvider(AutomationProvider):
    manifest = AutomationProviderManifest(
        id="longcat",
        browser_backend="camoufox",
        fallback_backend="patchright",
        isolation="process",
        challenge_types=("slider", "tap", "dots"),
    )
