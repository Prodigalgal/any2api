from __future__ import annotations

import re
from typing import Any


def session_token(credential: dict[str, Any]) -> str:
    for name in ("sso", "sso-rw", "sso_rw", "sso_token"):
        value = str(credential.get(name) or "").strip()
        if value:
            return re.sub(r"^sso(?:-rw)?\s*=\s*", "", value, flags=re.IGNORECASE)
    raise ValueError("credential requires an SSO token")


def session_cookies(credential: dict[str, Any]) -> dict[str, str]:
    token = session_token(credential)
    return {"sso": token, "sso-rw": token}


def probe_result(status: int, completed: bool) -> dict[str, Any]:
    if 200 <= status < 300 and completed:
        return {"healthy": True, "auth_expired": False, "credential_patch": None}
    if status == 401:
        return {
            "healthy": False,
            "auth_expired": True,
            "terminal": True,
            "error_class": "SsoExpired",
            "credential_patch": None,
        }
    error_class = {
        403: "PermissionOrEgressDenied",
        429: "UpstreamRateLimited",
    }.get(status, "ChannelProbeFailed")
    return {
        "healthy": False,
        "auth_expired": False,
        "terminal": False,
        "error_class": error_class,
        "credential_patch": None,
    }
