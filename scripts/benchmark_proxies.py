"""Comprehensive Benchmark: Self-hosted Oracle Proxy vs CF Dynamic Overseas Proxy
Testing compatibility across all Any2API providers.
"""

import base64
import json
import os
import socket
import subprocess
import tempfile
import time
from dataclasses import dataclass
from typing import Any
from urllib.parse import parse_qs, unquote, urlparse
import httpx


ORACLE_NODES = [
    "vless://2ca9450c-750f-41ca-a6f9-d87178c50d3b@168.138.40.52:8443?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.amazon.com&fp=chrome&pbk=1YFmLexMACEUyK-2Kzu7_6hueIvvrIiF9koCAi46_jo&sid=105c25d4e0c35f0d&type=tcp&headerType=none#Oracle-JP-1",
    "vless://1183f977-ddee-4a55-a026-1f247298efae@168.138.40.214:8443?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.amazon.com&fp=chrome&pbk=xQnRnOIIYhRBEHb36JQ5jDfHVomLITTlEnR-_QtNoy8&sid=49727d3313b8dda0&type=tcp&headerType=none#Oracle-JP-2",
    "vless://c18944ad-3c5a-4b12-9e37-a6cc3a220abc@213.35.105.106:8443?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.amazon.com&fp=chrome&pbk=N05uUbuJzSKyRNLT4rqS0wuNZ7jYtJ0ntqDn6pwhOFs&sid=e444db481ffb64f3&type=tcp&headerType=none#Oracle-JP-3",
    "vless://9c471768-9c27-4622-89c2-c403ef230b7b@217.142.229.2:8443?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.amazon.com&fp=chrome&pbk=yTsCW2NJ22IiJP3QHu84rENjKSHUE5RYIOSNfLLtF0o&sid=119be631d071d5a2&type=tcp&headerType=none#Oracle-JP-4",
]

CF_SUBSCRIPTION_URL = "https://proxy.omnnu.xyz/sub?token=2f7c29086e124598d5f9a5ad17a4657c"

TEST_TARGETS = [
    {
        "provider": "deepseek",
        "name": "DeepSeek (Web Portal)",
        "url": "https://chat.deepseek.com",
        "waf_type": "CloudFront WAF",
        "headers": {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36",
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        },
    },
    {
        "provider": "deepseek",
        "name": "DeepSeek (API Egress)",
        "url": "https://chat.deepseek.com/api/v0/users/current",
        "waf_type": "CloudFront WAF",
        "headers": {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36",
            "Accept": "application/json",
        },
    },
    {
        "provider": "grok_web",
        "name": "Grok (x.ai / Grok Web)",
        "url": "https://grok.com/",
        "waf_type": "Cloudflare Turnstile",
        "headers": {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0",
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        },
    },
    {
        "provider": "grok_web",
        "name": "Grok Auth (accounts.x.ai)",
        "url": "https://accounts.x.ai/",
        "waf_type": "Cloudflare Turnstile",
        "headers": {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0",
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        },
    },
    {
        "provider": "arena",
        "name": "Arena (lmarena.ai)",
        "url": "https://lmarena.ai/",
        "waf_type": "Cloudflare WAF",
        "headers": {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36",
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        },
    },
    {
        "provider": "arena",
        "name": "Arena (chat.lmsys.org)",
        "url": "https://chat.lmsys.org/",
        "waf_type": "Cloudflare WAF",
        "headers": {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36",
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        },
    },
    {
        "provider": "mimo",
        "name": "MiMo (Xiaomi AI Studio)",
        "url": "https://aistudio.xiaomimimo.com",
        "waf_type": "Xiaomi Gateway",
        "headers": {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36",
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        },
    },
    {
        "provider": "minmax",
        "name": "MiniMax (Agent Stream)",
        "url": "https://agent-stream.minimax.io",
        "waf_type": "MiniMax Gateway",
        "headers": {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36",
        },
    },
    {
        "provider": "glm",
        "name": "ChatGLM (Zhipu AI)",
        "url": "https://chatglm.cn/",
        "waf_type": "Domestic Gateway",
        "headers": {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36",
        },
    },
    {
        "provider": "longcat",
        "name": "LongCat (LongCat AI)",
        "url": "https://longcat.chat/",
        "waf_type": "Custom Gateway",
        "headers": {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36",
        },
    },
    {
        "provider": "qwen",
        "name": "Qwen (Tongyi / Aliyun)",
        "url": "https://chat.qwen.ai/",
        "waf_type": "Aliyun Baxia WAF",
        "headers": {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0",
        },
    },
]


