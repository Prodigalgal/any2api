from .base import AutomationProvider, AutomationProviderManifest


class QwenAutomationProvider(AutomationProvider):
    manifest = AutomationProviderManifest(
        id="qwen",
        browser_backend="camoufox",
        fallback_backend="patchright",
        isolation="process",
        challenge_types=("slider",),
        realtime=True,
    )
