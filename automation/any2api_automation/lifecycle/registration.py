from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from enum import StrEnum

logger = logging.getLogger(__name__)


class RegistrationStage(StrEnum):
    MAILBOX_CREATED = "mailbox_created"
    BROWSER_LAUNCHED = "browser_launched"
    FORM_READY = "form_ready"
    FORM_SUBMITTED = "form_submitted"
    CHALLENGE_CLEARED = "challenge_cleared"
    UPSTREAM_ACCEPTED = "upstream_accepted"
    OTP_UI_VISIBLE = "otp_ui_visible"
    OTP_RECEIVED = "otp_received"
    ACTIVATED = "activated"
    CREDENTIAL_CAPTURED = "credential_captured"


@dataclass
class RegistrationTrace:
    provider_id: str
    stages: list[RegistrationStage] = field(default_factory=list)

    @property
    def current(self) -> str:
        return self.stages[-1].value if self.stages else "started"

    def mark(self, stage: RegistrationStage) -> None:
        if not self.stages or self.stages[-1] != stage:
            self.stages.append(stage)
        logger.info(
            "provider registration stage provider=%s stage=%s",
            self.provider_id,
            stage.value,
        )

    def metadata(self) -> dict[str, list[str]]:
        return {"registration_stages": [stage.value for stage in self.stages]}

    def failure(self, error: Exception) -> RuntimeError:
        detail = re.sub(
            r"data:image/[^;\s]+;base64,[A-Za-z0-9+/=]+",
            "<embedded-image>",
            str(error),
        )
        detail = " ".join(detail.split())[:1200]
        return RuntimeError(
            f"provider registration failed at stage={self.current} "
            f"({type(error).__name__}: {detail})"
        )
