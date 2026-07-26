from typing import Literal

from pydantic import BaseModel, Field


class SolverEstimate(BaseModel):
    solver: str
    value: str | float | list[tuple[float, float]]
    confidence: float = Field(ge=0, le=1)
    detail: str = ""


class Consensus(BaseModel):
    accepted: bool
    spread: float | None = None
    votes: int


class SolveResult(BaseModel):
    ok: bool
    request_id: str
    challenge_type: str
    kind: Literal["text", "offset", "points"]
    value: str | float | list[tuple[float, float]] | None
    confidence: float
    consensus: Consensus
    estimates: list[SolverEstimate]
    duration_ms: int
    error: str | None = None
