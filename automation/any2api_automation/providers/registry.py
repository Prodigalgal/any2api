import asyncio
import re
from dataclasses import asdict

from .base import AutomationProvider
from .channels import ActionBindingRegistry, ProviderActionDispatcher

_PROVIDER_ID = re.compile(r"^[a-z][a-z0-9_-]{1,31}$")
_PROVIDER_OPERATIONS = frozenset({"register", "reauthenticate", "keepalive", "daily_checkin"})
_REGISTRATION_ATTEMPT_MODES = frozenset({"new_identity", "single_identity"})


class AutomationProviderRegistry:
    def __init__(self, providers: list[AutomationProvider]) -> None:
        self._providers: dict[str, AutomationProvider] = {}
        for provider in sorted(providers, key=lambda item: item.manifest.id):
            provider_id = provider.manifest.id
            if not _PROVIDER_ID.fullmatch(provider_id):
                raise ValueError(f"invalid automation provider id: {provider_id}")
            unknown_operations = set(provider.manifest.operations) - _PROVIDER_OPERATIONS
            if unknown_operations:
                names = ", ".join(sorted(unknown_operations))
                raise ValueError(f"unsupported automation operations for {provider_id}: {names}")
            if provider.manifest.registration_attempt_mode not in _REGISTRATION_ATTEMPT_MODES:
                raise ValueError(
                    "unsupported registration attempt mode for "
                    f"{provider_id}: {provider.manifest.registration_attempt_mode}"
                )
            if provider_id in self._providers:
                raise ValueError(f"duplicate automation provider id: {provider_id}")
            self._providers[provider_id] = provider
        self._action_bindings = ActionBindingRegistry(self._providers.values())

    def require(self, provider_id: str) -> AutomationProvider:
        try:
            return self._providers[provider_id]
        except KeyError as error:
            raise ValueError(f"unknown automation provider: {provider_id}") from error

    def public_manifests(self) -> list[dict[str, object]]:
        manifests: list[dict[str, object]] = []
        for provider in self._providers.values():
            manifest = asdict(provider.manifest)
            manifest["actions"] = self._action_bindings.describe(provider.manifest.id)
            manifests.append(manifest)
        return manifests

    def action_dispatcher(self) -> ProviderActionDispatcher:
        return ProviderActionDispatcher(self, self._action_bindings)

    def routers(self) -> list[object]:
        return [router for provider in self._providers.values() for router in provider.routers()]

    async def close(self) -> None:
        await asyncio.gather(*(provider.close() for provider in self._providers.values()))
