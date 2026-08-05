from __future__ import annotations

import hashlib
import json
import re
import secrets
from copy import deepcopy
from dataclasses import dataclass
from typing import Any

QWEN_FINGERPRINT_SCHEMA_VERSION = 2
QWEN_LEGACY_FINGERPRINT_SCHEMA_VERSION = 1
QWEN_FINGERPRINT_MAX_BYTES = 256 << 10
PATCHRIGHT_PROFILES = ("chrome142", "chrome145", "chrome146")
CAMOUFOX_PROFILES = ("firefox144", "firefox147")
CAMOUFOX_GENERATION_ATTEMPTS = 8

_PATCHRIGHT_PERSONAS = (
    (1366, 768, 1366, 657, 4),
    (1440, 900, 1440, 789, 8),
    (1536, 864, 1536, 753, 12),
    (1920, 1080, 1920, 969, 16),
)


@dataclass(frozen=True)
class QwenFingerprintPlan:
    patchright: dict[str, Any]
    camoufox: dict[str, Any]

    def for_backend(self, backend: str) -> dict[str, Any]:
        if backend == "patchright":
            return deepcopy(self.patchright)
        if backend == "camoufox":
            return deepcopy(self.camoufox)
        raise ValueError(f"unsupported Qwen browser backend: {backend}")


def new_qwen_fingerprint(backend: str, *, proxy_url: str = "") -> dict[str, Any]:
    if backend == "patchright":
        return _new_patchright_fingerprint()
    if backend == "camoufox":
        return _new_camoufox_fingerprint(proxy_url)
    raise ValueError(f"unsupported Qwen browser backend: {backend}")


def new_qwen_fingerprint_plan(*, camoufox_proxy_url: str = "") -> QwenFingerprintPlan:
    patchright = new_qwen_fingerprint("patchright")
    camoufox = new_qwen_fingerprint("camoufox", proxy_url=camoufox_proxy_url)
    if camoufox_proxy_url:
        patchright = normalize_qwen_fingerprint(
            {**patchright, "timezone_id": camoufox["timezone_id"]}
        )
    return QwenFingerprintPlan(
        patchright=patchright,
        camoufox=camoufox,
    )


def normalize_qwen_fingerprint(value: Any) -> dict[str, Any]:
    if value in (None, {}):
        return {}
    if not isinstance(value, dict):
        raise TypeError("Qwen browser fingerprint must be an object")
    try:
        encoded = json.dumps(value, ensure_ascii=True, separators=(",", ":"))
    except (TypeError, ValueError) as error:
        raise TypeError("Qwen browser fingerprint must contain JSON values") from error
    if len(encoded.encode("utf-8")) > QWEN_FINGERPRINT_MAX_BYTES:
        raise ValueError("Qwen browser fingerprint exceeds the persisted size limit")
    normalized = json.loads(encoded)
    schema_version = normalized.get("schema_version")
    if schema_version == QWEN_LEGACY_FINGERPRINT_SCHEMA_VERSION:
        normalized = _migrate_legacy_fingerprint(normalized)
    elif schema_version != QWEN_FINGERPRINT_SCHEMA_VERSION:
        raise ValueError("unsupported Qwen browser fingerprint schema")
    backend = str(normalized.get("backend") or "")
    profile = str(normalized.get("browser_profile") or "").lower()
    allowed_profiles = PATCHRIGHT_PROFILES if backend == "patchright" else CAMOUFOX_PROFILES
    if backend not in {"patchright", "camoufox"} or profile not in allowed_profiles:
        raise ValueError("unsupported Qwen browser fingerprint backend or profile")
    user_agent = str(normalized.get("user_agent") or "")
    if not 40 <= len(user_agent) <= 512 or "\r" in user_agent or "\n" in user_agent:
        raise ValueError("invalid Qwen browser fingerprint User-Agent")
    major = browser_major(profile)
    product = "Chrome" if backend == "patchright" else "Firefox"
    if f"{product}/{major}." not in user_agent:
        raise ValueError("Qwen browser fingerprint profile does not match its User-Agent")
    if backend == "patchright":
        _validate_patchright_fingerprint(normalized)
    else:
        config = normalized.get("camoufox_config")
        prefs = normalized.get("firefox_user_prefs")
        if not isinstance(config, dict) or not config:
            raise ValueError("Camoufox fingerprint requires its generated config")
        if not isinstance(prefs, dict):
            raise ValueError("Camoufox fingerprint requires Firefox preferences")
        if str(config.get("navigator.userAgent") or "") != user_agent:
            raise ValueError("Camoufox config User-Agent does not match its manifest")
        if "humanize" in config or "humanize:maxTime" in config:
            raise ValueError("Camoufox fingerprint must not persist cursor timing controls")
    interaction_profile = normalized.get("interaction_profile")
    if interaction_profile != {
        "mode": "qwen_slider_v1",
        "camoufox_native_humanize": False,
    }:
        raise ValueError("unsupported Qwen browser interaction profile")
    normalized["browser_profile"] = profile
    return normalized


