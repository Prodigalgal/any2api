from __future__ import annotations

import base64
import hashlib
import json
import random
import re
import secrets
import socket
import subprocess
import tempfile
import threading
import time
from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, unquote, urljoin, urlparse

import httpx
import redis

from ..config import settings

_local_node_lock = threading.Lock()
_local_nodes: set[str] = set()


@dataclass(frozen=True)
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


@dataclass(frozen=True)
class DirectNode:
    url: str


ProxyNode = VlessNode | DirectNode


def proxy_parameters(payload: dict[str, Any]) -> dict[str, Any]:
    config = settings()
    pool = payload.get("proxy_pool")
    if isinstance(pool, dict):
        mode = str(pool.get("mode") or "").upper()
        if mode == "SUBSCRIPTION_URL":
            return {
                "explicit_url": "",
                "dynamic": True,
                "subscription_url": str(pool.get("subscription_url") or "").strip(),
                "node_urls": None,
                "affinity_key": str(payload.get("proxy_affinity_key") or "").strip(),
                "strict_affinity": bool(payload.get("strict_proxy_affinity", False)),
            }
        if mode == "NODE_LIST":
            raw_nodes = pool.get("nodes")
            nodes = (
                [str(value).strip() for value in raw_nodes] if isinstance(raw_nodes, list) else []
            )
            return {
                "explicit_url": "",
                "dynamic": True,
                "subscription_url": "",
                "node_urls": nodes,
                "affinity_key": str(payload.get("proxy_affinity_key") or "").strip(),
                "strict_affinity": bool(payload.get("strict_proxy_affinity", False)),
            }
        raise ValueError("unsupported proxy pool mode")
    return {
        "explicit_url": str(payload.get("proxy_url") or config.registration_proxy_url).strip(),
        "dynamic": bool(payload.get("dynamic_proxy", config.registration_use_dynamic_proxy)),
        "subscription_url": "",
        "node_urls": None,
        "affinity_key": str(payload.get("proxy_affinity_key") or "").strip(),
        "strict_affinity": bool(payload.get("strict_proxy_affinity", False)),
    }


@contextmanager
def proxy_lease(
    *,
    explicit_url: str,
    dynamic: bool,
    check_url: str,
    subscription_url: str = "",
    node_urls: list[str] | None = None,
    affinity_key: str = "",
    strict_affinity: bool = False,
    reject_redirect_hosts: tuple[str, ...] = (),
) -> Iterator[str]:
    if explicit_url:
        yield explicit_url
        return
    if not dynamic:
        yield ""
        return
    subscription = subscription_url.strip() or settings().dynamic_proxy_subscription_url.strip()
    nodes = _parse_nodes(node_urls or []) if node_urls is not None else []
    if not nodes:
        if not subscription:
            raise RuntimeError("dynamic proxy subscription or node list is not configured")
        nodes = _fetch_nodes(subscription)
    nodes = _ordered_nodes(nodes, affinity_key)
    attempt_limit = 1 if affinity_key and strict_affinity else settings().dynamic_proxy_max_attempts
    errors: list[str] = []
    for node in nodes[:attempt_limit]:
        reservation = _NodeReservation.acquire(node)
        if reservation is None:
            errors.append("NodeBusy")
            continue
        lease = _SingBoxLease(node) if isinstance(node, VlessNode) else None
        try:
            proxy_url = lease.__enter__() if lease else node.url
            response, assets_ready = _connectivity(proxy_url, check_url)
            location = response.headers.get("location", "")
        except (OSError, RuntimeError, httpx.HTTPError) as error:
            if lease:
                lease.__exit__()
            reservation.release()
            errors.append(type(error).__name__)
            continue
        if (
            response.status_code >= 500
            or _rejected_redirect(location, reject_redirect_hosts)
            or not assets_ready
        ):
            if lease:
                lease.__exit__()
            reservation.release()
            errors.append(f"http_{response.status_code}")
            continue
        try:
            yield proxy_url
        finally:
            if lease:
                lease.__exit__()
            reservation.release()
        return
    raise RuntimeError("CF dynamic proxy has no usable overseas node: " + ",".join(errors))


