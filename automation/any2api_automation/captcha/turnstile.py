from __future__ import annotations

import json
import re
import threading
import time
from types import TracebackType
from typing import Any, Self

from ..lifecycle.browser import BrowserLaunchProfile, close_browser_context, launch_browser

_turnstile_slots = threading.BoundedSemaphore(1)


class LocalTurnstileSolver:
    """Shared-process Camoufox Turnstile solver with one browser per registration flow."""

    def __init__(
        self,
        *,
        proxy_url: str = "",
        headless: bool = True,
        rounds: int = 2,
        timeout_seconds: int = 55,
    ) -> None:
        self.proxy_url = proxy_url
        self.headless = headless
        self.rounds = max(1, rounds)
        self.timeout_seconds = max(10, timeout_seconds)
        self._browser_manager: Any = None
        self._browser: Any = None

    def __enter__(self) -> Self:
        _turnstile_slots.acquire()
        try:
            self._browser_manager = launch_browser(
                "camoufox",
                None,
                headless=self.headless,
                proxy_url=self.proxy_url,
                profile=BrowserLaunchProfile(
                    headless=self.headless,
                    humanize=False,
                    block_webrtc=True,
                    launch_timeout_ms=120_000,
                ),
            )
            _, self._browser = self._browser_manager.__enter__()
            return self
        except Exception:
            _turnstile_slots.release()
            raise

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        try:
            if self._browser_manager is not None:
                self._browser_manager.__exit__(exc_type, exc, traceback)
        finally:
            self._browser_manager = None
            self._browser = None
            _turnstile_slots.release()

    def solve_turnstile(
        self,
        *,
        website_url: str,
        website_key: str,
        premium: bool = False,
        fallback_non_premium: bool = True,
        action: str = "",
        cdata: str = "",
        **_kwargs: Any,
    ) -> str:
        del premium, fallback_non_premium
        if self._browser is None:
            raise RuntimeError("Turnstile solver is not running")
        if not re.fullmatch(r"0x4[0-9A-Za-z_-]{10,}", website_key.strip()):
            raise ValueError("invalid Turnstile sitekey")
        last_error = "token timeout"
        for round_index in range(1, self.rounds + 1):
            context = None
            page = None
            try:
                context = self._browser.new_context(no_viewport=True)
                context.set_default_timeout(self.timeout_seconds * 1000)
                page = context.new_page()
                page.set_viewport_size({"width": 500, "height": 100})
                page.add_init_script(
                    """(() => {
                      Object.defineProperty(navigator, 'webdriver', {get: () => undefined});
                      const original = Element.prototype.attachShadow;
                      Element.prototype.attachShadow = function(init) {
                        const root = original.call(this, init);
                        if (init?.mode === 'closed') window.__lastClosedShadowRoot = root;
                        return root;
                      };
                    })()"""
                )
                page.goto(website_url, wait_until="domcontentloaded", timeout=45_000)
                _dismiss_cookie_banner(page)
                _inject_widget(page, website_key, action, cdata)
                deadline = time.monotonic() + self.timeout_seconds
                attempt = 0
                while time.monotonic() < deadline:
                    attempt += 1
                    token = _turnstile_token(page)
                    if token:
                        return token
                    if attempt >= 3 and attempt % 2 == 1:
                        _click_turnstile(page)
                    if attempt == 10 and round_index < self.rounds:
                        _inject_widget(page, website_key, action, cdata)
                    page.wait_for_timeout(min(1800, 450 + attempt * 40))
                last_error = (
                    f"token timeout round {round_index}/{self.rounds}; {_turnstile_state(page)}"
                )
            except Exception as error:  # noqa: BLE001 - each fresh context is an attempt
                last_error = f"{type(error).__name__}: {str(error)[:180]}"
            finally:
                if context is not None:
                    close_browser_context(
                        context,
                        page if page is not None else context,
                        label="Turnstile context cleanup",
                    )
            if round_index < self.rounds:
                time.sleep(0.8)
        raise RuntimeError(f"Turnstile solve failed: {last_error}")


