from dataclasses import asdict

from .base import AutomationProvider


class AutomationProviderRegistry:
    def __init__(self, providers: list[AutomationProvider]) -> None:
        self._providers: dict[str, AutomationProvider] = {}
        for provider in providers:
            provider_id = provider.manifest.id
            if provider_id in self._providers:
                raise ValueError(f"duplicate automation provider id: {provider_id}")
            self._providers[provider_id] = provider

    def require(self, provider_id: str) -> AutomationProvider:
        try:
            return self._providers[provider_id]
        except KeyError as error:
            raise ValueError(f"unknown automation provider: {provider_id}") from error

    def public_manifests(self) -> list[dict[str, object]]:
        return [asdict(provider.manifest) for provider in self._providers.values()]