@dataclass
class VlessNode:
    uuid: str
    server: str
    port: int
    host: str
    sni: str
    path: str
    fingerprint: str
    security: str
    network: str
    flow: str = ""
    public_key: str = ""
    short_id: str = ""
    raw_tag: str = ""


def parse_vless(value: str) -> VlessNode | None:
    if not value.strip().startswith("vless://"):
        return None
    try:
        tag = ""
        if "#" in value:
            value, tag = value.split("#", 1)
            tag = unquote(tag)
        parsed = urlparse(value.strip())
        query = parse_qs(parsed.query)

        def first(name: str, fallback: str = "") -> str:
            values = query.get(name)
            return unquote(values[0]) if values else fallback

        server = parsed.hostname or ""
        path = first("path", "/")
        return VlessNode(
            uuid=parsed.username or "",
            server=server,
            port=int(parsed.port or 443),
            host=first("host", server),
            sni=first("sni", first("host", server)),
            path=path if path.startswith("/") else "/" + path,
            fingerprint=first("fp", "chrome"),
            security=first("security", "tls"),
            network=first("type", "ws"),
            flow=first("flow"),
            public_key=first("pbk"),
            short_id=first("sid"),
            raw_tag=tag,
        )
    except Exception:
        return None


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("", 0))
        return sock.getsockname()[1]


def singbox_config(node: VlessNode, port: int) -> dict[str, Any]:
    outbound: dict[str, Any] = {
        "type": "vless",
        "tag": "proxy",
        "server": node.server,
        "server_port": node.port,
        "uuid": node.uuid,
    }
    if node.flow:
        outbound["flow"] = node.flow
    if node.network == "ws":
        outbound["transport"] = {
            "type": "ws",
            "path": node.path,
            "headers": {"Host": node.host},
        }
    tls: dict[str, Any] = {
        "enabled": node.security in {"tls", "reality"},
        "server_name": node.sni,
        "utls": {"enabled": True, "fingerprint": node.fingerprint or "chrome"},
    }
    if node.security == "reality":
        tls["reality"] = {
            "enabled": True,
            "public_key": node.public_key,
            "short_id": node.short_id,
        }
    outbound["tls"] = tls
    return {
        "log": {"level": "warn"},
        "inbounds": [
            {
                "type": "mixed",
                "tag": "mixed-in",
                "listen": "127.0.0.1",
                "listen_port": port,
            }
        ],
        "outbounds": [outbound, {"type": "direct", "tag": "direct"}],
        "route": {"rules": [{"outbound": "proxy"}]},
    }


class LocalProxy:
    def __init__(self, node: VlessNode, singbox_bin: str = "/usr/local/bin/sing-box"):
        self.node = node
        self.singbox_bin = singbox_bin
        self.port = free_port()
        self.proc = None
        self.tempdir = None

    def __enter__(self) -> str:
        self.tempdir = tempfile.TemporaryDirectory(prefix="bench-proxy-")
        cfg_path = os.path.join(self.tempdir.name, "config.json")
        with open(cfg_path, "w", encoding="utf-8") as f:
            json.dump(singbox_config(self.node, self.port), f)
        self.proc = subprocess.Popen(
            [self.singbox_bin, "run", "-c", cfg_path],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            if self.proc.poll() is not None:
                raise RuntimeError(f"sing-box exited prematurely with code {self.proc.returncode}")
            try:
                with socket.create_connection(("127.0.0.1", self.port), timeout=0.3):
                    return f"http://127.0.0.1:{self.port}"
            except OSError:
                time.sleep(0.1)
        raise RuntimeError("sing-box port did not open in time")

    def __exit__(self, exc_type, exc_val, exc_tb):
        if self.proc:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=2)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        if self.tempdir:
            self.tempdir.cleanup()


