import asyncio
from typing import Any, Literal

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from .providers import provider_registry
from .resources import lanes
from .security import require_internal_token


class ProviderOperationRequest(BaseModel):
    operation: Literal["register", "reauthenticate", "keepalive"]
    payload: dict[str, Any] = Field(default_factory=dict)


class ProviderTransportRequest(BaseModel):
    method: Literal["GET", "POST"]
    path: str = Field(min_length=1, max_length=1024)
    body: str = Field(default="", max_length=2 * 1024 * 1024)
    payload: dict[str, Any] = Field(default_factory=dict)


router = APIRouter(
    prefix="/internal/v1/providers",
    dependencies=[Depends(require_internal_token)],
)


@router.post("/{provider_id}/execute")
async def execute(provider_id: str, request: ProviderOperationRequest) -> dict[str, Any]:
    try:
        provider = provider_registry.require(provider_id)
    except (TypeError, ValueError) as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    if request.operation not in provider.manifest.operations:
        raise HTTPException(
            status_code=501,
            detail=f"{request.operation} is not implemented for {provider_id}",
        )
    operation = getattr(provider, request.operation)
    try:
        async with lanes.batch:
            result = await operation(request.payload)
        return {"ok": True, "provider": provider_id, "operation": request.operation, **result}
    except (TypeError, ValueError) as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except NotImplementedError as exc:
        raise HTTPException(status_code=501, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(
            status_code=502,
            detail=f"provider operation failed ({type(exc).__name__})",
        ) from exc


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
