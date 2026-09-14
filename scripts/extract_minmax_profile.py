import re
import urllib.request
from urllib.parse import urljoin, urlparse

base = "https://agent.minimax.io"
req = urllib.request.Request(
    base,
    headers={
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
    },
)
html = urllib.request.urlopen(req, timeout=30).read().decode("utf-8", "replace")
print("html_len", len(html))
print("html_head", html[:400].replace("\n", " "))
scripts = re.findall(
    r'<script[^>]+src=["\']([^"\']+\.js(?:\?[^"\']*)?)["\']', html, re.I
)
print("scripts", len(scripts))
for i, s in enumerate(scripts[:25]):
    print(i, s[:160])

allowed = {"agent.minimax.io", "agent-stream.minimax.io", "cdn.minimax.io", "sf16-va.tiktokcdn.com"}
sig = yy = ver = ""
for src in scripts[:40]:
    url = urljoin(base, src)
    host = urlparse(url).hostname
    print("try", host, url[:140])
    if host not in allowed and host and not host.endswith(".minimax.io"):
        continue
    try:
        body = (
            urllib.request.urlopen(
                urllib.request.Request(
                    url,
                    headers={
                        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
                    },
                ),
                timeout=30,
            )
            .read()
            .decode("utf-8", "replace")
        )
    except Exception as exc:
        print(" fail", exc)
        continue
    print(" fetched", len(body), "x-signature" in body, "hasSearchParamsPath" in body, "version_code" in body)
    if "x-signature" in body:
        idx = body.find("x-signature")
        print("  x-signature context:", body[max(0, idx - 80) : idx + 220].replace("\n", " ")[:300])
    if "hasSearchParamsPath" in body:
        idx = body.find("hasSearchParamsPath")
        print("  hasSearchParamsPath context:", body[max(0, idx - 40) : idx + 220].replace("\n", " ")[:300])
    if "version_code" in body:
        m = re.search(r"version_code\s*:\s*[\"']([0-9]{3,12})[\"']", body)
        print("  version_code match", m.group(1) if m else None)
    if not sig:
        offset = 0
        while True:
            marker = body.find("x-signature", offset)
            if marker < 0:
                break
            first = body.find("${", marker)
            first_end = body.find("}", first + 2) if first >= 0 else -1
            second = body.find("${", first_end + 1) if first_end >= 0 else -1
            if first >= 0 and first - marker < 400 and first_end >= 0 and second >= 0:
                cand = body[first_end + 1 : second]
                print("  sig cand", repr(cand[:80]))
                if (
                    6 <= len(cand) <= 80
                    and not any(ch in cand for ch in "${}`")
                    and not cand.isspace()
                ):
                    sig = cand
                    break
            offset = marker + 1
    if not yy:
        offset = 0
        while True:
            marker = body.find("hasSearchParamsPath", offset)
            if marker < 0:
                break
            call = body.find("toString())}", marker)
            end = body.find("`", call) if call >= 0 else -1
            brace = body.find("}", call) if call >= 0 else -1
            if call >= 0 and call - marker < 900 and end > call and brace >= 0:
                cand = body[brace + 1 : end]
                print("  yy cand", repr(cand[:80]))
                if (
                    2 <= len(cand) <= 32
                    and not any(ch in cand for ch in "${}`")
                    and not cand.isspace()
                ):
                    yy = cand
                    break
            offset = marker + 1
    if not ver:
        match = re.search(r'version_code\s*:\s*["\']([0-9]{3,12})["\']', body)
        if match:
            ver = match.group(1)
    if sig and yy and ver:
        break
print("RESULT", {"signature_salt": sig, "yy_salt": yy, "version_code": ver})