def fetch_cf_nodes() -> list[VlessNode]:
    try:
        resp = httpx.get(CF_SUBSCRIPTION_URL, timeout=15, headers={"User-Agent": "v2rayN/6.23"})
        if resp.status_code != 200:
            print("Failed to fetch CF subscription:", resp.status_code)
            return []
        raw = base64.b64decode(resp.content).decode("utf-8", errors="ignore")
        nodes = []
        for line in raw.splitlines():
            line = line.strip()
            if line.startswith("vless://"):
                n = parse_vless(line)
                if n:
                    nodes.append(n)
        return nodes
    except Exception as e:
        print("Error fetching CF subscription:", e)
        return []


def test_target(client: httpx.Client, target: dict[str, Any]) -> dict[str, Any]:
    url = target["url"]
    headers = target.get("headers", {})
    start = time.monotonic()
    result = {
        "provider": target["provider"],
        "name": target["name"],
        "url": url,
        "status": None,
        "latency_ms": None,
        "waf_blocked": False,
        "turnstile_detected": False,
        "hcaptcha_detected": False,
        "detail": "",
    }
    try:
        resp = client.get(url, headers=headers, timeout=20, follow_redirects=True)
        elapsed = int((time.monotonic() - start) * 1000)
        result["status"] = resp.status_code
        result["latency_ms"] = elapsed
        text_lower = resp.text.lower()

        # Check for CloudFront WAF
        if resp.status_code == 403 and ("cloudfront" in text_lower or "403 forbidden" in text_lower):
            result["waf_blocked"] = True
            result["detail"] = "CloudFront 403 Forbidden"
        # Check for Cloudflare WAF / Turnstile
        elif resp.status_code == 403 and ("just a moment" in text_lower or "challenges.cloudflare.com" in text_lower):
            result["waf_blocked"] = True
            result["turnstile_detected"] = True
            result["detail"] = "Cloudflare 403 Anti-Bot (Just a moment)"
        elif "turnstile" in text_lower or "challenges.cloudflare.com" in text_lower:
            result["turnstile_detected"] = True
            result["detail"] = f"HTTP {resp.status_code} (Turnstile present)"
        elif "hcaptcha" in text_lower or "hcaptcha.com" in text_lower:
            result["hcaptcha_detected"] = True
            result["detail"] = f"HTTP {resp.status_code} (hCaptcha present)"
        else:
            result["detail"] = f"HTTP {resp.status_code} (OK {len(resp.content)} bytes)"
    except httpx.ConnectTimeout:
        result["detail"] = "Connect Timeout (>20s)"
    except httpx.ReadTimeout:
        result["detail"] = "Read Timeout (>20s)"
    except httpx.ConnectError as ce:
        result["detail"] = f"Connect Error: {ce}"
    except Exception as e:
        result["detail"] = f"Error: {type(e).__name__}: {e}"
    return result


def get_egress_ip(client: httpx.Client) -> tuple[str, str, str]:
    try:
        resp = client.get("http://ip-api.com/json", timeout=10)
        if resp.status_code == 200:
            d = resp.json()
            return d.get("query", ""), f"{d.get('country', '')} ({d.get('isp', '')})", d.get("as", "")
    except Exception:
        pass
    try:
        resp = client.get("https://api.ipify.org?format=json", timeout=10)
        if resp.status_code == 200:
            return resp.json().get("ip", ""), "Unknown", "Unknown"
    except Exception:
        pass
    return "Unknown", "Unknown", "Unknown"


