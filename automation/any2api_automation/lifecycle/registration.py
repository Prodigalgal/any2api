from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from enum import StrEnum

logger = logging.getLogger(__name__)

_DATA_IMAGE = re.compile(r"data:image/[^;\s]+;base64,[A-Za-z0-9+/=]+")
_EMAIL = re.compile(r"(?<![\w.+-])[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}(?![\w.-])")
_URL_WITH_QUERY = re.compile(r"https?://[^\s?#]+\?[^\s#]+")
_SECRET_FIELD = re.compile(
    r"(?i)(?P<key>password|token|authorization|cookie|jwt)"
    r"(?P<separator>\s*[:=]\s*)"
    r"(?P<quote>['\"]?)[^\s,}'\"]+(?P=quote)"
)


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
        detail = _sanitize_failure_detail(error)
        logger.warning(
            "provider registration failed provider=%s stage=%s error_type=%s detail=%s",
            self.provider_id,
            self.current,
            type(error).__name__,
            detail,
        )
        return RuntimeError(
            f"provider registration failed at stage={self.current} "
            f"({type(error).__name__}: {detail})"
        )


def _sanitize_failure_detail(error: Exception) -> str:
    detail = _DATA_IMAGE.sub("<embedded-image>", str(error))
    detail = _EMAIL.sub("<email>", detail)
    detail = _URL_WITH_QUERY.sub("<url-with-query>", detail)
    detail = _SECRET_FIELD.sub(
        lambda match: f"{match.group('key')}{match.group('separator')}<redacted>",
        detail,
    )
    return " ".join(detail.split())[:1200]
