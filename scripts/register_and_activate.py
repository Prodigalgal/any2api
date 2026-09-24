import asyncio
import base64
import hashlib
import hmac
import json
import os
import re
import sys
import time
import uuid
import httpx
from camoufox.sync_api import Camoufox
from any2api_automation.lifecycle.mail import TempMailClient
from any2api_automation.lifecycle.account import strong_password
from any2api_automation.captcha.turnstile import LocalTurnstileSolver


def make_admin_session():
    secret = os.environ.get("ANY2API_INTERNAL_TOKEN", "Xu6P_gq2q_Br2-11OGPKXBi3DUStZziW3SM-EHbq2nk")
    signing_key = hashlib.sha256(secret.encode("utf-8")).digest()
    username = os.environ.get("ANY2API_ADMIN_USERNAME", "admin")
    expires_at = int(time.time()) + 86400 * 7
    payload = f"{username}:{expires_at}:{uuid.uuid4()}"
    encoded = base64.urlsafe_b64encode(payload.encode("utf-8")).decode("utf-8").rstrip("=")
    signature = base64.urlsafe_b64encode(hmac.new(signing_key, encoded.encode("utf-8"), hashlib.sha256).digest()).decode("utf-8").rstrip("=")
    return f"{encoded}.{signature}"


def run():
    print("=== Step 1: Allocating Temp Mailbox ===", flush=True)
    client = TempMailClient()
    mailbox = asyncio.run(client.create_address())
    password = strong_password(16)
    print("Address:", mailbox.address, flush=True)

    print("=== Step 2: Solving Turnstile Token via LocalTurnstileSolver ===", flush=True)
    with LocalTurnstileSolver(headless=False, rounds=2, timeout_seconds=45) as solver:
        token = solver.solve_turnstile(
            website_url="https://accounts.x.ai/sign-up?redirect=grok-com",
            website_key="0x4AAAAAAAhr9JGVDZbrZOo0"
        )
    print("Token ready! Len:", len(token) if token else 0, flush=True)

    print("=== Step 3: Launching Camoufox Browser ===", flush=True)
    with Camoufox(headless=False, firefox_user_prefs={"webgl.force-enabled": True}) as browser:
        context = browser.new_context()
        page = context.new_page()

        page.on("request", lambda r: print("REQ:", r.method, r.url, flush=True) if any(k in r.url for k in ("sign-up", "api", "auth", "complete")) else None)
        page.on("response", lambda r: print("RESP:", r.status, r.url, flush=True) if any(k in r.url for k in ("sign-up", "api", "auth", "complete")) else None)

        page.add_init_script("""
            window.__turnstile_callback = null;
            const check = () => {
                if (window.turnstile && !window.turnstile.__hooked) {
                    window.turnstile.__hooked = true;
                    const origRender = window.turnstile.render;
                    window.turnstile.render = function(target, options) {
                        if (options?.callback) {
                            window.__turnstile_callback = options.callback;
                        }
                        return origRender.apply(this, arguments);
                    };
                }
            };
            setInterval(check, 30);
        """)

        print("1. Loading accounts.x.ai...", flush=True)
        page.goto("https://accounts.x.ai/sign-up?redirect=grok-com", wait_until="domcontentloaded", timeout=45000)
        page.wait_for_timeout(2000)

        email_btn = page.locator('button:has-text("Sign up with email")').first
        if email_btn.is_visible():
            email_btn.click()
            page.wait_for_timeout(2000)

        email_input = page.locator('input[type="email"], input[name="email"]').first
        email_input.fill(mailbox.address)
        page.wait_for_timeout(500)
        submit_btn = page.locator('button:has-text("Sign up"), button[type="submit"]').first
        submit_btn.click()
        page.wait_for_timeout(3000)

        code = None
        for i in range(30):
            mails = client._list_mails_sync(mailbox.jwt)
            if mails:
                for m in mails:
                    match = re.search(r"(\d{3})-?(\d{3})", str(m.get("subject") or ""))
                    if not match:
                        match = re.search(r"\b(\d{6})\b", str(m.get("subject") or ""))
                    if match:
                        code = match.group(1) + (match.group(2) if len(match.groups()) > 1 and match.group(2) else "")
                        print("Extracted code:", code, flush=True)
                        break
                if code:
                    break
            page.wait_for_timeout(2000)

        if not code:
            raise RuntimeError("Failed to receive OTP email in time")

        code_input = page.locator('input[name="code"]').first
        code_input.click()
        page.wait_for_timeout(200)
        code_input.press_sequentially(code, delay=100)
        page.wait_for_timeout(2000)

        confirm_btn = page.locator('button:has-text("Confirm email")').first
        if confirm_btn.is_visible():
            try:
                confirm_btn.click(timeout=3000)
            except Exception:
                pass
        page.wait_for_timeout(3000)

        given_name = page.locator('input[name="givenName"]').first
        given_name.wait_for(state="visible", timeout=15000)
        given_name.fill("Alex")
        page.locator('input[name="familyName"]').first.fill("Taylor")
        page.locator('input[name="password"]').first.fill(password)
        page.wait_for_timeout(1000)

        print("Feeding Turnstile Token to React...", flush=True)
        feed_res = page.evaluate("""(tok) => {
            let res = { calledCb: false, setInput: false };
            if (typeof window.__turnstile_callback === 'function') {
                try {
                    window.__turnstile_callback(tok);
                    res.calledCb = true;
                } catch (e) {
                    res.cbError = String(e);
                }
            }
            const input = document.querySelector('input[name="cf-turnstile-response"]');
            if (input) {
                input.value = tok;
                input.dispatchEvent(new Event('input', { bubbles: true }));
                input.dispatchEvent(new Event('change', { bubbles: true }));
                res.setInput = true;
            }
            return res;
        }""", token)
        print("Feed result:", feed_res, flush=True)
        page.wait_for_timeout(1000)

        complete_btn = page.locator('button:has-text("Complete sign up"), button[type="submit"]').first
        print("Clicking Complete sign up...", flush=True)
        complete_btn.click()

        print("Waiting for navigation / redirect...", flush=True)
        for sec in range(1, 25):
            page.wait_for_timeout(1000)
            if "grok.com" in page.url and "accounts.x.ai" not in page.url:
                print(f"SUCCESS: Redirected to {page.url} at second {sec}!", flush=True)
                break

        print("Final URL:", page.url, flush=True)
        cookies = context.cookies()
        cookies_dict = {c["name"]: c["value"] for c in cookies if isinstance(c, dict) and "name" in c and "value" in c}
        sso = next((c.get("value") for c in cookies if c.get("name") == "sso"), None)
        sso_rw = next((c.get("value") for c in cookies if c.get("name") in ("sso-rw", "sso_rw")), None)
        print("Captured cookies:", len(cookies), flush=True)
        print("Has sso:", bool(sso), "Has sso-rw:", bool(sso_rw), flush=True)

        if not (sso or sso_rw):
            raise RuntimeError("Registration succeeded but no sso/sso-rw cookies found!")

        user_id = ""
        try:
            session_info = page.evaluate("() => fetch('https://grok.com/rest/app-chat/session').then(r => r.json()).catch(() => null)")
            if isinstance(session_info, dict):
                user_id = str(session_info.get("session", {}).get("userId") or session_info.get("userId") or "").strip()
                print("Session User ID:", user_id, flush=True)
        except Exception as e:
            print("Session info error:", e, flush=True)

        external_id = user_id or f"grok_web:{mailbox.address}"

        print("=== Step 4: Importing Account to Backend API ===", flush=True)
        admin_session = make_admin_session()
        import_body = {
            "providerId": "grok_web",
            "externalId": external_id,
            "email": mailbox.address,
            "status": "ACTIVE",
            "enabled": True,
            "maxConcurrency": 1,
            "priority": 0,
            "weight": 1,
            "credential": {
                "sso": sso,
                "sso-rw": sso_rw,
                "cookies": cookies_dict
            },
            "metadata": {
                "userId": user_id,
                "tier": "basic",
                "registration_source": "camoufox_automated_turnstile",
                "registered_at": time.strftime("%Y-%m-%dT%H:%M:%SZ")
            },
            "scheduleLifecycle": True
        }

        resp = httpx.post(
            "http://any2api-server:8080/api/admin/v1/accounts/import",
            cookies={"any2api_admin_session": admin_session},
            json=import_body,
            timeout=30.0
        )
        print("Backend Import Status:", resp.status_code, flush=True)
        print("Backend Import Response:", resp.text, flush=True)
        if resp.status_code in (200, 201):
            print("****************************************************************", flush=True)
            print(">>> SUCCESS! Grok Web Account Registered and Activated! <<<", flush=True)
            print("****************************************************************", flush=True)
        else:
            raise RuntimeError(f"Account import failed: {resp.status_code} {resp.text}")


if __name__ == "__main__":
    run()
