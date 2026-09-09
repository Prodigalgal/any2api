from abc import ABC, abstractmethod
from collections.abc import AsyncIterator, Awaitable, Iterator
from dataclasses import dataclass
from typing import Any

from ..lifecycle.browser import (
    BrowserContextProfile,
    BrowserFingerprintPolicy,
    BrowserLaunchProfile,
)

CAMOUFOX_BROWSER_RUNTIME = "camoufox_browser_runtime"
NO_INFERENCE_RUNTIME = "none"
_INFERENCE_RUNTIMES = frozenset({NO_INFERENCE_RUNTIME, CAMOUFOX_BROWSER_RUNTIME})


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
    inference_runtime: str = NO_INFERENCE_RUNTIME
    registration_attempt_mode: str = "new_identity"

    def __post_init__(self) -> None:
        if self.inference_runtime not in _INFERENCE_RUNTIMES:
            raise ValueError(
                f"unsupported inference runtime for {self.id}: {self.inference_runtime}"
            )
        if self.inference_transport and self.inference_runtime != CAMOUFOX_BROWSER_RUNTIME:
            raise ValueError(f"inference provider {self.id} must use {CAMOUFOX_BROWSER_RUNTIME}")
        if not self.inference_transport and self.inference_runtime != NO_INFERENCE_RUNTIME:
            raise ValueError(
                f"provider {self.id} cannot declare an inference runtime without transport"
            )


class DailyCheckinStrategy(ABC):
    """Provider-specific implementation of the shared daily-checkin semantic."""

    @abstractmethod
    async def execute(self, payload: dict[str, Any]) -> dict[str, Any]:
        raise NotImplementedError


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

    async def daily_checkin(self, payload: dict[str, Any]) -> dict[str, Any]:
        raise NotImplementedError(f"daily_checkin is not implemented for {self.manifest.id}")

    def transport_request(
        self, payload: dict[str, Any]
    ) -> dict[str, Any] | Awaitable[dict[str, Any]]:
        raise NotImplementedError(f"request transport is not implemented for {self.manifest.id}")

    def transport_stream(self, payload: dict[str, Any]) -> Iterator[bytes] | AsyncIterator[bytes]:
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
