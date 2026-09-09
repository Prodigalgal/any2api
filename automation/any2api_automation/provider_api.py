import logging
import time
from typing import Any, Literal

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from .captcha.policy import CaptchaAiPolicy, bind_captcha_policy
from .observability import OperationFailure, bind_operation, failure_details
from .providers import provider_registry
from .providers.actions import ProviderAction, ProviderActionRequest
from .providers.base import CAMOUFOX_BROWSER_RUNTIME
from .providers.channels import ActionNotSupported
from .resources import lanes
from .security import require_internal_token


class OperationContext(BaseModel):
    correlation_id: str = Field(min_length=8, max_length=100)
    aggregate_type: str = Field(min_length=1, max_length=32)
    aggregate_id: str = Field(min_length=1, max_length=255)
    attempt: int = Field(ge=1, le=10000)


class ProviderOperationRequest(BaseModel):
    operation: Literal["register", "reauthenticate", "keepalive", "daily_checkin"]
    payload: dict[str, Any] = Field(default_factory=dict)
    context: OperationContext


class ProviderTransportRequest(BaseModel):
    runtime_mode: Literal["api", "camoufox_browser_runtime"] = CAMOUFOX_BROWSER_RUNTIME
    operation: str | None = Field(default=None, min_length=1, max_length=64)
    semantic_command: dict[str, Any] = Field(default_factory=dict)
    runtime_plan: dict[str, Any] = Field(default_factory=dict)
    method: Literal["GET", "POST"] | None = None
    path: str = Field(default="", max_length=1024)
    body: str = Field(default="", max_length=2 * 1024 * 1024)
    payload: dict[str, Any] = Field(default_factory=dict)


class ProviderActionEnvelope(BaseModel):
    """统一 Action 契约；旧 transport 请求由下方兼容路由转换到这里。"""

    action: str = Field(min_length=1, max_length=64)
    channel: Literal["api", "runtime", "camoufox_browser_runtime"] = CAMOUFOX_BROWSER_RUNTIME
    operation: str | None = Field(default=None, min_length=1, max_length=64)
    semantic_command: dict[str, Any] = Field(default_factory=dict)
    runtime_plan: dict[str, Any] = Field(default_factory=dict)
    method: Literal["GET", "POST"] | None = None
    path: str = Field(default="", max_length=1024)
    body: str = Field(default="", max_length=2 * 1024 * 1024)
    payload: dict[str, Any] = Field(default_factory=dict)


router = APIRouter(
    prefix="/internal/v1/providers",
    dependencies=[Depends(require_internal_token)],
)
logger = logging.getLogger("any2api_automation.provider_api")
action_dispatcher = provider_registry.action_dispatcher()


@router.post("/{provider_id}/execute")
async def execute(provider_id: str, request: ProviderOperationRequest) -> dict[str, Any]:
    started = time.monotonic()
    correlation = request.context.correlation_id
    with bind_operation(correlation, provider_id, request.operation):
        try:
            provider = provider_registry.require(provider_id)
        except (TypeError, ValueError) as exc:
            error = OperationFailure(
                code="provider_not_installed",
                stage="dispatch",
                message="automation provider is not installed",
                error_type=type(exc).__name__,
                retryable=False,
            )
            raise _http_failure(
                error, correlation, provider_id, request.operation, 404, started
            ) from exc
        if request.operation not in provider.manifest.operations:
            error = OperationFailure(
                code="operation_unsupported",
                stage="dispatch",
                message="automation provider does not implement this operation",
                error_type="NotImplementedError",
                retryable=False,
            )
            raise _http_failure(error, correlation, provider_id, request.operation, 501, started)
        logger.info(
            "automation_operation_started correlation_id=%s provider=%s operation=%s "
            "aggregate_type=%s aggregate_id=%s attempt=%s",
            correlation,
            provider_id,
            request.operation,
            request.context.aggregate_type,
            request.context.aggregate_id,
            request.context.attempt,
        )
        try:
            captcha_policy = CaptchaAiPolicy.from_payload(request.payload)
            with bind_captcha_policy(captcha_policy):
                async with lanes.batch:
                    result = await action_dispatcher.execute(
                        ProviderActionRequest(
                            provider_id=provider_id,
                            action=ProviderAction.from_legacy_operation(request.operation),
                            channel=CAMOUFOX_BROWSER_RUNTIME,
                            operation=request.operation,
                            payload=request.payload,
                        )
                    )
            duration_ms = round((time.monotonic() - started) * 1000)
            logger.info(
                "automation_operation_finished correlation_id=%s provider=%s operation=%s "
                "status=SUCCEEDED duration_ms=%s",
                correlation,
                provider_id,
                request.operation,
                duration_ms,
            )
            return {
                "ok": True,
                "provider": provider_id,
                "operation": request.operation,
                "observability": {
                    "correlation_id": correlation,
                    "stage": "completed",
                    "duration_ms": duration_ms,
                },
                **result,
            }
        except (TypeError, ValueError) as exc:
            raise _http_failure(exc, correlation, provider_id, request.operation, 400, started)
        except ActionNotSupported as exc:
            raise _http_failure(exc, correlation, provider_id, request.operation, 501, started)
        except NotImplementedError as exc:
            raise _http_failure(exc, correlation, provider_id, request.operation, 501, started)
        except Exception as exc:  # noqa: BLE001 - normalize failures at the provider boundary
            raise _http_failure(exc, correlation, provider_id, request.operation, 502, started)


