from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class ChallengeDetection:
    kind: str
    target: Any = None
    metadata: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class ChallengeAttemptResult:
    diagnostic: str = ""


@dataclass(frozen=True)
class ChallengeRunResult:
    present: bool
    solved: bool
    attempts: int
    diagnostic: str = ""


@dataclass(frozen=True)
class ChallengePolicy:
    max_attempts: int
    first_detection_timeout_ms: int
    retry_detection_timeout_ms: int = 5_000
    retry_settle_ms: int = 1_000


class ChallengeStrategy(ABC):
    @property
    @abstractmethod
    def strategy_id(self) -> str:
        raise NotImplementedError

    def attach(self, page: Any) -> None:
        del page

    @abstractmethod
    def detect(self, page: Any, timeout_ms: int) -> ChallengeDetection | None:
        raise NotImplementedError

    @abstractmethod
    def solve(
        self,
        page: Any,
        detection: ChallengeDetection,
        attempt: int,
    ) -> ChallengeAttemptResult:
        raise NotImplementedError

    @abstractmethod
    def verify(
        self,
        page: Any,
        detection: ChallengeDetection,
        attempt: ChallengeAttemptResult,
    ) -> bool:
        raise NotImplementedError

    def refresh(self, page: Any, detection: ChallengeDetection) -> None:
        del page, detection


class ChallengeRunner:
    def run(
        self,
        page: Any,
        strategy: ChallengeStrategy,
        policy: ChallengePolicy,
    ) -> ChallengeRunResult:
        if policy.max_attempts < 1:
            raise ValueError("challenge max_attempts must be positive")
        detection = strategy.detect(page, policy.first_detection_timeout_ms)
        if detection is None:
            return ChallengeRunResult(present=False, solved=False, attempts=0)

        last = ChallengeAttemptResult()
        for attempt_number in range(1, policy.max_attempts + 1):
            last = strategy.solve(page, detection, attempt_number)
            if strategy.verify(page, detection, last):
                return ChallengeRunResult(
                    present=True,
                    solved=True,
                    attempts=attempt_number,
                    diagnostic=last.diagnostic,
                )
            if attempt_number == policy.max_attempts:
                break
            strategy.refresh(page, detection)
            page.wait_for_timeout(policy.retry_settle_ms)
            detection = strategy.detect(page, policy.retry_detection_timeout_ms)
            if detection is None:
                raise RuntimeError(
                    f"{strategy.strategy_id} challenge state disappeared before verification"
                )

        return ChallengeRunResult(
            present=True,
            solved=False,
            attempts=policy.max_attempts,
            diagnostic=last.diagnostic,
        )
