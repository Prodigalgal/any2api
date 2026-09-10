from __future__ import annotations

import inspect
from collections.abc import AsyncIterator, Awaitable, Callable, Iterator, Mapping
from dataclasses import dataclass, field
from enum import StrEnum
from typing import TYPE_CHECKING, Any

from .base import API_TRANSPORT, CAMOUFOX_BROWSER_RUNTIME

if TYPE_CHECKING:
    from .base import AutomationProvider


class ProviderAction(StrEnum):
    """业务动作名；动作不包含 URL、签名、浏览器或 HTTP 细节。"""

    REGISTER = "register"
    REAUTHENTICATE = "reauthenticate"
    KEEPALIVE = "keepalive"
    DAILY_CHECKIN = "daily_checkin"
    MODEL_DISCOVERY = "model_discovery"
    CHAT = "chat"
    PROVIDER_QUERY = "provider_query"
    MEDIA_POLICY = "media_policy"
    MEDIA_CALLBACK = "media_callback"
    RAW_REQUEST = "raw_request"

    @classmethod
    def parse(cls, value: str) -> ProviderAction:
        normalized = str(value or "").strip().lower()
        try:
            return cls(normalized)
        except ValueError as error:
            raise ValueError(f"unsupported provider action: {value}") from error

    @classmethod
    def from_legacy_operation(cls, operation: str | None) -> ProviderAction:
        normalized = str(operation or "").strip().lower()
        return {
            "register": cls.REGISTER,
            "reauthenticate": cls.REAUTHENTICATE,
            "keepalive": cls.KEEPALIVE,
            "daily_checkin": cls.DAILY_CHECKIN,
            "models": cls.MODEL_DISCOVERY,
            "chat": cls.CHAT,
            "agents": cls.PROVIDER_QUERY,
            "files_policy": cls.MEDIA_POLICY,
            "files_callback": cls.MEDIA_CALLBACK,
        }.get(normalized, cls.RAW_REQUEST)

    @property
    def default_legacy_operation(self) -> str | None:
        return {
            ProviderAction.MODEL_DISCOVERY: "models",
            ProviderAction.CHAT: "chat",
            ProviderAction.PROVIDER_QUERY: "agents",
            ProviderAction.MEDIA_POLICY: "files_policy",
            ProviderAction.MEDIA_CALLBACK: "files_callback",
        }.get(self)


def normalize_channel(value: str) -> str:
    normalized = str(value or "").strip().lower()
    if normalized == "runtime":
        normalized = CAMOUFOX_BROWSER_RUNTIME
    if normalized not in {API_TRANSPORT, CAMOUFOX_BROWSER_RUNTIME}:
        raise ValueError(f"unsupported provider action channel: {value}")
    return normalized


@dataclass(frozen=True)
class ProviderActionRequest:
    provider_id: str
    action: ProviderAction
    channel: str
    payload: Mapping[str, Any] = field(default_factory=dict)
    operation: str | None = None
    semantic_command: Mapping[str, Any] = field(default_factory=dict)
    runtime_plan: Mapping[str, Any] = field(default_factory=dict)
    method: str | None = None
    path: str = ""
    body: str = ""
    stream: bool = False

    def __post_init__(self) -> None:
        action = (
            self.action
            if isinstance(self.action, ProviderAction)
            else ProviderAction.parse(self.action)
        )
        object.__setattr__(self, "action", action)
        object.__setattr__(self, "channel", normalize_channel(self.channel))
        object.__setattr__(self, "payload", dict(self.payload))
        object.__setattr__(self, "semantic_command", dict(self.semantic_command))
        object.__setattr__(self, "runtime_plan", dict(self.runtime_plan))

    @classmethod
    def from_legacy(
        cls,
        provider_id: str,
        channel: str,
        operation: str | None,
        *,
        payload: Mapping[str, Any] | None = None,
        semantic_command: Mapping[str, Any] | None = None,
        runtime_plan: Mapping[str, Any] | None = None,
        method: str | None = None,
        path: str = "",
        body: str = "",
        stream: bool = False,
    ) -> ProviderActionRequest:
        return cls(
            provider_id=provider_id,
            action=ProviderAction.from_legacy_operation(operation),
            channel=channel,
            operation=operation,
            payload=payload or {},
            semantic_command=semantic_command or {},
            runtime_plan=runtime_plan or {},
            method=method,
            path=path,
            body=body,
            stream=stream,
        )

    def legacy_payload(self, default_operation: str | None = None) -> dict[str, Any]:
        """将统一动作契约适配为旧 Provider 方法的输入。"""

        operation = self.operation or default_operation or self.action.default_legacy_operation
        payload = dict(self.payload)
        payload.update(
            {
                "action": self.action.value,
                "runtime_mode": self.channel,
                "operation": operation,
                "semantic_command": dict(self.semantic_command),
                "runtime_plan": dict(self.runtime_plan),
            }
        )
        if self.method is not None:
            payload.update(method=self.method, path=self.path, body=self.body)
        return payload


