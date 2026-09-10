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
API_TRANSPORT = "api"
NO_INFERENCE_RUNTIME = "none"
_INFERENCE_RUNTIMES = frozenset({NO_INFERENCE_RUNTIME, CAMOUFOX_BROWSER_RUNTIME})
_INFERENCE_MODES = frozenset({API_TRANSPORT, CAMOUFOX_BROWSER_RUNTIME})
_INFERENCE_ACTIONS = frozenset(
    {
        "model_discovery",
        "chat",
        "provider_query",
        "media_policy",
        "media_callback",
        "raw_request",
    }
)


def reject_raw_request(command: Any, provider_id: str) -> None:
    """Keep the public request opaque to Runtime/API provider implementations."""

    if isinstance(command, dict) and ("rawRequest" in command or "raw_request" in command):
        raise ValueError(
            f"{provider_id} semantic command must not contain rawRequest; "
            "use canonical fields, controls, or providerOptions"
        )


def validate_semantic_command(command: Any, provider_id: str) -> dict[str, Any]:
    """Validate the shared Action envelope before provider-native translation."""

    if not isinstance(command, dict):
        raise TypeError(f"{provider_id} semantic command must be an object")
    reject_raw_request(command, provider_id)
    if command.get("schemaVersion") != 1:
        raise ValueError(f"{provider_id} semantic command schema is unsupported")
    if not str(command.get("model") or "").strip():
        raise ValueError(f"{provider_id} semantic command requires a model")
    if not isinstance(command.get("messages"), list):
        raise TypeError(f"{provider_id} semantic command messages must be an array")
    for field in ("generation", "reasoning", "providerOptions", "controls"):
        if not isinstance(command.get(field), dict):
            raise TypeError(f"{provider_id} semantic command {field} must be an object")
    if not isinstance(command.get("tools"), list):
        raise TypeError(f"{provider_id} semantic command tools must be an array")
    return command


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
    inference_modes: tuple[str, ...] = (CAMOUFOX_BROWSER_RUNTIME,)
    inference_actions: tuple[str, ...] = ()
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
        if not self.inference_modes or any(
            mode not in _INFERENCE_MODES for mode in self.inference_modes
        ):
            raise ValueError(f"provider {self.id} declares an unsupported inference mode")
        if self.inference_transport and self.inference_runtime not in self.inference_modes:
            raise ValueError(f"provider {self.id} runtime is not included in inference modes")
        declared_actions = tuple(str(action).strip().lower() for action in self.inference_actions)
        if len(set(declared_actions)) != len(declared_actions):
            raise ValueError(f"provider {self.id} declares duplicate inference actions")
        unsupported_actions = set(declared_actions) - _INFERENCE_ACTIONS
        if unsupported_actions:
            names = ", ".join(sorted(unsupported_actions))
            raise ValueError(f"provider {self.id} declares unsupported inference actions: {names}")
        if not self.inference_transport and declared_actions:
            raise ValueError(
                f"provider {self.id} cannot declare inference actions without inference transport"
            )
        if self.inference_transport and not declared_actions:
            raise ValueError(f"inference provider {self.id} must declare inference actions")
        if self.inference_transport and "chat" not in declared_actions:
            raise ValueError(f"inference provider {self.id} must declare the chat action")


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

    def action_bindings(self):
        """Return explicit business-action bindings for the provider channels.

        Existing providers get a compatibility Runtime binding until their concrete
        channel implementation is migrated. Providers with an API implementation
        override this method and register it separately.
        """
        from .actions import default_action_bindings

        return default_action_bindings(self)

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
