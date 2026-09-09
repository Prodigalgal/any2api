from __future__ import annotations

import json
import math
from collections.abc import Callable
from contextlib import AbstractAsyncContextManager
from datetime import UTC, datetime
from typing import Any

from ..lifecycle.account import credential
from .base import DailyCheckinStrategy
from .minmax_browser import MinmaxOfficialBrowserTransport
from .runtime_rules import RuntimePlan, parse_runtime_plan

_MINMAX_SIGNIN_STATUS_PATH = "/minimax-cloud/api/v1/signin/status"
_MINMAX_SIGNIN_CLAIM_PATH = "/minimax-cloud/api/v1/signin/claim"
_SIGNIN_CLAIMABLE = 2
_SIGNIN_CLAIMED = 3
_SIGNIN_CLAIM_RESULT_CLAIMED = 1
_SIGNIN_CLAIM_RESULT_ALREADY_CLAIMED = 2


class MinmaxDailyCheckin(DailyCheckinStrategy):
    """MiniMax Web check-in protocol behind the shared daily-checkin contract."""

    def __init__(
        self,
        transport_factory: Callable[[], MinmaxOfficialBrowserTransport],
        proxy_lease_factory: Callable[[dict[str, Any]], AbstractAsyncContextManager[str]],
    ) -> None:
        self._transport_factory = transport_factory
        self._proxy_lease_factory = proxy_lease_factory

    async def execute(self, payload: dict[str, Any]) -> dict[str, Any]:
        current = credential(payload)
        plan = parse_runtime_plan(payload.get("runtime_plan"), "minmax")
        transport = self._transport_factory()
        async with self._proxy_lease_factory(payload) as proxy_url:
            status_response = await transport.request(
                current,
                "GET",
                _runtime_path(plan, "signinStatus", _MINMAX_SIGNIN_STATUS_PATH),
                "",
                proxy_url,
            )
            status = int(status_response.get("status") or 502)
            status_patch = status_response.get("credential_patch")
            if status in {401, 403}:
                return _signin_failure(
                    "AUTH_EXPIRED", "minmax_daily_checkin_auth_expired", status_patch
                )
            if status < 200 or status >= 300:
                return _signin_failure(
                    "HTTP_ERROR", "minmax_daily_checkin_unavailable", status_patch
                )
            try:
                panel = _parse_signin_panel(str(status_response.get("body") or ""))
            except RuntimeError:
                return _signin_failure("REJECTED", "minmax_daily_checkin_rejected", status_patch)
            except (TypeError, ValueError):
                return _signin_failure(
                    "PROTOCOL_ERROR", "minmax_daily_checkin_protocol_changed", status_patch
                )

            today = next(day for day in panel["days"] if day["is_today"])
            if today["status"] == _SIGNIN_CLAIMED:
                return _signin_success("ALREADY_CLAIMED", today, status_patch)
            claimable = next(
                (day for day in panel["days"] if day["status"] == _SIGNIN_CLAIMABLE),
                None,
            )
            if claimable is None:
                return _signin_failure(
                    "UNAVAILABLE",
                    "minmax_daily_checkin_unavailable",
                    status_patch,
                    day=today,
                )

            claim_response = await transport.request(
                current,
                "POST",
                _runtime_path(plan, "signinClaim", _MINMAX_SIGNIN_CLAIM_PATH),
                "{}",
                proxy_url,
            )
            claim_status = int(claim_response.get("status") or 502)
            claim_patch = _merge_patches(status_patch, claim_response.get("credential_patch"))
            if claim_status in {401, 403}:
                return _signin_failure(
                    "AUTH_EXPIRED", "minmax_daily_checkin_auth_expired", claim_patch
                )
            if claim_status < 200 or claim_status >= 300:
                return _signin_failure(
                    "HTTP_ERROR", "minmax_daily_checkin_unavailable", claim_patch
                )
            try:
                claim = _parse_signin_claim(str(claim_response.get("body") or ""))
            except RuntimeError:
                return _signin_failure(
                    "REJECTED",
                    "minmax_daily_checkin_rejected",
                    claim_patch,
                    day=claimable,
                )
            except (TypeError, ValueError):
                return _signin_failure(
                    "PROTOCOL_ERROR",
                    "minmax_daily_checkin_protocol_changed",
                    claim_patch,
                    day=claimable,
                )
            if claim["claim_result"] not in {
                _SIGNIN_CLAIM_RESULT_CLAIMED,
                _SIGNIN_CLAIM_RESULT_ALREADY_CLAIMED,
            }:
                return _signin_failure(
                    "PROTOCOL_ERROR",
                    "minmax_daily_checkin_protocol_changed",
                    claim_patch,
                    day=claimable,
                )
            result_status = (
                "CLAIMED"
                if claim["claim_result"] == _SIGNIN_CLAIM_RESULT_CLAIMED
                else "ALREADY_CLAIMED"
            )
            return _signin_success(
                result_status,
                {
                    "day_no": claim["day_no"],
                    "points": claim["points"],
                    "is_today": True,
                    "status": _SIGNIN_CLAIMED,
                },
                claim_patch,
                claim_result=result_status,
            )


def _runtime_path(plan: RuntimePlan, key: str, fallback: str) -> str:
    return str(plan.active.rules.endpoint_paths.get(key) or fallback)