def _inject_widget(page: Any, sitekey: str, action: str, cdata: str) -> None:
    arguments = json.dumps({"sitekey": sitekey, "action": action, "cdata": cdata})
    page.evaluate(
        f"""() => {{
          const options = {arguments};
          document.querySelectorAll('[data-any2api-turnstile]').forEach(el => el.remove());
          let token = document.querySelector('input[name="cf-turnstile-response"]');
          if (!token) {{
            token = document.createElement('input');
            token.type = 'hidden';
            token.name = 'cf-turnstile-response';
            document.body.appendChild(token);
          }}
          token.value = '';
          window.__any2apiTurnstileState = {{rendered: false, renderError: '', widgetId: ''}};
          const host = document.createElement('div');
          host.className = 'cf-turnstile';
          host.dataset.any2apiTurnstile = '1';
          host.dataset.sitekey = options.sitekey;
          host.dataset.callback = '__any2apiTurnstileCallback';
          if (options.action) host.dataset.action = options.action;
          if (options.cdata) host.dataset.cdata = options.cdata;
          Object.assign(host.style, {{position:'fixed', top:'20px', left:'20px', zIndex:'2147483647',
            background:'#fff', padding:'12px', minWidth:'320px', minHeight:'70px'}});
          document.body.appendChild(host);
          window.__any2apiTurnstileCallback = value => {{
            token.value = value || '';
            window.__any2apiTurnstileState.rendered = true;
          }};
          const render = () => {{
            if (!window.turnstile?.render || host.querySelector('iframe')) return;
            try {{
              const widgetId = window.turnstile.render(host, {{
                sitekey: options.sitekey,
                ...(options.action ? {{action: options.action}} : {{}}),
                ...(options.cdata ? {{cData: options.cdata}} : {{}}),
                callback: window.__any2apiTurnstileCallback,
                'error-callback': code => {{
                  window.__any2apiTurnstileState.renderError = String(code || 'widget_error');
                }}
              }});
              window.__any2apiTurnstileState.widgetId = String(widgetId || '');
            }} catch (error) {{
              window.__any2apiTurnstileState.renderError = String(error?.message || error);
            }}
          }};
          const readyRender = () => {{
            if (window.turnstile?.ready) window.turnstile.ready(render);
            else render();
            setTimeout(render, 1000);
          }};
          if (window.turnstile?.render) {{ readyRender(); return; }}
          const existing = [...document.scripts].find(script =>
            script.src.includes('challenges.cloudflare.com/turnstile/v0/api.js'));
          if (existing) {{
            existing.addEventListener('load', readyRender, {{once: true}});
            setTimeout(readyRender, 1200);
            return;
          }}
          const script = document.createElement('script');
          script.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js';
          script.async = true;
          script.defer = true;
          script.onload = () => setTimeout(readyRender, 500);
          document.head.appendChild(script);
        }}"""
    )


def _turnstile_token(page: Any) -> str:
    try:
        fields = page.locator('input[name="cf-turnstile-response"]')
        for index in range(fields.count()):
            value = str(fields.nth(index).input_value(timeout=500) or "").strip()
            if len(value) >= 20:
                return value
    except Exception:  # noqa: BLE001,S110 - widget can rerender between reads
        pass
    return ""


def _turnstile_state(page: Any) -> str:
    try:
        state = page.evaluate(
            """() => ({
              tokenFields: document.querySelectorAll('input[name="cf-turnstile-response"]').length,
              iframes: document.querySelectorAll('iframe[src*="challenges.cloudflare.com"]').length,
              widgets: document.querySelectorAll('[data-any2api-turnstile]').length,
              apiReady: Boolean(window.turnstile?.render),
              renderError: String(window.__any2apiTurnstileState?.renderError || ''),
              widgetId: String(window.__any2apiTurnstileState?.widgetId || '')
            })"""
        )
        return (
            f"token_fields={int(state.get('tokenFields') or 0)}, "
            f"iframes={int(state.get('iframes') or 0)}, "
            f"widgets={int(state.get('widgets') or 0)}, "
            f"api_ready={bool(state.get('apiReady'))}, "
            f"widget_id={bool(state.get('widgetId'))}, "
            f"render_error={str(state.get('renderError') or 'none')[:120]}"
        )
    except Exception as error:  # noqa: BLE001 - diagnostics must not mask the failure
        return f"state_error={type(error).__name__}"


def _click_turnstile(page: Any) -> bool:
    for selector in (
        ".cf-turnstile",
        "[data-any2api-turnstile]",
        'iframe[src*="challenges.cloudflare.com"]',
        'iframe[src*="turnstile"]',
        'iframe[title*="widget"]',
    ):
        try:
            target = page.locator(selector).first
            if not target.count() or not target.is_visible():
                continue
            try:
                target.click(force=True, timeout=1000)
            except Exception:  # noqa: BLE001 - coordinate fallback for browser click
                box = target.bounding_box()
                if not box:
                    continue
                page.mouse.click(
                    box["x"] + box["width"] / 2,
                    box["y"] + box["height"] / 2,
                )
            return True
        except Exception:  # noqa: BLE001,S112 - try the next widget surface
            continue
    return False


def _dismiss_cookie_banner(page: Any) -> None:
    for selector in (
        "#onetrust-accept-btn-handler",
        "#onetrust-reject-all-handler",
        ".onetrust-close-btn-handler",
    ):
        try:
            button = page.locator(selector).first
            if button.count() and button.is_visible():
                button.click(force=True, timeout=800)
                break
        except Exception:  # noqa: BLE001,S112 - consent UI is optional
            continue
    try:
        page.evaluate(
            """() => ['onetrust-consent-sdk','onetrust-banner-sdk','onetrust-pc-sdk']
              .forEach(id => document.getElementById(id)?.remove())"""
        )
    except Exception:  # noqa: BLE001,S110 - banner cleanup is best effort
        pass
