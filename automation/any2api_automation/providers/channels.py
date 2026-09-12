from __future__ import annotations

import inspect
from collections.abc import Iterable
from dataclasses import replace
from typing import TYPE_CHECKING, Any

from .actions import (
    ActionBinding,
    ProviderAction,
    ProviderActionRequest,
    default_action_bindings,
    normalize_channel,
)
from .base import API_TRANSPORT, CAMOUFOX_BROWSER_RUNTIME, validate_semantic_command

if TYPE_CHECKING:
    from .base import AutomationProvider


class ActionNotSupported(NotImplementedError):
    def __init__(self, provider_id: str, action: ProviderAction, channel: str) -> None:
        super().__init__(
            f"provider action is not implemented: provider={provider_id} "
            f"action={action.value} channel={channel}"
        )
        self.provider_id = provider_id
        self.action = action
        self.channel = channel


class ProviderActionChannel:
    """单个物理渠道的执行边界；渠道不感知 Provider 的业务规则。"""

    name: str

    async def execute(
        self, provider_id: str, binding: ActionBinding, request: ProviderActionRequest
    ) -> dict[str, Any]:
        if binding.execute is None:
            raise ActionNotSupported(provider_id, request.action, request.channel)
        value = binding.execute(_bind_legacy_operation(binding, request))
        result = await value if inspect.isawaitable(value) else value
        if not isinstance(result, dict):
            raise TypeError("provider action must return an object")
        return result

    async def stream(
        self, provider_id: str, binding: ActionBinding, request: ProviderActionRequest
    ):
        if binding.stream is None:
            raise ActionNotSupported(provider_id, request.action, request.channel)
        value = binding.stream(_bind_legacy_operation(binding, request))
        return await value if inspect.isawaitable(value) else value


class RuntimeChannel(ProviderActionChannel):
    name = CAMOUFOX_BROWSER_RUNTIME


class ApiChannel(ProviderActionChannel):
    name = API_TRANSPORT


def _bind_legacy_operation(
    binding: ActionBinding, request: ProviderActionRequest
) -> ProviderActionRequest:
    if request.operation:
        if (
            binding.legacy_operation
            and request.operation != binding.legacy_operation
            and request.action is not ProviderAction.RAW_REQUEST
        ):
            raise ValueError(
                f"provider action {request.action.value} does not match operation "
                f"{request.operation}"
            )
        return request
    if not binding.legacy_operation:
        return request
    return replace(request, operation=binding.legacy_operation)


class ActionBindingRegistry:
    def __init__(self, providers: Iterable[AutomationProvider]) -> None:
        self._bindings: dict[str, dict[tuple[str, ProviderAction], ActionBinding]] = {}
        self._provider_objects: dict[str, AutomationProvider] = {}
        for provider in providers:
            provider_id = provider.manifest.id
            self._provider_objects[provider_id] = provider
            self._bindings[provider_id] = self._index(provider)

    def _index(
        self, provider: AutomationProvider
    ) -> dict[tuple[str, ProviderAction], ActionBinding]:
        indexed: dict[tuple[str, ProviderAction], ActionBinding] = {}
        action_bindings = getattr(provider, "action_bindings", None)
        bindings = (
            action_bindings() if callable(action_bindings) else default_action_bindings(provider)
        )
        for binding in bindings:
            key = (binding.channel, binding.action)
            if key in indexed:
                raise ValueError(
                    f"duplicate provider action binding: provider={provider.manifest.id} "
                    f"channel={binding.channel} action={binding.action.value}"
                )
            indexed[key] = binding
        for operation in getattr(provider.manifest, "operations", ()):
            action = ProviderAction.from_legacy_operation(operation)
            if (CAMOUFOX_BROWSER_RUNTIME, action) not in indexed:
                raise ValueError(
                    f"provider operation has no Runtime action binding: "
                    f"provider={provider.manifest.id} operation={operation}"
                )
        if getattr(provider.manifest, "inference_transport", False):
            for declared_action in provider.manifest.inference_actions:
                action = ProviderAction.parse(declared_action)
                if (CAMOUFOX_BROWSER_RUNTIME, action) not in indexed:
                    raise ValueError(
                        f"inference provider has no Runtime action binding: "
                        f"provider={provider.manifest.id} action={action.value}"
                    )
            api_declared = API_TRANSPORT in provider.manifest.inference_modes
            for declared_action in provider.manifest.inference_actions:
                action = ProviderAction.parse(declared_action)
                api_binding = indexed.get((API_TRANSPORT, action))
                if api_declared and api_binding is None:
                    raise ValueError(
                        f"inference provider has no API action binding: "
                        f"provider={provider.manifest.id} action={action.value}"
                    )
                if not api_declared and api_binding is not None:
                    raise ValueError(
                        f"provider exposes an API action without declaring API mode: "
                        f"provider={provider.manifest.id} action={action.value}"
                    )
        return indexed

    def for_provider(
        self, provider: AutomationProvider
    ) -> dict[tuple[str, ProviderAction], ActionBinding]:
        provider_id = str(getattr(provider.manifest, "id", ""))
        cached = self._bindings.get(provider_id)
        if cached is not None and self._provider_objects.get(provider_id) is provider:
            return cached
        return self._index(provider)

    def resolve(
        self, provider: AutomationProvider, action: ProviderAction, channel: str
    ) -> ActionBinding:
        normalized_channel = normalize_channel(channel)
        binding = self.for_provider(provider).get((normalized_channel, action))
        if binding is None:
            raise ActionNotSupported(
                str(getattr(provider.manifest, "id", "unknown")), action, normalized_channel
            )
        return binding

    def describe(self, provider_id: str) -> list[dict[str, object]]:
        values = self._bindings.get(provider_id, {})
        return [
            {
                "action": action.value,
                "channel": channel,
                "stream": binding.stream is not None,
                "request": binding.execute is not None,
            }
            for (channel, action), binding in sorted(
                values.items(), key=lambda item: (item[0][0], item[0][1].value)
            )
        ]


class ProviderActionDispatcher:
    """统一动作调度器；只在这里选择 API 或 Runtime 渠道。"""

    def __init__(self, providers, bindings: ActionBindingRegistry) -> None:
        self._providers = providers
        self._bindings = bindings
        self._channels = {
            RuntimeChannel.name: RuntimeChannel(),
            ApiChannel.name: ApiChannel(),
        }

    async def execute(self, request: ProviderActionRequest) -> dict[str, Any]:
        provider = self._providers.require(request.provider_id)
        channel = self._channel(request.channel)
        self._validate_request(request)
        binding = self._bindings.resolve(provider, request.action, request.channel)
        return await channel.execute(request.provider_id, binding, request)

    async def stream(self, request: ProviderActionRequest):
        provider = self._providers.require(request.provider_id)
        channel = self._channel(request.channel)
        self._validate_request(request)
        binding = self._bindings.resolve(provider, request.action, request.channel)
        return await channel.stream(request.provider_id, binding, request)

    @staticmethod
    def _validate_request(request: ProviderActionRequest) -> None:
        if request.action is ProviderAction.CHAT:
            validate_semantic_command(dict(request.semantic_command), request.provider_id)

    def _channel(self, value: str) -> ProviderActionChannel:
        normalized = normalize_channel(value)
        try:
            return self._channels[normalized]
        except KeyError as error:
            raise ValueError(f"unsupported provider action channel: {value}") from error
