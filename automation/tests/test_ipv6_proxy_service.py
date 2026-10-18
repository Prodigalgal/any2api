import asyncio
import base64
import importlib.util
import socket
import sys
from pathlib import Path
from unittest.mock import AsyncMock

import pytest

module_path = Path(__file__).resolve().parents[2] / "deploy/ipv6-proxy/ipv6_proxy.py"
spec = importlib.util.spec_from_file_location("any2api_ipv6_proxy", module_path)
ipv6_proxy = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = ipv6_proxy
spec.loader.exec_module(ipv6_proxy)


def test_proxy_authentication_and_destination_boundaries():
    user = ipv6_proxy.ProxyUser("password", "2001:470:36:3ea::100")
    header = b"Proxy-Authorization: Basic " + base64.b64encode(b"node:password")
    assert ipv6_proxy.authenticate([header], {"node": user}) is user
    for invalid in ([], [b"Proxy-Authorization: Basic !!!"], [header, header]):
        with pytest.raises(ipv6_proxy.ProxyError) as rejected:
            ipv6_proxy.authenticate(invalid, {"node": user})
        assert rejected.value.status == 407
    assert ipv6_proxy.parse_destination(b"CONNECT arena.ai:443 HTTP/1.1")[:3] == (
        "CONNECT",
        "arena.ai",
        443,
    )
    with pytest.raises(ipv6_proxy.ProxyError) as forbidden:
        ipv6_proxy.parse_destination(b"CONNECT arena.ai:25 HTTP/1.1")
    assert forbidden.value.status == 403
    for address in ("::1", "fe80::1", "fc00::1", "ff00::1", "::ffff:127.0.0.1"):
        assert ipv6_proxy.public_ipv6(address) is False


@pytest.mark.asyncio
async def test_private_destination_never_connects(monkeypatch):
    loop = asyncio.get_running_loop()
    monkeypatch.setattr(
        loop,
        "getaddrinfo",
        AsyncMock(return_value=[(socket.AF_INET6, socket.SOCK_STREAM, 6, "", ("::1", 443, 0, 0))]),
    )
    connect = AsyncMock()
    monkeypatch.setattr(asyncio, "open_connection", connect)
    with pytest.raises(ipv6_proxy.ProxyError) as rejected:
        await ipv6_proxy.connect_ipv6("private.test", 443, "2001:470:36:3ea::100")
    assert rejected.value.status == 502
    connect.assert_not_awaited()


@pytest.mark.asyncio
async def test_authenticated_connections_keep_source_and_release_slots(monkeypatch):
    sources = []

    async def echo(reader, writer):
        try:
            while payload := await reader.read(65536):
                writer.write(payload)
                await writer.drain()
        finally:
            writer.close()
            await writer.wait_closed()

    upstream = await asyncio.start_server(echo, "127.0.0.1", 0)
    upstream_port = upstream.sockets[0].getsockname()[1]

    async def connect(host, port, source):
        sources.append(source)
        return await asyncio.open_connection("127.0.0.1", upstream_port)

    monkeypatch.setattr(ipv6_proxy, "connect_ipv6", connect)
    users = {
        "a": ipv6_proxy.ProxyUser("password", "2001:470:36:3ea::100"),
        "b": ipv6_proxy.ProxyUser("password", "2001:470:36:3ea::200"),
    }
    proxy = ipv6_proxy.IPv6Proxy(users, max_connections=1)
    server = await asyncio.start_server(proxy.handle, "127.0.0.1", 0)
    port = server.sockets[0].getsockname()[1]
    try:
        for username in (None, "a", "a", "b"):
            reader, writer = await asyncio.open_connection("127.0.0.1", port)
            auth = (
                b"Proxy-Authorization: Basic "
                + base64.b64encode(f"{username}:password".encode())
                + b"\r\n"
                if username
                else b""
            )
            writer.write(b"CONNECT arena.ai:443 HTTP/1.1\r\n" + auth + b"\r\n")
            await writer.drain()
            response = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 2)
            assert response.startswith(b"HTTP/1.1 200" if username else b"HTTP/1.1 407")
            if username:
                writer.write(b"hello")
                await writer.drain()
                assert await asyncio.wait_for(reader.readexactly(5), 2) == b"hello"
            writer.close()
            await writer.wait_closed()
        assert sources == [users["a"].source_ipv6, users["a"].source_ipv6, users["b"].source_ipv6]
    finally:
        server.close()
        upstream.close()
        await server.wait_closed()
        await upstream.wait_closed()
