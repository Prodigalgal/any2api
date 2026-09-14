#!/usr/bin/env python3
"""Full-chain provider smoke against a running any2api server.

Run from a host that can reach the server (cluster pod or port-forward).
Usage:
  python scripts/fullchain_smoke.py --base-url http://any2api-server:8080 \
    --api-key sk-... --providers deepseek,mimo --modes api,runtime,auto
"""

from __future__ import annotations

import argparse
import base64
import json
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Any

# 1x1 red PNG
PNG_1X1 = base64.b64encode(
    bytes.fromhex(
        "89504e470d0a1a0a0000000d494844520000000100000001080200000090"
        "7753de0000000c4944415408d763f8cfc000000301010018dd8db0000000"
        "0049454e44ae426082"
    )
).decode()

# 32x32 gray PNG via minimal IHDR+IDAT (solid color)
PNG_32 = base64.b64encode(
    bytes.fromhex(
        "89504e470d0a1a0a0000000d4948445200000020000000200802000000fc"
        "18ed000000154944415478016360f8cfc0000003010100"
        "18dd8db0d60000000049454e44ae426082"
    )
).decode()


@dataclass
class CaseResult:
    provider: str
    case: str
    mode: str
    ok: bool
    detail: str
    duration_s: float
    status: int | None = None


@dataclass
class Report:
    results: list[CaseResult] = field(default_factory=list)

    def add(self, result: CaseResult) -> None:
        self.results.append(result)
        mark = "PASS" if result.ok else "FAIL"
        print(
            f"[{mark}] {result.provider:10} {result.case:28} {result.mode:8} "
            f"{result.duration_s:6.1f}s status={result.status} {result.detail[:160]}",
            flush=True,
        )

    def summary(self) -> int:
        total = len(self.results)
        passed = sum(1 for r in self.results if r.ok)
        failed = total - passed
        print(f"\n=== SUMMARY {passed}/{total} passed, {failed} failed ===")
        for r in self.results:
            if not r.ok:
                print(f"  FAIL {r.provider}/{r.case}/{r.mode}: {r.detail[:200]}")
        return 0 if failed == 0 else 1


def request_json(
    url: str,
    *,
    method: str = "GET",
    api_key: str | None = None,
    body: dict[str, Any] | None = None,
    timeout: float = 180.0,
) -> tuple[int, Any, float]:
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if api_key:
        req.add_header("Authorization", f"Bearer {api_key}")
    started = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
            status = resp.status
    except urllib.error.HTTPError as exc:
        raw = exc.read()
        status = exc.code
    duration = time.time() - started
    text = raw.decode("utf-8", errors="replace")
    try:
        parsed: Any = json.loads(text) if text else None
    except json.JSONDecodeError:
        parsed = text
    return status, parsed, duration


def extract_text(payload: Any) -> str:
    if not isinstance(payload, dict):
        return str(payload)[:200]
    choices = payload.get("choices") or []
    if choices:
        message = choices[0].get("message") or {}
        content = message.get("content")
        if isinstance(content, str):
            return content[:200]
        if isinstance(content, list):
            parts = []
            for item in content:
                if isinstance(item, dict) and item.get("type") == "text":
                    parts.append(str(item.get("text") or ""))
            return "".join(parts)[:200]
    if "output_text" in payload:
        return str(payload.get("output_text"))[:200]
    return json.dumps(payload, ensure_ascii=False)[:200]


def chat_body(model: str, stream: bool, with_image: bool = False) -> dict[str, Any]:
    content: list[dict[str, Any]] = [{"type": "text", "text": "Reply with the single word: pong"}]
    if with_image:
        content.append(
            {
                "type": "image_url",
                "image_url": {"url": f"data:image/png;base64,{PNG_32}"},
            }
        )
    return {
        "model": model,
        "stream": stream,
        "messages": [{"role": "user", "content": content if with_image else content[0]["text"]}],
        "max_tokens": 64,
    }


def run_chat(
    report: Report,
    base: str,
    api_key: str,
    provider: str,
    model: str,
    mode: str,
    case: str,
    stream: bool,
    with_image: bool,
    path_prefix: str,
) -> None:
    url = f"{base}{path_prefix}/chat/completions"
    body = chat_body(model, stream, with_image)
    # transportMode is applied via dedicated keys; for smoke we use the key's mode
    status, payload, duration = request_json(url, method="POST", api_key=api_key, body=body)
    detail = extract_text(payload) if status == 200 else json.dumps(payload, ensure_ascii=False)[:200]
    ok = status == 200 and (
        (not stream and bool(detail) and "error" not in detail.lower())
        or (stream and ("[DONE]" in str(payload) or "data:" in str(payload) or status == 200))
    )
    if stream and status == 200:
        # SSE comes back as text when Accept is default; treat HTTP 200 as pass if body non-empty
        ok = bool(str(payload).strip())
        detail = f"sse_bytes={len(str(payload))} head={str(payload)[:80]!r}"
    report.add(CaseResult(provider, case, mode, ok, detail, duration, status))


