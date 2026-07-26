import time
from typing import Annotated

from fastapi import APIRouter, Depends, File, Form, UploadFile

from ..security import require_internal_token
from .fusion import fuse_offsets, fuse_text
from .models import SolveResult
from .registry import registry

router = APIRouter(
    prefix="/internal/v1/captcha",
    tags=["captcha"],
    dependencies=[Depends(require_internal_token)],
)


@router.get("/capabilities")
async def capabilities() -> dict[str, object]:
    return {"ok": True, "solvers": registry.capabilities()}


@router.post("/solve", response_model=SolveResult)
async def solve(
    request_id: Annotated[str, Form()],
    provider: Annotated[str, Form()],
    challenge_type: Annotated[str, Form()],
    background: Annotated[UploadFile, File()],
    piece: Annotated[UploadFile | None, File()] = None,
) -> SolveResult:
    started = time.monotonic()
    background_bytes = await background.read()
    if challenge_type == "ocr":
        estimates = await registry.solve_text(background_bytes)
        value, confidence, consensus = fuse_text(estimates)
        kind = "text"
    elif challenge_type == "slider" and piece is not None:
        estimates = await registry.solve_slider(background_bytes, await piece.read())
        value, confidence, consensus = fuse_offsets(estimates)
        kind = "offset"
    else:
        return SolveResult(
            ok=False,
            request_id=request_id,
            challenge_type=challenge_type,
            kind="points",
            value=None,
            confidence=0,
            consensus={"accepted": False, "votes": 0},
            estimates=[],
            duration_ms=int((time.monotonic() - started) * 1000),
            error=f"unsupported or incomplete challenge for {provider}",
        )
    return SolveResult(
        ok=bool(consensus.accepted and value is not None),
        request_id=request_id,
        challenge_type=challenge_type,
        kind=kind,
        value=value,
        confidence=confidence,
        consensus=consensus,
        estimates=estimates,
        duration_ms=int((time.monotonic() - started) * 1000),
        error=None if consensus.accepted else "insufficient_consensus",
    )
