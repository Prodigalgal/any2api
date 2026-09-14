import hashlib
import json
import os
import re
import urllib.error
import urllib.request

BASE = "http://any2api-server:8080"


def http(url, body=None, method=None, headers=None, timeout=180):
    data = None if body is None else (
        json.dumps(body).encode() if isinstance(body, (dict, list)) else body
    )
    method = method or ("POST" if data is not None else "GET")
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    for key, value in (headers or {}).items():
        req.add_header(key, value)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
            return resp.status, raw, dict(resp.headers)
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read(), dict(exc.headers)


def solve_pow(token: str, difficulty: int) -> int:
    full_bytes = difficulty // 8
    remaining = difficulty % 8
    nonce = 0
    while True:
        digest = hashlib.sha256(f"{token}:{nonce}".encode()).digest()
        valid = True
        for index in range(full_bytes):
            if digest[index] != 0:
                valid = False
                break
        if valid and remaining > 0:
            if digest[full_bytes] & (0xFF << (8 - remaining)) != 0:
                valid = False
        if valid:
            return nonce
        nonce += 1


def main():
    status, raw, _ = http(f"{BASE}/api/admin/v1/login-challenge")
    print("challenge", status, raw.decode()[:240])
    challenge = json.loads(raw)
    token = challenge.get("challengeToken") or challenge.get("token")
    difficulty = int(challenge["difficulty"])
    question = challenge.get("expression") or challenge.get("question") or ""
    print("question", question, "difficulty", difficulty)
    match = re.search(r"(\d+)\s*([+x×*])\s*(\d+)", question)
    if not match:
        raise SystemExit(f"cannot parse question: {question}")
    left = int(match.group(1))
    op = match.group(2)
    right = int(match.group(3))
    answer = left * right if op in {"x", "×", "*"} else left + right
    nonce = solve_pow(token, difficulty)
    print("answer", answer, "nonce", nonce)

    password = os.environ["ADMIN_PASSWORD"]
    status, raw, headers = http(
        f"{BASE}/api/admin/v1/session",
        {
            "username": "admin",
            "password": password,
            "challengeToken": token,
            "mathAnswer": str(answer),
            "powNonce": nonce,
        },
        method="POST",
    )
    print("login", status, raw.decode()[:300])
    cookie_header = headers.get("Set-Cookie") or headers.get("set-cookie") or ""
    cookie = cookie_header.split(";")[0]
    print("cookie", cookie[:60] if cookie else None)
    if not cookie:
        raise SystemExit("no session cookie")

    for provider, model in [
        ("glm", "glm-5.2"),
        ("glm", "glm-4-flash"),
        ("glm", "glm-4.6v"),
        ("arena", "Max"),
        ("minmax", "MiniMax-M3"),
        ("qwen", "qwen3.7-plus"),
    ]:
        status, raw, _ = http(
            f"{BASE}/api/admin/v1/models/probe",
            {"providerId": provider, "modelId": model},
            method="POST",
            headers={"Cookie": cookie},
            timeout=180,
        )
        print("probe", provider, model, status, raw.decode()[:240])


if __name__ == "__main__":
    main()