def run_models(report: Report, base: str, api_key: str, provider: str, mode: str) -> None:
    url = f"{base}/{provider}/v1/models"
    status, payload, duration = request_json(url, api_key=api_key)
    count = 0
    if isinstance(payload, dict):
        count = len(payload.get("data") or [])
    ok = status == 200 and count > 0
    report.add(CaseResult(provider, "models", mode, ok, f"count={count}", duration, status))


def run_keepalive_internal(
    report: Report,
    base: str,
    internal_token: str,
    provider: str,
    account_id: str,
    mode: str,
) -> None:
    url = f"{base.replace(':8080', ':8090')}/internal/v1/providers/{provider}/execute"
    body = {
        "operation": "keepalive",
        "payload": {"account_id": account_id},
        "context": {
            "correlation_id": f"smoke-keepalive-{provider}-{int(time.time())}",
            "aggregate_type": "account",
            "aggregate_id": account_id,
            "attempt": 1,
        },
    }
    req = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", f"Bearer {internal_token}")
    started = time.time()
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
            raw = resp.read()
            status = resp.status
    except urllib.error.HTTPError as exc:
        raw = exc.read()
        status = exc.code
    duration = time.time() - started
    text = raw.decode("utf-8", errors="replace")
    ok = status == 200
    try:
        parsed = json.loads(text)
        stage = parsed.get("stage") or parsed.get("status")
        ok = ok and str(stage).upper() in {"SUCCEEDED", "SUCCESS", "OK"} or (
            status == 200 and parsed.get("ok") is True
        )
        detail = json.dumps(parsed, ensure_ascii=False)[:200]
    except json.JSONDecodeError:
        detail = text[:200]
    report.add(CaseResult(provider, "keepalive", mode, bool(ok), detail, duration, status))


DEFAULT_MODELS = {
    "deepseek": {"text": "default", "image": None},
    "glm": {"text": "glm-5.2", "image": "glm-4.6v"},
    "longcat": {"text": "longcat-pro", "image": "longcat-pro"},
    "mimo": {"text": "mimo-v2.5-pro", "image": "mimo-v2.5"},
    "minmax": {"text": "MiniMax-M3", "image": "MiniMax-M3"},
    "qwen": {"text": "qwen3.7-plus", "image": "qwen3.7-plus"},
    "arena": {"text": "Max", "image": "claude-sonnet-4-6"},
}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://any2api-server:8080")
    parser.add_argument("--api-key", required=True)
    parser.add_argument("--internal-token", default="")
    parser.add_argument("--providers", default="deepseek,mimo,longcat,glm,minmax,qwen,arena")
    parser.add_argument("--skip-image", action="store_true")
    parser.add_argument("--skip-stream", action="store_true")
    parser.add_argument("--skip-keepalive", action="store_true")
    args = parser.parse_args()

    providers = [p.strip() for p in args.providers.split(",") if p.strip()]
    report = Report()
    base = args.base_url.rstrip("/")

    health_status, health, _ = request_json(f"{base}/healthz", timeout=10)
    print(f"healthz status={health_status} body={health}")

    for provider in providers:
        models = DEFAULT_MODELS.get(provider)
        if not models:
            continue
        run_models(report, base, args.api_key, provider, "auto")

        text_model = models["text"]
        image_model = models["image"]
        prefix = f"/{provider}/v1"

        run_chat(report, base, args.api_key, provider, text_model, "auto", "chat_text_collect", False, False, prefix)
        if not args.skip_stream:
            run_chat(report, base, args.api_key, provider, text_model, "auto", "chat_text_sse", True, False, prefix)
        if image_model and not args.skip_image:
            run_chat(report, base, args.api_key, provider, image_model, "auto", "chat_image_collect", False, True, prefix)
            if not args.skip_stream:
                run_chat(report, base, args.api_key, provider, image_model, "auto", "chat_image_sse", True, True, prefix)

    return report.summary()


if __name__ == "__main__":
    sys.exit(main())