def qwen_fingerprint_digest(value: Any) -> str:
    normalized = normalize_qwen_fingerprint(value)
    if not normalized:
        return ""
    encoded = json.dumps(normalized, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def browser_major(profile: str) -> str:
    match = re.fullmatch(r"(?:chrome|firefox)(\d{2,3})", profile.strip().lower())
    if not match:
        raise ValueError("Qwen browser profile must include a supported major version")
    return match.group(1)


def patchright_client_hints(fingerprint: dict[str, Any]) -> dict[str, str]:
    normalized = normalize_qwen_fingerprint(fingerprint)
    if normalized.get("backend") != "patchright":
        return {}
    major = browser_major(str(normalized["browser_profile"]))
    return {
        "sec-ch-ua": (f'"Not;A=Brand";v="99", "Chromium";v="{major}", "Google Chrome";v="{major}"'),
        "sec-ch-ua-mobile": "?0",
        "sec-ch-ua-platform": '"Windows"',
    }


def patchright_cdp_commands(
    fingerprint: dict[str, Any],
) -> tuple[tuple[str, dict[str, Any]], ...]:
    normalized = normalize_qwen_fingerprint(fingerprint)
    if normalized.get("backend") != "patchright":
        return ()
    major = browser_major(str(normalized["browser_profile"]))
    brands = [
        {"brand": "Not;A=Brand", "version": "99"},
        {"brand": "Chromium", "version": major},
        {"brand": "Google Chrome", "version": major},
    ]
    metadata = {
        "brands": brands,
        "fullVersionList": [
            {"brand": value["brand"], "version": value["version"] + ".0.0.0"} for value in brands
        ],
        "fullVersion": major + ".0.0.0",
        "platform": "Windows",
        "platformVersion": "10.0.0",
        "architecture": "x86",
        "model": "",
        "mobile": False,
        "bitness": "64",
        "wow64": False,
    }
    return (
        (
            "Network.setUserAgentOverride",
            {
                "userAgent": str(normalized["user_agent"]),
                "acceptLanguage": str(normalized["accept_language"]),
                "platform": "Win32",
                "userAgentMetadata": metadata,
            },
        ),
        (
            "Emulation.setHardwareConcurrencyOverride",
            {"hardwareConcurrency": int(normalized["hardware_concurrency"])},
        ),
    )


def finalize_patchright_fingerprint(
    fingerprint: dict[str, Any], observed: dict[str, Any]
) -> dict[str, Any]:
    normalized = normalize_qwen_fingerprint(fingerprint)
    if normalized.get("backend") != "patchright":
        return normalized
    runtime = {
        "device_memory": observed.get("device_memory"),
        "webgl_vendor": str(observed.get("webgl_vendor") or ""),
        "webgl_renderer": str(observed.get("webgl_renderer") or ""),
    }
    if not isinstance(runtime["device_memory"], (int, float)):
        raise TypeError("Patchright did not expose deviceMemory")
    if not runtime["webgl_vendor"] or not runtime["webgl_renderer"]:
        raise RuntimeError("Patchright did not expose its WebGL identity")
    expected = normalized.get("observed")
    if isinstance(expected, dict) and expected and expected != runtime:
        raise RuntimeError("Patchright runtime identity drifted from the persisted account")
    normalized["observed"] = runtime
    return normalize_qwen_fingerprint(normalized)


def camoufox_launch_options(
    fingerprint: dict[str, Any],
    *,
    headless: bool,
    proxy_url: str,
) -> dict[str, Any]:
    from camoufox.utils import get_env_vars, get_target_os, launch_options

    normalized = normalize_qwen_fingerprint(fingerprint)
    if normalized.get("backend") != "camoufox":
        raise ValueError("Camoufox launch requires a Camoufox fingerprint")
    exact_config = deepcopy(normalized["camoufox_config"])
    options: dict[str, Any] = {
        "config": deepcopy(exact_config),
        "headless": headless,
        "humanize": False,
        "i_know_what_im_doing": True,
        "firefox_user_prefs": deepcopy(normalized["firefox_user_prefs"]),
        "block_webrtc": True,
    }
    if proxy_url:
        options["proxy"] = {"server": proxy_url}
    prepared = launch_options(**options)
    environment = dict(prepared.get("env") or {})
    for name in tuple(environment):
        if name.startswith("CAMOU_CONFIG_"):
            environment.pop(name)
    environment.update(get_env_vars(exact_config, get_target_os(exact_config)))
    prepared["env"] = environment
    prepared["firefox_user_prefs"] = {
        **dict(prepared.get("firefox_user_prefs") or {}),
        **deepcopy(normalized["firefox_user_prefs"]),
    }
    return prepared


def _new_patchright_fingerprint() -> dict[str, Any]:
    profile = secrets.choice(PATCHRIGHT_PROFILES)
    screen_width, screen_height, viewport_width, viewport_height, cores = secrets.choice(
        _PATCHRIGHT_PERSONAS
    )
    major = browser_major(profile)
    value = {
        "schema_version": QWEN_FINGERPRINT_SCHEMA_VERSION,
        "backend": "patchright",
        "variant_id": secrets.token_hex(12),
        "browser_profile": profile,
        "user_agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            f"Chrome/{major}.0.0.0 Safari/537.36"
        ),
        "os": "windows",
        "locale": "zh-CN",
        "timezone_id": "Asia/Shanghai",
        "accept_language": "zh-CN,zh;q=0.9,en;q=0.8",
        "color_scheme": secrets.choice(("light", "dark")),
        "screen": {"width": screen_width, "height": screen_height},
        "viewport": {"width": viewport_width, "height": viewport_height},
        "device_scale_factor": 1,
        "hardware_concurrency": cores,
        "interaction_profile": {
            "mode": "qwen_slider_v1",
            "camoufox_native_humanize": False,
        },
    }
    return normalize_qwen_fingerprint(value)


