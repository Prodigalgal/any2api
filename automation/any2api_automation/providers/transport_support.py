from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any

from ..lifecycle.proxy import proxy_lease, proxy_parameters


def transport_frame(kind: str, **payload: object) -> bytes:
    return (json.dumps({"type": kind, **payload}, separators=(",", ":")) + "\n").encode()


def optional_proxy_parameters(payload: dict[str, Any]) -> dict[str, Any]:
    if not any(key in payload for key in ("proxy_pool", "proxy_url", "dynamic_proxy")):
        return {
            "explicit_url": "",
            "dynamic": False,
            "subscription_url": "",
            "node_urls": None,
            "affinity_key": "",
            "strict_affinity": False,
            "node_offset": 0,
        }
    return proxy_parameters(payload)


@asynccontextmanager
async def transport_proxy_lease(
    payload: dict[str, Any],
    *,
    check_url: str,
    reject_redirect_hosts: tuple[str, ...] = (),
) -> AsyncIterator[str]:
    lease = proxy_lease(
        check_url=check_url,
        reject_redirect_hosts=reject_redirect_hosts,
        **optional_proxy_parameters(payload),
    )
    proxy_url = await asyncio.to_thread(lease.__enter__)
    try:
        yield proxy_url
    finally:
        await asyncio.to_thread(lease.__exit__, None, None, None)
