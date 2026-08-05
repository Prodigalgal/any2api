import json
from contextlib import contextmanager

import pytest

from any2api_automation.lifecycle import browser as browser_lifecycle
from any2api_automation.lifecycle.browser import (
    BrowserContextProfile,
    BrowserLaunchProfile,
    BrowserResult,
    launch_browser,
    run_browser_flow,
)


def test_browser_flow_bounds_context_cleanup_timeout(monkeypatch) -> None:
    class Context:
        def __init__(self) -> None:
            self.timeouts: list[int] = []
            self.closed = False

        def set_default_timeout(self, timeout: int) -> None:
            self.timeouts.append(timeout)

        def new_page(self) -> object:
            return object()

        def close(self) -> None:
            self.closed = True

    context = Context()
    context_options: dict[str, object] = {}
    launch_options: dict[str, object] = {}

    class Browser:
        def new_context(self, **options: object) -> Context:
            context_options.update(options)
            return context

    @contextmanager
    def proxy(**options: object):
        del options
        yield "http://proxy.internal:8080"

    @contextmanager
    def launch(*args: object, **kwargs: object):
        del args
        launch_options.update(kwargs)
        yield "camoufox", Browser()

    monkeypatch.setattr(browser_lifecycle, "proxy_lease", proxy)
    monkeypatch.setattr(browser_lifecycle, "launch_browser", launch)

    resolved_profile = BrowserLaunchProfile(headless=False, camoufox_os="windows")
    resolved_context = BrowserContextProfile(timezone_id="Asia/Singapore")
    resolved_proxies: list[str] = []

    def resolve(
        proxy_url: str,
    ) -> tuple[BrowserContextProfile, BrowserLaunchProfile]:
        resolved_proxies.append(proxy_url)
        return resolved_context, resolved_profile

    result = run_browser_flow(
        lambda page, current, backend, proxy_url: BrowserResult(
            "account-id",
            "mail@example.test",
            {"backend": backend, "proxy": proxy_url, "page": page is not None},
        ),
        preferred="camoufox",
        fallback=None,
        payload={},
        profile_resolver=resolve,
    )

    assert result.external_id == "account-id"
    assert context.timeouts[-1] == 5_000
    assert context.closed is True
    assert resolved_proxies == ["http://proxy.internal:8080"]
    assert launch_options["profile"] is resolved_profile
    assert context_options["timezone_id"] == "Asia/Singapore"


def test_browser_launcher_cleans_partially_started_camoufox(monkeypatch) -> None:
    import camoufox.sync_api

    class Manager:
        def __init__(self) -> None:
            self.exited = False

        def __enter__(self):
            raise RuntimeError("browser launch failed after runtime start")

        def __exit__(self, *args: object) -> None:
            del args
            self.exited = True

    manager = Manager()
    monkeypatch.setattr(camoufox.sync_api, "Camoufox", lambda **options: manager)

    with (
        pytest.raises(RuntimeError, match="no browser backend available"),
        launch_browser("camoufox", None, headless=True, proxy_url=""),
    ):
        raise AssertionError("failed launcher must not yield")

    assert manager.exited is True


def test_browser_launcher_replays_geo_aligned_camoufox_config_without_regeneration(
    monkeypatch,
) -> None:
    import camoufox.sync_api
    import camoufox.utils

    generated_config = {
        "navigator.userAgent": "fixture-agent",
        "timezone": "Asia/Tokyo",
        "geolocation:latitude": 35.0,
        "geolocation:longitude": 139.0,
    }
    launch_input: dict[str, object] = {}
    runtime_options: dict[str, object] = {}

    def prepare(**options: object) -> dict[str, object]:
        launch_input.update(options)
        return {"env": {"CAMOU_CONFIG_1": "stale"}, "firefox_user_prefs": {}}

    class Manager:
        def __init__(self, **options: object) -> None:
            runtime_options.update(options["from_options"])

        def __enter__(self) -> object:
            return object()

        def __exit__(self, *args: object) -> None:
            del args

    monkeypatch.setattr(camoufox.utils, "launch_options", prepare)
    monkeypatch.setattr(
        camoufox.utils,
        "get_env_vars",
        lambda config, target: {"CAMOU_CONFIG_1": json.dumps(config, sort_keys=True)},
    )
    monkeypatch.setattr(camoufox.utils, "get_target_os", lambda config: "win")
    monkeypatch.setattr(camoufox.sync_api, "Camoufox", Manager)

    profile = BrowserLaunchProfile(
        camoufox_config=generated_config,
        camoufox_firefox_user_prefs={"fixture.pref": True},
    )
    with launch_browser(
        "camoufox",
        None,
        headless=False,
        proxy_url="http://proxy.internal:8080",
        profile=profile,
    ):
        pass

    assert launch_input["geoip"] is False
    assert launch_input["proxy"] is None
    assert launch_input["i_know_what_im_doing"] is True
    assert runtime_options["proxy"] == {"server": "http://proxy.internal:8080"}
    assert json.loads(runtime_options["env"]["CAMOU_CONFIG_1"]) == generated_config
    assert runtime_options["firefox_user_prefs"]["fixture.pref"] is True


def test_browser_launcher_reaps_processes_when_runtime_close_fails(monkeypatch) -> None:
    import camoufox.sync_api

    class Manager:
        def __enter__(self) -> object:
            return object()

        def __exit__(self, *args: object) -> None:
            del args
            raise RuntimeError("runtime close failed")

    reaped: list[tuple[tuple[int, ...], str]] = []
    monkeypatch.setattr(camoufox.sync_api, "Camoufox", lambda **options: Manager())
    monkeypatch.setattr(browser_lifecycle, "_driver_pid", lambda value: 700)
    monkeypatch.setattr(browser_lifecycle, "_process_tree", lambda pid: [pid, 701])
    monkeypatch.setattr(
        browser_lifecycle,
        "terminate_residual_browser_process",
        lambda value, *, label: reaped.append((value, label)),
    )

    with (
        pytest.raises(RuntimeError, match="runtime close failed"),
        launch_browser("camoufox", None, headless=True, proxy_url="") as launched,
    ):
        assert launched[0] == "camoufox"

    assert reaped == [((700, 701), "camoufox runtime cleanup")]
