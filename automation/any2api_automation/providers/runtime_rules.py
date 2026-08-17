from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from typing import Any

_PROVIDER_ID = re.compile(r"^[a-z][a-z0-9_-]{1,31}$")
_RULE_KEY = re.compile(r"^[A-Za-z][A-Za-z0-9]{0,63}$")
_BUILD_ID = re.compile(r"^[a-f0-9]{64}$")
_ALLOWED_RULE_FIELDS = {
    "schemaVersion",
    "sessionMaxAgeSeconds",
    "canaryTimeoutSeconds",
    "buildAssetMarkers",
    "discoveryMarkers",
    "capabilities",
    "endpointPaths",
}


class RuntimeRuleDiscoveryError(RuntimeError):
    """The declarative rule could not discover a compatible official build."""


@dataclass(frozen=True)
class RuntimeRule:
    schema_version: int
    session_max_age_seconds: int
    canary_timeout_seconds: int
    build_asset_markers: tuple[str, ...]
    discovery_markers: dict[str, tuple[str, ...]]
    capabilities: dict[str, str]
    endpoint_paths: dict[str, str]


@dataclass(frozen=True)
class RuntimeRuleSelection:
    provider_id: str
    revision: int
    rules: RuntimeRule


@dataclass(frozen=True)
class RuntimePlan:
    active: RuntimeRuleSelection
    candidate: RuntimeRuleSelection | None
    active_build_id: str
    candidate_build_id: str


def parse_runtime_plan(value: Any, provider_id: str) -> RuntimePlan:
    if not _PROVIDER_ID.fullmatch(provider_id):
        raise ValueError("runtime plan provider id is invalid")
    if not isinstance(value, dict):
        raise TypeError("runtime plan must be an object")
    active = _parse_selection(value.get("active"), provider_id, "active")
    candidate_value = value.get("candidate")
    candidate = (
        None
        if candidate_value is None
        else _parse_selection(candidate_value, provider_id, "candidate")
    )
    if candidate is not None and candidate.revision == active.revision:
        raise ValueError("runtime candidate revision must differ from active revision")
    return RuntimePlan(
        active=active,
        candidate=candidate,
        active_build_id=_parse_build_id(value.get("activeBuildId")),
        candidate_build_id=_parse_build_id(value.get("candidateBuildId")),
    )


def runtime_canary(
    selection: RuntimeRuleSelection,
    build_id: str,
    status: str,
    reason: str = "",
) -> dict[str, Any]:
    normalized_status = status.upper()
    if normalized_status not in {"PASSED", "FAILED"}:
        raise ValueError("runtime canary status is invalid")
    normalized_build = str(build_id or "").strip().lower()
    if normalized_build and not _BUILD_ID.fullmatch(normalized_build):
        raise ValueError("runtime build id must be a SHA-256 value")
    report: dict[str, Any] = {
        "revision": selection.revision,
        "build_id": normalized_build,
        "status": normalized_status,
    }
    if reason:
        report["reason"] = " ".join(str(reason).split())[:500]
    return report


def successful_canary(
    plan: RuntimePlan,
    selection: RuntimeRuleSelection,
    current_build_id: str,
) -> dict[str, Any] | None:
    is_candidate = (
        plan.candidate is not None
        and plan.candidate.revision == selection.revision
    )
    if not is_candidate and plan.active_build_id == current_build_id:
        return None
    return runtime_canary(selection, current_build_id, "PASSED")


def build_id(values: list[str] | tuple[str, ...]) -> str:
    normalized = sorted({str(value).strip() for value in values if str(value).strip()})
    if not normalized:
        raise RuntimeRuleDiscoveryError("official build assets were not found")
    return hashlib.sha256("\n".join(normalized).encode()).hexdigest()