def _new_camoufox_fingerprint(proxy_url: str) -> dict[str, Any]:
    from camoufox.utils import launch_options

    profile = secrets.choice(CAMOUFOX_PROFILES)
    major = int(browser_major(profile))
    last_error: ValueError | None = None
    for _ in range(CAMOUFOX_GENERATION_ATTEMPTS):
        try:
            options: dict[str, Any] = {
                "os": "windows",
                "locale": "zh-CN",
                "headless": True,
                "fingerprint_preset": True,
                "ff_version": major,
                "i_know_what_im_doing": True,
                "block_webrtc": True,
                "humanize": False,
            }
            if proxy_url:
                options.update(
                    {
                        "geoip": True,
                        "proxy": {"server": proxy_url},
                    }
                )
            prepared = launch_options(
                **options,
            )
            break
        except ValueError as error:
            if "No WebGL data found for vendor" not in str(error):
                raise
            last_error = error
    else:
        raise RuntimeError(
            "Camoufox could not generate a coherent WebGL fingerprint"
        ) from last_error
    chunks = sorted(
        (int(name.rsplit("_", 1)[1]), value)
        for name, value in dict(prepared.get("env") or {}).items()
        if name.startswith("CAMOU_CONFIG_")
    )
    if not chunks:
        raise RuntimeError("Camoufox did not emit a generated fingerprint config")
    config = json.loads("".join(str(value) for _, value in chunks))
    config.pop("addons", None)
    if not str(config.get("timezone") or "").strip():
        config["timezone"] = "Asia/Shanghai"
    timezone_id = str(config["timezone"])
    value = {
        "schema_version": QWEN_FINGERPRINT_SCHEMA_VERSION,
        "backend": "camoufox",
        "variant_id": secrets.token_hex(12),
        "browser_profile": profile,
        "user_agent": str(config.get("navigator.userAgent") or ""),
        "os": "windows",
        "locale": "zh-CN",
        "timezone_id": timezone_id,
        "accept_language": "zh-CN,zh;q=0.9,en;q=0.8",
        "camoufox_config": config,
        "firefox_user_prefs": dict(prepared.get("firefox_user_prefs") or {}),
        "interaction_profile": {
            "mode": "qwen_slider_v1",
            "camoufox_native_humanize": False,
        },
    }
    return normalize_qwen_fingerprint(value)