class _SingBoxLease:
    def __init__(self, node: VlessNode) -> None:
        self.node = node
        self.process: subprocess.Popen[bytes] | None = None
        self.directory: tempfile.TemporaryDirectory[str] | None = None
        self.port = _free_port()

    def __enter__(self) -> str:
        binary = Path(settings().dynamic_proxy_singbox_path)
        if not binary.is_file():
            raise RuntimeError("sing-box binary is unavailable")
        self.directory = tempfile.TemporaryDirectory(prefix="any2api-proxy-")
        config_path = Path(self.directory.name) / "config.json"
        config_path.write_text(json.dumps(_singbox_config(self.node, self.port)), encoding="utf-8")
        self.process = subprocess.Popen(
            [str(binary), "run", "-c", str(config_path)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise RuntimeError("sing-box exited during startup")
            try:
                with socket.create_connection(("127.0.0.1", self.port), timeout=0.3):
                    return f"http://127.0.0.1:{self.port}"
            except OSError:
                time.sleep(0.2)
        raise RuntimeError("sing-box did not bind its local port")

    def __exit__(self, *_: object) -> None:
        if self.process is not None:
            self.process.terminate()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=3)
        if self.directory is not None:
            self.directory.cleanup()


class _NodeReservation:
    def __init__(self, client: redis.Redis | None, key: str, token: str) -> None:
        self.client = client
        self.key = key
        self.token = token

    @classmethod
    def acquire(cls, node: ProxyNode) -> _NodeReservation | None:
        digest = hashlib.sha256(_node_identity(node).encode()).hexdigest()
        key = f"any2api:proxy-node:{digest}"
        token = secrets.token_urlsafe(24)
        if not settings().dynamic_proxy_distributed_leases:
            with _local_node_lock:
                if key in _local_nodes:
                    return None
                _local_nodes.add(key)
            return cls(None, key, token)
        client = redis.Redis.from_url(settings().redis_url, decode_responses=True)
        acquired = client.set(
            key,
            token,
            nx=True,
            ex=max(60, settings().dynamic_proxy_lease_seconds),
        )
        return cls(client, key, token) if acquired else None

    def release(self) -> None:
        if self.client is None:
            with _local_node_lock:
                _local_nodes.discard(self.key)
            return
        self.client.eval(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
            "return redis.call('del', KEYS[1]) else return 0 end",
            1,
            self.key,
            self.token,
        )


def _fetch_nodes(url: str) -> list[ProxyNode]:
    response = httpx.get(url, timeout=40, headers={"user-agent": settings().provider_user_agent})
    response.raise_for_status()
    body = response.text.strip()
    if not body.startswith("vless://"):
        try:
            body = base64.b64decode(body + "=" * (-len(body) % 4)).decode()
        except (ValueError, UnicodeDecodeError):
            pass
    nodes = _parse_nodes(body.splitlines())
    if not nodes:
        raise RuntimeError("CF dynamic proxy subscription contains no VLESS nodes")
    return nodes


def _parse_nodes(values: list[str] | tuple[str, ...] | Any) -> list[ProxyNode]:
    nodes: list[ProxyNode] = []
    for raw in values:
        value = str(raw).strip()
        if not value or value.startswith("#"):
            continue
        node = _parse_vless(value)
        if node is not None:
            nodes.append(node)
            continue
        parsed = urlparse(value)
        if parsed.scheme.lower() in {"http", "https", "socks5", "socks5h"} and parsed.hostname:
            nodes.append(DirectNode(value))
    if not nodes:
        raise RuntimeError("proxy pool contains no supported nodes")
    return nodes


def _ordered_nodes(nodes: list[ProxyNode], affinity_key: str) -> list[ProxyNode]:
    if not affinity_key:
        shuffled = list(nodes)
        random.SystemRandom().shuffle(shuffled)
        return shuffled
    if len(affinity_key) > 256 or not re.fullmatch(r"[A-Za-z0-9._:-]+", affinity_key):
        raise ValueError("proxy affinity key is invalid")
    return sorted(
        nodes,
        key=lambda node: hashlib.sha256(
            f"{affinity_key}\0{_node_identity(node)}".encode()
        ).digest(),
        reverse=True,
    )


def _node_identity(node: ProxyNode) -> str:
    return (
        f"{node.uuid}@{node.server}:{node.port}{node.path}"
        if isinstance(node, VlessNode)
        else node.url
    )


def _connectivity(proxy_url: str, check_url: str) -> tuple[httpx.Response, bool]:
    with httpx.Client(proxy=proxy_url, timeout=35, follow_redirects=False) as client:
        response = client.get(check_url, headers={"user-agent": settings().provider_user_agent})
        content_type = response.headers.get("content-type", "")
        if "text/html" not in content_type:
            return response, True
        sources = re.findall(
            r'<script[^>]+src=["\']([^"\']+\.js(?:\?[^"\']*)?)["\']',
            response.text,
            re.IGNORECASE,
        )
        if not sources:
            return response, True
        for source in sources[:3]:
            asset = client.get(
                urljoin(str(response.url), source),
                headers={"user-agent": settings().provider_user_agent},
            )
            if asset.is_success and len(asset.content) > 1024:
                return response, True
        return response, False


def _rejected_redirect(location: str, hosts: tuple[str, ...]) -> bool:
    hostname = (urlparse(location).hostname or "").lower()
    return any(hostname == host.lower() or hostname.endswith("." + host.lower()) for host in hosts)


def _parse_vless(value: str) -> VlessNode | None:
    if not value.strip().startswith("vless://"):
        return None
    try:
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
        )
    except (TypeError, ValueError):
        return None


def _singbox_config(node: VlessNode, port: int) -> dict[str, Any]:
    if node.network not in {"ws", "tcp"}:
        raise RuntimeError("VLESS proxy node transport is unsupported")
    if node.security == "reality" and (not node.public_key or not node.short_id):
        raise RuntimeError("VLESS REALITY node is missing public key or short id")
    tls: dict[str, Any] = {
        "enabled": node.security in {"tls", "reality"},
        "server_name": node.sni or node.host,
        "insecure": False,
        "utls": {"enabled": True, "fingerprint": node.fingerprint or "chrome"},
    }
    if node.security == "reality":
        tls["reality"] = {
            "enabled": True,
            "public_key": node.public_key,
            "short_id": node.short_id,
        }
    outbound: dict[str, Any] = {
        "type": "vless",
        "tag": "proxy",
        "server": node.server,
        "server_port": node.port,
        "uuid": node.uuid,
        "packet_encoding": "xudp",
        "tls": tls,
    }
    if node.flow:
        outbound["flow"] = node.flow
    if node.network == "ws":
        outbound["transport"] = {
            "type": "ws",
            "path": node.path,
            "headers": {"Host": node.host},
        }
    return {
        "log": {"level": "warn", "timestamp": True},
        "inbounds": [
            {
                "type": "mixed",
                "tag": "mixed-in",
                "listen": "127.0.0.1",
                "listen_port": port,
            }
        ],
        "outbounds": [outbound, {"type": "direct", "tag": "direct"}],
        "route": {"rules": [{"action": "sniff"}], "final": "proxy", "auto_detect_interface": True},
    }


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as handle:
        handle.bind(("127.0.0.1", 0))
        return int(handle.getsockname()[1])