def _parse_signin_panel(body: str) -> dict[str, Any]:
    data = _parse_signin_data(body)
    scene = data.get("scene")
    days = data.get("days")
    if not isinstance(scene, int) or isinstance(scene, bool) or scene not in {0, 1, 2, 3, 4}:
        raise ValueError("MinMax daily check-in scene is invalid")
    if not isinstance(days, list) or len(days) != 7:
        raise ValueError("MinMax daily check-in days are invalid")
    seen: set[int] = set()
    normalized_days: list[dict[str, Any]] = []
    today_count = 0
    for day in days:
        if not isinstance(day, dict):
            raise TypeError("MinMax daily check-in day is invalid")
        day_no = day.get("day_no")
        points = day.get("points")
        status = day.get("status")
        is_today = day.get("is_today")
        if (
            not isinstance(day_no, int)
            or isinstance(day_no, bool)
            or not 1 <= day_no <= 7
            or day_no in seen
            or not isinstance(points, (int, float))
            or isinstance(points, bool)
            or not math.isfinite(float(points))
            or points < 0
            or not isinstance(is_today, bool)
            or not isinstance(status, int)
            or isinstance(status, bool)
            or status not in {1, 2, 3, 4}
        ):
            raise ValueError("MinMax daily check-in day fields are invalid")
        seen.add(day_no)
        today_count += int(is_today)
        normalized_days.append(
            {
                "day_no": day_no,
                "points": points,
                "is_today": is_today,
                "status": status,
            }
        )
    if seen != set(range(1, 8)) or today_count != 1:
        raise ValueError("MinMax daily check-in day set is invalid")
    return {"scene": scene, "days": normalized_days}


def _parse_signin_claim(body: str) -> dict[str, Any]:
    data = _parse_signin_data(body)
    claim_id = data.get("claim_id")
    claim_result = data.get("claim_result")
    if isinstance(claim_result, str) and claim_result.isdigit():
        claim_result = int(claim_result)
    day_no = data.get("day_no")
    points = data.get("points")
    expire_at_ms = data.get("expire_at_ms")
    if (
        not isinstance(claim_id, str)
        or not claim_id.strip()
        or not isinstance(claim_result, int)
        or isinstance(claim_result, bool)
        or not isinstance(day_no, int)
        or isinstance(day_no, bool)
        or not 1 <= day_no <= 7
        or not isinstance(points, (int, float))
        or isinstance(points, bool)
        or not math.isfinite(float(points))
        or points < 0
        or not isinstance(expire_at_ms, (int, float))
        or isinstance(expire_at_ms, bool)
        or not math.isfinite(float(expire_at_ms))
    ):
        raise ValueError("MinMax daily check-in claim fields are invalid")
    return {"claim_result": claim_result, "day_no": day_no, "points": points}


def _parse_signin_data(body: str) -> dict[str, Any]:
    try:
        value = json.loads(body)
    except json.JSONDecodeError as error:
        raise ValueError("MinMax daily check-in response is not JSON") from error
    if not isinstance(value, dict):
        raise TypeError("MinMax daily check-in response is invalid")
    base_resp = value.get("base_resp")
    if isinstance(base_resp, dict):
        code = base_resp.get("status_code")
        if code not in {None, 0, "0"}:
            raise RuntimeError("MinMax daily check-in response was rejected")
    data = value.get("data")
    if not isinstance(data, dict):
        raise TypeError("MinMax daily check-in response data is invalid")
    return data


def _signin_success(
    status: str,
    day: dict[str, Any],
    credential_patch: Any,
    *,
    claim_result: str | None = None,
) -> dict[str, Any]:
    metadata = {
        "minmax_daily_checkin": {
            "status": status,
            "day_no": day["day_no"],
            "points": day["points"],
            "checked_at": datetime.now(UTC).isoformat(),
        }
    }
    if claim_result is not None:
        metadata["minmax_daily_checkin"]["claim_result"] = claim_result
    result: dict[str, Any] = {
        "healthy": True,
        "auth_expired": False,
        "ready_for_inference": False,
        "metadata_patch": metadata,
    }
    if isinstance(credential_patch, dict) and credential_patch:
        result["credential_patch"] = credential_patch
    return result


def _signin_failure(
    status: str,
    error_class: str,
    credential_patch: Any,
    *,
    day: dict[str, Any] | None = None,
) -> dict[str, Any]:
    metadata: dict[str, Any] = {
        "status": status,
        "checked_at": datetime.now(UTC).isoformat(),
    }
    if day is not None:
        metadata.update(day_no=day["day_no"], points=day["points"])
    result: dict[str, Any] = {
        "healthy": False,
        "auth_expired": status == "AUTH_EXPIRED",
        "ready_for_inference": False,
        "error_class": error_class,
        "metadata_patch": {"minmax_daily_checkin": metadata},
    }
    if isinstance(credential_patch, dict) and credential_patch:
        result["credential_patch"] = credential_patch
    return result


def _merge_patches(first: Any, second: Any) -> dict[str, Any]:
    result: dict[str, Any] = {}
    if isinstance(first, dict):
        result.update(first)
    if isinstance(second, dict):
        result.update(second)
    return result