def _migrate_legacy_fingerprint(value: dict[str, Any]) -> dict[str, Any]:
    migrated = deepcopy(value)
    migrated["schema_version"] = QWEN_FINGERPRINT_SCHEMA_VERSION
    migrated["interaction_profile"] = {
        "mode": "qwen_slider_v1",
        "camoufox_native_humanize": False,
    }
    config = migrated.get("camoufox_config")
    if isinstance(config, dict):
        config.pop("humanize", None)
        config.pop("humanize:maxTime", None)
    return migrated


def _validate_patchright_fingerprint(value: dict[str, Any]) -> None:
    for field, lower, upper in (
        ("hardware_concurrency", 2, 32),
        ("device_scale_factor", 0.5, 3),
    ):
        candidate = value.get(field)
        if not isinstance(candidate, (int, float)) or not lower <= candidate <= upper:
            raise ValueError(f"invalid Qwen browser fingerprint {field}")
    for field in ("screen", "viewport"):
        dimensions = value.get(field)
        if not isinstance(dimensions, dict):
            raise TypeError(f"Qwen browser fingerprint requires {field}")
        width = dimensions.get("width")
        height = dimensions.get("height")
        if not isinstance(width, int) or not 800 <= width <= 3840:
            raise ValueError(f"invalid Qwen browser fingerprint {field} width")
        if not isinstance(height, int) or not 600 <= height <= 2160:
            raise ValueError(f"invalid Qwen browser fingerprint {field} height")
    for field in (
        "variant_id",
        "locale",
        "timezone_id",
        "accept_language",
        "color_scheme",
    ):
        candidate = value.get(field)
        if not isinstance(candidate, str) or not candidate or len(candidate) > 512:
            raise ValueError(f"invalid Qwen browser fingerprint {field}")
    observed = value.get("observed")
    if observed is not None:
        if not isinstance(observed, dict):
            raise TypeError("Qwen observed browser fingerprint must be an object")
        memory = observed.get("device_memory")
        if not isinstance(memory, (int, float)) or not 1 <= memory <= 256:
            raise ValueError("invalid Qwen observed device memory")
        for field in ("webgl_vendor", "webgl_renderer"):
            candidate = observed.get(field)
            if not isinstance(candidate, str) or not candidate or len(candidate) > 512:
                raise ValueError(f"invalid Qwen observed {field}")
