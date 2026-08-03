import asyncio
import logging
import time
from typing import Any, Literal

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from .observability import OperationFailure, bind_operation, failure_details
from .providers import provider_registry
from .resources import lanes
from .security import require_internal_token


class OperationContext(BaseModel):
    correlation_id: str = Field(min_length=8, max_length=100)
    aggregate_type: str = Field(min_length=1, max_length=32)
    aggregate_id: str = Field(min_length=1, max_length=255)
    attempt: int = Field(ge=1, le=10000)


class ProviderOperationRequest(BaseModel):
    operation: Literal["register", "reauthenticate", "keepalive"]
    payload: dict[str, Any] = Field(default_factory=dict)
    context: OperationContext


class ProviderTransportRequest(BaseModel):
    method: Literal["GET", "POST"]
    path: str = Field(min_length=1, max_length=1024)
    body: str = Field(default="", max_length=2 * 1024 * 1024)
    payload: dict[str, Any] = Field(default_factory=dict)


router = APIRouter(
    prefix="/internal/v1/providers",
    dependencies=[Depends(require_internal_token)],
)
logger = logging.getLogger("any2api_automation.provider_api")


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
        operation = getattr(provider, request.operation)
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
            async with lanes.batch:
                result = await operation(request.payload)
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
    provider = _transport_provider(provider_id)
    payload = {
        **request.payload,
        "method": request.method,
        "path": request.path,
        "body": request.body,
    }
    try:
        return await asyncio.to_thread(provider.transport_request, payload)
    except (TypeError, ValueError) as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(
            status_code=502,
            detail=f"provider transport failed ({type(exc).__name__})",
        ) from exc


@router.post("/{provider_id}/transport/stream")
async def transport_stream(
    provider_id: str, request: ProviderTransportRequest
) -> StreamingResponse:
    provider = _transport_provider(provider_id)
    payload = {
        **request.payload,
        "method": request.method,
        "path": request.path,
        "body": request.body,
    }
    try:
        stream = provider.transport_stream(payload)
    except (TypeError, ValueError) as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return StreamingResponse(stream, media_type="application/x-ndjson")


def _transport_provider(provider_id: str):
    try:
        provider = provider_registry.require(provider_id)
    except (TypeError, ValueError) as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    if not provider.manifest.inference_transport:
        raise HTTPException(status_code=501, detail="provider transport is not implemented")
    return provider
