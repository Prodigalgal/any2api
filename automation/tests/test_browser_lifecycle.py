from contextlib import contextmanager

from any2api_automation.lifecycle import browser as browser_lifecycle
from any2api_automation.lifecycle.browser import BrowserResult, run_browser_flow


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

    class Browser:
        def new_context(self, **options: object) -> Context:
            del options
            return context

    @contextmanager
    def proxy(**options: object):
        del options
        yield ""

    @contextmanager
    def launch(*args: object, **kwargs: object):
        del args, kwargs
        yield "camoufox", Browser()

    monkeypatch.setattr(browser_lifecycle, "proxy_lease", proxy)
    monkeypatch.setattr(browser_lifecycle, "launch_browser", launch)

    result = run_browser_flow(
        lambda page, current, backend, proxy_url: BrowserResult(
            "account-id",
            "mail@example.test",
            {"backend": backend, "proxy": proxy_url, "page": page is not None},
        ),
        preferred="camoufox",
        fallback=None,
        payload={},
    )

    assert result.external_id == "account-id"
    assert context.timeouts[-1] == 5_000
    assert context.closed is True