ActionResult = dict[str, Any]
ActionExecutor = Callable[[ProviderActionRequest], ActionResult | Awaitable[ActionResult]]
ActionStreamer = Callable[
    [ProviderActionRequest],
    AsyncIterator[bytes] | Iterator[bytes] | Awaitable[AsyncIterator[bytes] | Iterator[bytes]],
]


@dataclass(frozen=True)
class ActionBinding:
    action: ProviderAction
    channel: str
    execute: ActionExecutor | None = None
    stream: ActionStreamer | None = None
    legacy_operation: str | None = None

    def __post_init__(self) -> None:
        action = (
            self.action
            if isinstance(self.action, ProviderAction)
            else ProviderAction.parse(self.action)
        )
        object.__setattr__(self, "action", action)
        object.__setattr__(self, "channel", normalize_channel(self.channel))
        if self.execute is None and self.stream is None:
            raise ValueError(f"action binding has no executor: {action.value}")


async def _await_result(value: ActionResult | Awaitable[ActionResult]) -> ActionResult:
    result = await value if inspect.isawaitable(value) else value
    if not isinstance(result, dict):
        raise TypeError("provider action must return an object")
    return result


def lifecycle_action_bindings(provider: AutomationProvider) -> tuple[ActionBinding, ...]:
    methods = {
        "register": ProviderAction.REGISTER,
        "reauthenticate": ProviderAction.REAUTHENTICATE,
        "keepalive": ProviderAction.KEEPALIVE,
        "daily_checkin": ProviderAction.DAILY_CHECKIN,
    }
    bindings: list[ActionBinding] = []
    for operation in provider.manifest.operations:
        action = methods.get(operation)
        if action is None:
            continue

        async def execute(
            request: ProviderActionRequest,
            method_name: str = operation,
        ) -> ActionResult:
            method = getattr(provider, method_name)
            return await _await_result(method(dict(request.payload)))

        bindings.append(
            ActionBinding(
                action=action,
                channel=CAMOUFOX_BROWSER_RUNTIME,
                execute=execute,
                legacy_operation=operation,
            )
        )
    return tuple(bindings)


def legacy_inference_action_bindings(provider: AutomationProvider) -> tuple[ActionBinding, ...]:
    if not getattr(provider.manifest, "inference_transport", False):
        return ()

    async def request(
        action_request: ProviderActionRequest,
        default_operation: str | None = None,
    ) -> ActionResult:
        return await _await_result(
            provider.transport_request(action_request.legacy_payload(default_operation))
        )

    def stream(
        action_request: ProviderActionRequest,
        default_operation: str | None = None,
    ) -> AsyncIterator[bytes] | Iterator[bytes] | Awaitable[AsyncIterator[bytes] | Iterator[bytes]]:
        return provider.transport_stream(action_request.legacy_payload(default_operation))

    async def discover(action_request: ProviderActionRequest) -> ActionResult:
        return await request(action_request, "models")

    async def raw_request(action_request: ProviderActionRequest) -> ActionResult:
        return await request(action_request)

    def chat_stream(action_request: ProviderActionRequest):
        return stream(action_request, "chat")

    bindings: list[ActionBinding] = []
    operation_defaults = {
        ProviderAction.PROVIDER_QUERY: "agents",
        ProviderAction.MEDIA_POLICY: "files_policy",
        ProviderAction.MEDIA_CALLBACK: "files_callback",
    }
    for declared_action in provider.manifest.inference_actions:
        action = ProviderAction.parse(declared_action)
        if action is ProviderAction.MODEL_DISCOVERY:
            bindings.append(
                ActionBinding(
                    action=action,
                    channel=CAMOUFOX_BROWSER_RUNTIME,
                    execute=discover,
                    legacy_operation="models",
                )
            )
        elif action is ProviderAction.CHAT:
            bindings.append(
                ActionBinding(
                    action=action,
                    channel=CAMOUFOX_BROWSER_RUNTIME,
                    stream=chat_stream,
                    legacy_operation="chat",
                )
            )
        elif action in operation_defaults:
            default_operation = operation_defaults[action]

            async def execute_operation(
                action_request: ProviderActionRequest,
                operation: str = default_operation,
            ) -> ActionResult:
                return await request(action_request, operation)

            bindings.append(
                ActionBinding(
                    action=action,
                    channel=CAMOUFOX_BROWSER_RUNTIME,
                    execute=execute_operation,
                    legacy_operation=default_operation,
                )
            )
        elif action is ProviderAction.RAW_REQUEST:
            bindings.append(
                ActionBinding(
                    action=action,
                    channel=CAMOUFOX_BROWSER_RUNTIME,
                    execute=raw_request,
                )
            )
    return tuple(bindings)


def default_action_bindings(provider: AutomationProvider) -> tuple[ActionBinding, ...]:
    return lifecycle_action_bindings(provider) + legacy_inference_action_bindings(provider)