def _http_failure(
    error: Exception,
    correlation: str,
    provider_id: str,
    operation: str,
    status_code: int,
    started: float,
) -> HTTPException:
    failure = failure_details(error)
    duration_ms = round((time.monotonic() - started) * 1000)
    logger.warning(
        "automation_operation_finished correlation_id=%s provider=%s operation=%s "
        "status=FAILED stage=%s error_code=%s error_type=%s duration_ms=%s",
        correlation,
        provider_id,
        operation,
        failure.stage,
        failure.code,
        failure.error_type,
        duration_ms,
    )
    return HTTPException(
        status_code=status_code,
        detail={
            "error": {
                "code": failure.code,
                "stage": failure.stage,
                "message": failure.message,
                "error_type": failure.error_type,
                "retryable": failure.retryable,
                "correlation_id": correlation,
            }
        },
    )


@router.post("/{provider_id}/transport/request")
async def transport_request(provider_id: str, request: ProviderTransportRequest) -> dict[str, Any]:
    return await _execute_action(
        _legacy_action_request(provider_id, request, stream=False),
    )


@router.post("/{provider_id}/transport/stream")
async def transport_stream(
    provider_id: str, request: ProviderTransportRequest
) -> StreamingResponse:
    return await _stream_action(_legacy_action_request(provider_id, request, stream=True))


@router.post("/{provider_id}/actions/request")
async def action_request(provider_id: str, request: ProviderActionEnvelope) -> dict[str, Any]:
    return await _execute_action(_action_request(provider_id, request, stream=False))


@router.post("/{provider_id}/actions/stream")
async def action_stream(provider_id: str, request: ProviderActionEnvelope) -> StreamingResponse:
    return await _stream_action(_action_request(provider_id, request, stream=True))


def _legacy_action_request(
    provider_id: str, request: ProviderTransportRequest, *, stream: bool
) -> ProviderActionRequest:
    return ProviderActionRequest.from_legacy(
        provider_id,
        request.runtime_mode,
        request.operation,
        payload=request.payload,
        semantic_command=request.semantic_command,
        runtime_plan=request.runtime_plan,
        method=request.method,
        path=request.path,
        body=request.body,
        stream=stream,
    )


def _action_request(
    provider_id: str, request: ProviderActionEnvelope, *, stream: bool
) -> ProviderActionRequest:
    try:
        action = ProviderAction.parse(request.action)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return ProviderActionRequest(
        provider_id=provider_id,
        action=action,
        channel=request.channel,
        operation=request.operation,
        payload=request.payload,
        semantic_command=request.semantic_command,
        runtime_plan=request.runtime_plan,
        method=request.method,
        path=request.path,
        body=request.body,
        stream=stream,
    )


async def _execute_action(request: ProviderActionRequest) -> dict[str, Any]:
    try:
        _require_action_provider(request)
        return await action_dispatcher.execute(request)
    except LookupError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except (TypeError, ValueError) as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except ActionNotSupported as exc:
        raise HTTPException(status_code=501, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(
            status_code=502,
            detail=f"provider action failed ({type(exc).__name__})",
        ) from exc


async def _stream_action(request: ProviderActionRequest) -> StreamingResponse:
    try:
        _require_action_provider(request)
        stream = await action_dispatcher.stream(request)
    except LookupError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except (TypeError, ValueError) as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except ActionNotSupported as exc:
        raise HTTPException(status_code=501, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(
            status_code=502,
            detail=f"provider action failed ({type(exc).__name__})",
        ) from exc
    return StreamingResponse(stream, media_type="application/x-ndjson")


def _require_action_provider(request: ProviderActionRequest) -> None:
    try:
        provider_registry.require(request.provider_id)
    except (TypeError, ValueError) as exc:
        raise LookupError(str(exc)) from exc