def main():
    print("=" * 90)
    print("Any2API Comprehensive Benchmark: Self-hosted Oracle vs CF Dynamic Overseas")
    print("Evaluating compatibility, WAF resistance, latency, and stability across 8 AI providers")
    print("=" * 90)

    # 1. Parse Oracle nodes
    oracle_nodes = [parse_vless(u) for u in ORACLE_NODES if parse_vless(u)]
    print(f"\n[Self-hosted Oracle] Parsed {len(oracle_nodes)} nodes (Dedicated OCI VPS)")

    # 2. Fetch CF nodes
    cf_nodes = fetch_cf_nodes()
    print(f"[CF Dynamic Overseas] Fetched {len(cf_nodes)} nodes (Cloudflare Workers/Edge)")

    pools = [
        ("Self-hosted Oracle", oracle_nodes),  # Test all 4 Oracle nodes
        ("CF Dynamic Overseas", cf_nodes[:4]), # Test 4 CF nodes
    ]

    summary_by_provider = {t["provider"]: {"Oracle": [], "CF": []} for t in TEST_TARGETS}
    raw_results = {}

    for pool_name, nodes in pools:
        short_key = "Oracle" if "Oracle" in pool_name else "CF"
        print(f"\n=======================================================")
        print(f">>> Testing Pool: {pool_name} ({len(nodes)} nodes) <<<")
        print(f"=======================================================")
        pool_records = []
        for i, node in enumerate(nodes):
            tag = getattr(node, "raw_tag", "") or f"Node-{i+1}"
            print(f"\n--- Testing [{pool_name}] Node {i+1}/{len(nodes)}: {tag} ({node.server}:{node.port}) ---")
            try:
                with LocalProxy(node) as proxy_url:
                    with httpx.Client(proxy=proxy_url, timeout=25) as client:
                        ip, location, asn = get_egress_ip(client)
                        print(f"  Egress IP: {ip} | {location} | ASN: {asn}")

                        node_tests = []
                        for target in TEST_TARGETS:
                            res = test_target(client, target)
                            status_str = f"HTTP {res['status']}" if res['status'] else "FAILED"
                            latency_str = f"{res['latency_ms']}ms" if res['latency_ms'] else "---"
                            flag = ""
                            if res["waf_blocked"]:
                                flag = " [WAF BLOCKED]"
                            elif res["turnstile_detected"]:
                                flag = " [TURNSTILE]"
                            elif res["hcaptcha_detected"]:
                                flag = " [HCAPTCHA]"
                            print(f"  [{res['provider']:<10}] {res['name']:<28}: {status_str:<10} {latency_str:<8} | {res['detail']}{flag}")
                            node_tests.append(res)
                            summary_by_provider[res["provider"]][short_key].append(res)
                        pool_records.append({
                            "node": tag,
                            "server": node.server,
                            "ip": ip,
                            "location": location,
                            "asn": asn,
                            "tests": node_tests,
                        })
            except Exception as e:
                print(f"  Failed to start or connect to node: {e}")
        raw_results[pool_name] = pool_records

    # Print Aggregate Statistics
    print("\n" + "=" * 90)
    print("AGGREGATE COMPATIBILITY & LATENCY SUMMARY BY PROVIDER")
    print("=" * 90)
    print(f"{'Provider':<12} | {'Self-hosted Oracle (OCI)':<35} | {'CF Dynamic Overseas (Edge)':<35}")
    print("-" * 90)

    for p in ["deepseek", "grok_web", "arena", "mimo", "minmax", "glm", "longcat", "qwen"]:
        stats = {}
        for short_key in ["Oracle", "CF"]:
            tests = summary_by_provider[p][short_key]
            total = len(tests)
            success = [t for t in tests if t["status"] in (200, 202, 404)]  # 404 is API not found but connection OK
            latencies = [t["latency_ms"] for t in success if t["latency_ms"] is not None]
            avg_lat = int(sum(latencies) / len(latencies)) if latencies else 0
            blocked = [t for t in tests if t["waf_blocked"]]
            stats[short_key] = {
                "total": total,
                "success_count": len(success),
                "avg_lat": avg_lat,
                "blocked_count": len(blocked),
            }
        
        oracle_str = f"{stats['Oracle']['success_count']}/{stats['Oracle']['total']} OK, {stats['Oracle']['avg_lat']}ms (Block: {stats['Oracle']['blocked_count']})"
        cf_str = f"{stats['CF']['success_count']}/{stats['CF']['total']} OK, {stats['CF']['avg_lat']}ms (Block: {stats['CF']['blocked_count']})"
        print(f"{p:<12} | {oracle_str:<35} | {cf_str:<35}")

    output_path = "/tmp/proxy_benchmark_results.json"
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump({"summary": summary_by_provider, "raw": raw_results}, f, ensure_ascii=False, indent=2)
    print(f"\nDetailed metrics saved to {output_path}")


if __name__ == "__main__":
    main()
