"""Authenticated HTTP proxy with a persistent IPv6 source address per node."""

from __future__ import annotations

import argparse
import asyncio
import base64
import hmac
import ipaddress
import json
import logging
import os
import socket
from contextlib import suppress
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit

logger = logging.getLogger("any2api_ipv6_proxy")
HEADER_LIMIT = 16 * 1024
ALLOWED_PORTS = frozenset({80, 443})


class ProxyError(Exception):
    def __init__(self, status: int, reason: str) -> None:
        super().__init__(reason)
        self.status = status
        self.reason = reason


@dataclass(frozen=True)
class ProxyUser:
    password: str
    source_ipv6: str


def authenticate(headers: list[bytes], users: dict[str, ProxyUser]) -> ProxyUser:
    supplied = [
        line.partition(b":")[2].strip()
        for line in headers
        if line.lower().startswith(b"proxy-authorization:")
    ]
    if len(supplied) != 1:
        raise ProxyError(407, "Proxy Authentication Required")
    try:
        scheme, token = supplied[0].split(None, 1)
        if scheme.lower() != b"basic":
            raise ValueError("unsupported authentication")
        username, password = base64.b64decode(token, validate=True).decode("utf-8").split(":", 1)
        user = users.get(username)
        expected = user.password if user is not None else "invalid-authentication"
        matches = hmac.compare_digest(password.encode(), expected.encode())
        if user is None or not matches:
            raise ValueError("authentication failed")
        return user
    except (ValueError, UnicodeError) as error:
        raise ProxyError(407, "Proxy Authentication Required") from error


def parse_destination(request_line: bytes) -> tuple[str, str, int, bytes]:
    try:
        method, target, version = request_line.decode("ascii").split()
        if version not in {"HTTP/1.0", "HTTP/1.1"}:
            raise ValueError("unsupported HTTP version")
        parsed = urlsplit("//" + target) if method == "CONNECT" else urlsplit(target)
        if method != "CONNECT" and parsed.scheme != "http":
            raise ValueError("HTTP forwarding requires an absolute HTTP URL")
        if parsed.username is not None or parsed.password is not None or not parsed.hostname:
            raise ValueError("invalid proxy destination")
        port = parsed.port or (443 if method == "CONNECT" else 80)
        if port not in ALLOWED_PORTS:
            raise ProxyError(403, "Destination Port Forbidden")
        path = parsed.path or "/"
        if parsed.query:
            path += "?" + parsed.query
        forwarded = f"{method} {path} {version}\r\n".encode("ascii")
        return method, parsed.hostname, port, forwarded
    except (ValueError, UnicodeError) as error:
        raise ProxyError(400, "Bad Request") from error


def public_ipv6(value: str) -> bool:
    address = ipaddress.ip_address(value)
    return (
        isinstance(address, ipaddress.IPv6Address)
        and address.is_global
        and not address.is_multicast
        and address.ipv4_mapped is None
    )


async def connect_ipv6(host: str, port: int, source: str):
    addresses = await asyncio.get_running_loop().getaddrinfo(
        host, port, family=socket.AF_INET6, type=socket.SOCK_STREAM
    )
    for _, _, _, _, address in addresses:
        target = address[0]
        if not public_ipv6(target):
            continue
        try:
            return await asyncio.open_connection(
                target, port, family=socket.AF_INET6, local_addr=(source, 0)
            )
        except OSError:
            continue
    raise ProxyError(502, "IPv6 Upstream Unavailable")


class IPv6Proxy:
    def __init__(
        self, users: dict[str, ProxyUser], *, max_connections: int = 128, idle_seconds: float = 600
    ) -> None:
        if not users or max_connections < 1 or idle_seconds <= 0:
            raise ValueError("invalid proxy limits")
        for user in users.values():
            if not public_ipv6(user.source_ipv6) or not user.password:
                raise ValueError("invalid IPv6 proxy credential configuration")
        self.users = users
        self._slots = asyncio.Semaphore(max_connections)
        self._idle_seconds = idle_seconds

    async def handle(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        acquired = False
        remote_writer = None
        response_started = False
        try:
            try:
                await asyncio.wait_for(self._slots.acquire(), 0.2)
                acquired = True
            except asyncio.TimeoutError as error:
                raise ProxyError(503, "Proxy Busy") from error
            header = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 10)
            lines = header[:-4].split(b"\r\n")
            user = authenticate(lines[1:], self.users)
            method, host, port, request_line = parse_destination(lines[0])
            remote_reader, remote_writer = await asyncio.wait_for(
                connect_ipv6(host, port, user.source_ipv6), 12
            )
            if method == "CONNECT":
                writer.write(b"HTTP/1.1 200 Connection Established\r\n\r\n")
                await writer.drain()
                response_started = True
            else:
                filtered = [
                    line
                    for line in lines[1:]
                    if line.partition(b":")[0].strip().lower()
                    not in {b"proxy-authorization", b"proxy-connection"}
                ]
                remote_writer.write(request_line + b"\r\n".join(filtered) + b"\r\n\r\n")
                await remote_writer.drain()
                response_started = True
            await self._relay(reader, writer, remote_reader, remote_writer)
        except ProxyError as error:
            if not response_started:
                writer.write(
                    f"HTTP/1.1 {error.status} {error.reason}\r\nConnection: close\r\n".encode()
                )
                if error.status == 407:
                    writer.write(b'Proxy-Authenticate: Basic realm="any2api-ipv6"\r\n')
                writer.write(b"Content-Length: 0\r\n\r\n")
                with suppress(OSError):
                    await writer.drain()
            logger.info("proxy_request_rejected status=%s", error.status)
        except (
            OSError,
            asyncio.TimeoutError,
            asyncio.IncompleteReadError,
            asyncio.LimitOverrunError,
        ) as error:
            if not response_started:
                writer.write(
                    b"HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
                )
                with suppress(OSError):
                    await writer.drain()
            logger.info("proxy_connection_failed error_type=%s", type(error).__name__)
        finally:
            if acquired:
                self._slots.release()
            for stream in (remote_writer, writer):
                if stream is not None:
                    stream.close()
                    with suppress(OSError):
                        await stream.wait_closed()

    async def _relay(self, client_reader, client_writer, remote_reader, remote_writer) -> None:
        async def forward(reader, writer):
            while True:
                data = await asyncio.wait_for(reader.read(65536), self._idle_seconds)
                if not data:
                    return
                writer.write(data)
                await writer.drain()

        tasks = {
            asyncio.create_task(forward(client_reader, remote_writer)),
            asyncio.create_task(forward(remote_reader, client_writer)),
        }
        try:
            done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
            for task in done:
                task.result()
        finally:
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)


async def serve(config_path: Path) -> None:
    config = json.loads(config_path.read_text())
    users = {name: ProxyUser(**value) for name, value in config["users"].items()}
    proxy = IPv6Proxy(
        users,
        max_connections=config.get("max_connections", 128),
        idle_seconds=config.get("idle_seconds", 600),
    )
    server = await asyncio.start_server(
        proxy.handle,
        config.get("listen_host", "0.0.0.0"),
        config.get("listen_port", 10801),
        limit=HEADER_LIMIT,
    )
    async with server:
        logger.info("ipv6_proxy_ready nodes=%s", len(users))
        await server.serve_forever()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--config",
        type=Path,
        default=Path(os.environ.get("CREDENTIALS_DIRECTORY", ".")) / "config.json",
    )
    arguments = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    asyncio.run(serve(arguments.config))
