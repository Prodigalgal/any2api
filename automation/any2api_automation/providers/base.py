from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class AutomationProviderManifest:
    id: str
    browser_backend: str
    fallback_backend: str | None
    isolation: str
    challenge_types: tuple[str, ...]
    realtime: bool = False


class AutomationProvider(ABC):
    @property
    @abstractmethod
    def manifest(self) -> AutomationProviderManifest:
        raise NotImplementedError

    async def register(self, payload: dict[str, Any]) -> dict[str, Any]:
        raise NotImplementedError(f"registration is not implemented for {self.manifest.id}")

    async def reauthenticate(self, payload: dict[str, Any]) -> dict[str, Any]:
        raise NotImplementedError(f"reauthentication is not implemented for {self.manifest.id}")

    async def keepalive(self, payload: dict[str, Any]) -> dict[str, Any]:
        raise NotImplementedError(f"keepalive is not implemented for {self.manifest.id}")
