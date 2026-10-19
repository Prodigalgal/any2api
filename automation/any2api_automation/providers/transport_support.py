from __future__ import annotations

import asyncio
import json
import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any

from ..blocking_work import run_blocking, run_cleanup
from ..lifecycle.proxy import proxy_lease, proxy_parameters

logger = logging.getLogger("any2api_automation.transport_support")


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
    acquiring = asyncio.create_task(run_blocking(lease.__enter__))
    try:
        proxy_url = await asyncio.shield(acquiring)
    except asyncio.CancelledError:
        # A worker thread keeps running after task cancellation; recover its lease first.
        try:
            await acquiring
        except Exception as error:  # noqa: BLE001 - preserve the caller's cancellation
            logger.warning("cancelled_proxy_acquire_failed error_type=%s", type(error).__name__)
        else:
            await run_cleanup(lease.__exit__, None, None, None)
        raise
    try:
        yield proxy_url
    finally:
        releasing = asyncio.create_task(run_cleanup(lease.__exit__, None, None, None))
        try:
            await asyncio.shield(releasing)
        except asyncio.CancelledError:
            await releasing
            raise
