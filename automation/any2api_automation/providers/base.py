from abc import ABC, abstractmethod
from collections.abc import AsyncIterator, Awaitable, Iterator
from dataclasses import dataclass
from typing import Any

from ..lifecycle.browser import (
    BrowserContextProfile,
    BrowserFingerprintPolicy,
    BrowserLaunchProfile,
)


@dataclass(frozen=True)
class AutomationProviderManifest:
    id: str
    browser_backend: str
    fallback_backend: str | None
    isolation: str
    challenge_types: tuple[str, ...]
    operations: tuple[str, ...] = ()
    realtime: bool = False
    inference_transport: bool = False
    registration_attempt_mode: str = "new_identity"


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

    def transport_request(
        self, payload: dict[str, Any]
    ) -> dict[str, Any] | Awaitable[dict[str, Any]]:
        raise NotImplementedError(f"request transport is not implemented for {self.manifest.id}")

    def transport_stream(
        self, payload: dict[str, Any]
    ) -> Iterator[bytes] | AsyncIterator[bytes]:
        raise NotImplementedError(f"stream transport is not implemented for {self.manifest.id}")

    def routers(self) -> tuple[Any, ...]:
        return ()

    async def close(self) -> None:
        return None

    def browser_context_profile(self) -> BrowserContextProfile:
        return BrowserContextProfile()

    def browser_launch_profile(self) -> BrowserLaunchProfile:
        return BrowserLaunchProfile()

    def browser_fingerprint_policy(self) -> BrowserFingerprintPolicy | None:
        return None
