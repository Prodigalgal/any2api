from __future__ import annotations

import json
import re
from dataclasses import dataclass
from typing import Any

_SOURCE = re.compile(r'botFlagSource"\s*:\s*(null|"?-?\d+"?)')
_DETAILS = re.compile(r'botFlagDetails"\s*:\s*(null|"(?:\\.|[^"\\])*")')
_DETAIL_FIELD = re.compile(r"(?:^|,)\s*([A-Za-z_][A-Za-z0-9_-]*)=([^,]*)")


@dataclass(frozen=True)
class GrokRegistrationRisk:
    status: str
    bot_flag_source: int | None = None
    policy: str = ""
    risk_score: float | None = None
    event: str = ""

    @property
    def denied(self) -> bool:
        return self.status == "denied"

    def metadata(self) -> dict[str, Any]:
        return {
            "registration_risk_status": self.status,
            "bot_flag_source": self.bot_flag_source,
            "registration_risk_policy": self.policy or None,
            "registration_risk_score": self.risk_score,
            "registration_risk_event": self.event or None,
        }


def parse_registration_risk(page_html: str) -> GrokRegistrationRisk:
    """Parse the allowlisted registration-risk fields embedded in Grok RSC data."""
    normalized = str(page_html or "").replace('\\"', '"').replace("\\u0024", "$")
    source_match = _SOURCE.search(normalized)
    details_match = _DETAILS.search(normalized)

    source: int | None = None
    if source_match and source_match.group(1) != "null":
        try:
            source = int(source_match.group(1).strip('"'))
        except ValueError:
            source = None

    details = ""
    if details_match and details_match.group(1) != "null":
        token = details_match.group(1)
        try:
            details = str(json.loads(token))
        except (TypeError, ValueError, json.JSONDecodeError):
            details = token.strip('"')
    fields = {
        match.group(1).lower(): match.group(2).strip()
        for match in _DETAIL_FIELD.finditer(details)
    }
    policy = fields.get("policy", "").lower()
    event = fields.get("event", "")
    risk_score: float | None = None
    try:
        if fields.get("risk"):
            risk_score = float(fields["risk"])
    except ValueError:
        pass

    if policy == "deny" and event == "$registration":
        status = "denied"
    elif source == 0 and policy != "deny":
        status = "clean"
    elif source is not None or policy:
        status = "flagged"
    else:
        status = "unknown"
    return GrokRegistrationRisk(status, source, policy, risk_score, event)


def inspect_registration_risk_page(
    page: Any,
    *,
    timeout_ms: int = 30_000,
) -> GrokRegistrationRisk:
    """Read risk state in the exact browser, proxy, and SSO session that registered."""
    try:
        response = page.goto(
            "https://grok.com/",
            wait_until="domcontentloaded",
            timeout=max(10_000, min(90_000, int(timeout_ms))),
        )
        if response is not None and int(response.status) != 200:
            return GrokRegistrationRisk("unknown")
        return parse_registration_risk(page.content())
    except Exception:  # noqa: BLE001 - an unavailable diagnostic must not discard SSO
        return GrokRegistrationRisk("unknown")
