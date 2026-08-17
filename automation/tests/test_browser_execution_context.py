from any2api_automation.lifecycle.browser import (
    _CAMOUFOX_RUNTIME_CONFIGS,
    camoufox_config_from_options,
    capture_browser_execution_context,
)


def test_browser_execution_context_persists_storage_and_runtime_fingerprint() -> None:
    class Context:
        def storage_state(self, *, indexed_db: bool) -> dict[str, object]:
            assert indexed_db is True
            return {
                "cookies": [{"name": "session", "value": "secret", "domain": ".example.com"}],
                "origins": [{"origin": "https://example.com", "localStorage": []}],
            }

    class Page:
        def evaluate(self, script: str) -> dict[str, object]:
            assert "WEBGL_debug_renderer_info" in script
            return {
                "user_agent": "Mozilla/5.0 Firefox/150.0",
                "platform": "Win32",
                "timezone_id": "Asia/Shanghai",
                "screen": {"width": 1440, "height": 900},
            }

    browser = object()
    _CAMOUFOX_RUNTIME_CONFIGS[id(browser)] = {"navigator.userAgent": "Mozilla/5.0 Firefox/150.0"}

    result = capture_browser_execution_context(
        browser,
        Context(),
        Page(),
        backend="camoufox",
        fingerprint_variant="windows-1440x900",
    )

    assert result["schema_version"] == 1
    assert result["backend"] == "camoufox"
    assert result["storage_state"]["cookies"][0]["name"] == "session"
    assert result["runtime_fingerprint"]["timezone_id"] == "Asia/Shanghai"
    assert result["camoufox_config"]["navigator.userAgent"].endswith("Firefox/150.0")
    _CAMOUFOX_RUNTIME_CONFIGS.pop(id(browser))


def test_camoufox_config_is_reassembled_without_machine_specific_addon_paths() -> None:
    options = {
        "env": {
            "CAMOU_CONFIG_2": 'Agent":"fixture"}',
            "CAMOU_CONFIG_1": '{"navigator.user',
            "PATH": "ignored",
        }
    }

    result = camoufox_config_from_options(options)

    assert result == {"navigator.userAgent": "fixture"}
