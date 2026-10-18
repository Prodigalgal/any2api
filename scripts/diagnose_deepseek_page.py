"""Diagnose DeepSeek registration page, network calls, and hCaptcha DOM elements.
"""

import json
import time
from camoufox.sync_api import Camoufox
import sys
sys.path.insert(0, "/tmp")
from benchmark_proxies import LocalProxy, parse_vless, ORACLE_NODES, fetch_cf_nodes

proxy_type = "oracle"
if len(sys.argv) > 1 and sys.argv[1].lower() == "cf":
    proxy_type = "cf"

if proxy_type == "cf":
    cf_nodes = fetch_cf_nodes()
    selected_node = cf_nodes[0]
    print(f"Launching Camoufox with CF Dynamic Proxy ({selected_node.raw_tag or selected_node.server})...")
else:
    selected_node = parse_vless(ORACLE_NODES[0])
    print(f"Launching Camoufox with Oracle Proxy ({selected_node.raw_tag or selected_node.server})...")

with LocalProxy(selected_node) as proxy_url:
    print(f"Proxy ready at {proxy_url}")
    with Camoufox(
        headless=False,
        geoip=True,
        humanize=True,
        proxy={"server": proxy_url},
    ) as browser:
        page = browser.new_page()

        def on_req(req):
            if any(k in req.url for k in ("api", "captcha", "hcaptcha", "user")):
                print(f"[REQ] {req.method} {req.url}")
                if "create_email_verification_code" in req.url or "create_guest_challenge" in req.url:
                    print(f"      [HEADERS] {dict(req.headers)}")
                    try:
                        print(f"      [POST_DATA] {req.post_data}")
                    except Exception:
                        try:
                            print(f"      [POST_DATA_RAW] {bytes(req.post_data_buffer)[:100]}")
                        except Exception:
                            pass

        def on_resp(resp):
            if any(k in resp.url for k in ("api", "captcha", "hcaptcha", "user")):
                try:
                    j = resp.json()
                    text = json.dumps(j, ensure_ascii=False)[:400]
                except Exception:
                    try:
                        text = resp.text()[:400].replace("\n", " ")
                    except Exception:
                        try:
                            b = resp.body()
                            text = f"<bytes {len(b)}: {b[:50]}>"
                        except Exception:
                            text = "<binary>"
                print(f"[RESP] {resp.status} {resp.url} -> {text}")

        page.on("request", on_req)
        page.on("response", on_resp)

        page.on("console", lambda msg: print(f"[CONSOLE {msg.type}] {msg.text}"))
        page.on("pageerror", lambda err: print(f"[PAGEERROR] {err}"))

        print("Navigating to https://chat.deepseek.com/sign_up...")
        page.goto("https://chat.deepseek.com/sign_up", wait_until="domcontentloaded", timeout=45000)
        page.wait_for_timeout(3000)

        page.screenshot(path="/tmp/deepseek_page_loaded.png")
        print("Page loaded screenshot saved to /tmp/deepseek_page_loaded.png")
        print(f"Page title: {page.title()}")

        # Print all frames
        print(f"Total frames: {len(page.frames)}")
        for idx, f in enumerate(page.frames):
            print(f"  Frame {idx}: name='{f.name}', url='{f.url}'")

        # Find email input
        selector = 'input[type="email"], input[placeholder*="email" i], input[placeholder*="mail" i], input[placeholder*="メール" i], input[placeholder*="邮" i]'
        try:
            page.wait_for_selector(selector, timeout=15000)
        except Exception:
            pass
        email_input = page.locator(selector).first
        if email_input.count() and email_input.is_visible():
            print("Found email input, typing test email...")
            email_input.fill("bench_test_user@omnnu.xyz")
            page.wait_for_timeout(1000)
        else:
            print("Email input not found!")

        # Find send code button
        send_btn = page.locator('[role="button"]:has-text("Send code"), button:has-text("Send code"), button:has-text("发送验证码"), [role="button"]:has-text("发送验证码"), [role="button"]:has-text("コードを送信"), button:has-text("コードを送信")').first
        if send_btn.count() and send_btn.is_visible():
            print("Found send code button, clicking...")
            send_btn.click()
            page.wait_for_timeout(5000)
        else:
            print("Send code button not found!")

        print("\n--- After clicking Send code ---")
        print(f"Total frames after click: {len(page.frames)}")
        for idx, f in enumerate(page.frames):
            print(f"  Frame {idx}: name='{f.name}', url='{f.url}'")
            # Try to see if there is any visible element in frame
            try:
                body_text = f.locator("body").inner_text()[:200].replace("\n", " ")
                print(f"    Body text: {body_text}")
            except Exception as e:
                print(f"    Error getting body text: {e}")

        print("\n--- DOM iframes ---")
        iframes = page.locator("iframe").all()
        print(f"Total iframes in DOM: {len(iframes)}")
        for idx, ifr in enumerate(iframes):
            src = ifr.get_attribute("src") or "<no src>"
            vis = ifr.is_visible()
            box = ifr.bounding_box()
            print(f"  DOM iframe {idx}: src={src[:120]}, visible={vis}, box={box}")

        page.screenshot(path="/tmp/deepseek_after_send.png")
        print("Saved screenshot to /tmp/deepseek_after_send.png")