def rule_digest(selection: RuntimeRuleSelection) -> str:
    payload = {
        "providerId": selection.provider_id,
        "revision": selection.revision,
        "rules": {
            "schemaVersion": selection.rules.schema_version,
            "sessionMaxAgeSeconds": selection.rules.session_max_age_seconds,
            "canaryTimeoutSeconds": selection.rules.canary_timeout_seconds,
            "buildAssetMarkers": selection.rules.build_asset_markers,
            "discoveryMarkers": selection.rules.discovery_markers,
            "capabilities": selection.rules.capabilities,
            "endpointPaths": selection.rules.endpoint_paths,
        },
    }
    return hashlib.sha256(
        json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()


def _parse_selection(value: Any, provider_id: str, name: str) -> RuntimeRuleSelection:
    if not isinstance(value, dict):
        raise TypeError(f"runtime {name} selection must be an object")
    selected_provider = str(value.get("providerId") or "").strip()
    if selected_provider != provider_id:
        raise ValueError(f"runtime {name} provider does not match request provider")
    revision = value.get("revision")
    if not isinstance(revision, int) or isinstance(revision, bool) or revision <= 0:
        raise ValueError(f"runtime {name} revision must be positive")
    return RuntimeRuleSelection(
        provider_id=selected_provider,
        revision=revision,
        rules=_parse_rule(value.get("rules")),
    )


def _parse_build_id(value: Any) -> str:
    if value is None or value == "":
        return ""
    if not isinstance(value, str) or not _BUILD_ID.fullmatch(value.strip().lower()):
        raise ValueError("runtime plan build id must be a SHA-256 value")
    return value.strip().lower()


def _parse_rule(value: Any) -> RuntimeRule:
    if not isinstance(value, dict):
        raise TypeError("runtime rule must be an object")
    unknown = set(value) - _ALLOWED_RULE_FIELDS
    if unknown:
        raise ValueError("runtime rule contains unsupported fields")
    schema_version = _bounded_integer(value.get("schemaVersion"), 1, 1, "schemaVersion")
    session_age = _bounded_integer(
        value.get("sessionMaxAgeSeconds"), 60, 86_400, "sessionMaxAgeSeconds"
    )
    timeout = _bounded_integer(
        value.get("canaryTimeoutSeconds"), 5, 300, "canaryTimeoutSeconds"
    )
    build_markers = _string_list(value.get("buildAssetMarkers"), 1, 16)
    discovery = _string_list_map(value.get("discoveryMarkers"), require_value=True)
    capabilities = _string_map(value.get("capabilities"), paths=False)
    endpoints = _string_map(value.get("endpointPaths"), paths=True)
    encoded = json.dumps(value, ensure_ascii=True, separators=(",", ":")).encode()
    if len(encoded) > 32_768:
        raise ValueError("runtime rule exceeds 32 KiB")
    return RuntimeRule(
        schema_version=schema_version,
        session_max_age_seconds=session_age,
        canary_timeout_seconds=timeout,
        build_asset_markers=build_markers,
        discovery_markers=discovery,
        capabilities=capabilities,
        endpoint_paths=endpoints,
    )


def _bounded_integer(value: Any, minimum: int, maximum: int, name: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool):
        raise TypeError(f"runtime rule {name} must be an integer")
    if value < minimum or value > maximum:
        raise ValueError(f"runtime rule {name} is outside allowed range")
    return value


def _string_list_map(value: Any, *, require_value: bool) -> dict[str, tuple[str, ...]]:
    if not isinstance(value, dict) or len(value) > 16:
        raise TypeError("runtime rule discoveryMarkers must be a bounded object")
    output: dict[str, tuple[str, ...]] = {}
    for raw_key, raw_values in value.items():
        output[_key(raw_key)] = _string_list(raw_values, 1, 16)
    if require_value and not output:
        raise ValueError("runtime rule discoveryMarkers must not be empty")
    return output


def _string_map(value: Any, *, paths: bool) -> dict[str, str]:
    if not isinstance(value, dict) or len(value) > 16:
        raise TypeError("runtime rule string map must be a bounded object")
    output: dict[str, str] = {}
    for raw_key, raw_value in value.items():
        normalized = _literal(raw_value)
        if paths and (
            not normalized.startswith("/")
            or "://" in normalized
            or any(character.isspace() for character in normalized)
        ):
            raise ValueError("runtime endpoint must be a same-origin path")
        output[_key(raw_key)] = normalized
    return output


def _string_list(value: Any, minimum: int, maximum: int) -> tuple[str, ...]:
    if not isinstance(value, list) or not minimum <= len(value) <= maximum:
        raise TypeError("runtime rule marker list is outside allowed size")
    output: list[str] = []
    for raw_value in value:
        normalized = _literal(raw_value)
        if normalized not in output:
            output.append(normalized)
    if len(output) < minimum:
        raise ValueError("runtime rule marker list must contain a value")
    return tuple(output)


def _key(value: Any) -> str:
    normalized = str(value or "").strip()
    if not _RULE_KEY.fullmatch(normalized):
        raise ValueError("runtime rule contains an invalid key")
    return normalized


def _literal(value: Any) -> str:
    if not isinstance(value, str):
        raise TypeError("runtime rule literals must be strings")
    normalized = value.strip()
    if (
        not normalized
        or len(normalized) > 256
        or any(ord(character) < 32 or ord(character) == 127 for character in normalized)
    ):
        raise ValueError("runtime rule contains an invalid literal")
    return normalized
