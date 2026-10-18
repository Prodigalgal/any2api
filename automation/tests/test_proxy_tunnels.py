import asyncio
import threading
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager

import pytest

from any2api_automation.lifecycle.proxy_tunnels import ProxyTunnelPool
from any2api_automation.providers import transport_support


class FakeTunnel:
    def __init__(self, port):
        self.url = f"http://127.0.0.1:{port}"
        self.alive = False
        self.closed = 0

    def __enter__(self):
        self.alive = True
        return self.url

    def __exit__(self, *_):
        self.alive = False
        self.closed += 1

    def is_alive(self):
        return self.alive


@pytest.fixture
def tunnel_pool():
    processes = []
    now = [0.0]

    def factory(node):
        process = FakeTunnel(18000 + len(processes))
        processes.append(process)
        return process

    pool = ProxyTunnelPool(factory, capacity=2, idle_seconds=30, clock=lambda: now[0])
    yield pool, processes, now
    pool.close()


def test_requests_reuse_idle_tunnel_and_browser_keeps_it_past_idle_expiry(tunnel_pool):
    pool, processes, now = tunnel_pool
    first = pool.acquire("node-a")
    browser = pool.retain(first.proxy_url)
    first.release()
    now[0] = 300
    assert pool.reap_idle() == 0
    second = pool.acquire("node-a")
    assert second.proxy_url == browser.proxy_url
    second.release()
    browser.release()
    browser.release()
    assert processes[0].closed == 0
    assert pool.snapshot()["references"] == 0
    now[0] += 31
    assert pool.reap_idle() == 1
    assert processes[0].closed == 1


def test_api_only_tunnel_is_reused_and_no_reference_is_held_while_idle(tunnel_pool):
    pool, processes, now = tunnel_pool
    for _ in range(20):
        lease = pool.acquire("node-a")
        lease.release()
    assert len(processes) == 1
    assert pool.snapshot() == {
        "capacity": 2,
        "total": 1,
        "idle": 1,
        "references": 0,
        "created": 1,
        "reused": 19,
    }
    now[0] = 31
    assert pool.reap_idle() == 1


def test_capacity_evicts_oldest_idle_tunnel_and_preserves_active_owner(tunnel_pool):
    pool, processes, _ = tunnel_pool
    active = pool.acquire("node-a")
    idle = pool.acquire("node-b")
    idle.release()
    third = pool.acquire("node-c")
    assert processes[0].closed == 0
    assert processes[1].closed == 1
    assert pool.snapshot()["total"] == 2
    third.release()
    active.release()


def test_dead_tunnel_replacement_is_not_removed_by_late_old_release(tunnel_pool):
    pool, processes, _ = tunnel_pool
    old = pool.acquire("node-a")
    processes[0].alive = False
    new = pool.acquire("node-a")
    assert old.proxy_url != new.proxy_url
    old.release()
    current = pool.acquire("node-a")
    assert current.proxy_url == new.proxy_url
    assert len(processes) == 2
    assert processes[0].closed == 1
    current.release()
    new.release()


def test_parallel_acquires_start_one_process():
    entered = threading.Event()
    unblock = threading.Event()
    processes = []

    class SlowTunnel(FakeTunnel):
        def __enter__(self):
            entered.set()
            assert unblock.wait(2)
            return super().__enter__()

    def factory(node):
        process = SlowTunnel(18080)
        processes.append(process)
        return process

    pool = ProxyTunnelPool(factory, capacity=1, idle_seconds=30)
    try:
        with ThreadPoolExecutor(max_workers=5) as executor:
            futures = [executor.submit(pool.acquire, "node") for _ in range(5)]
            assert entered.wait(2)
            unblock.set()
            leases = [future.result(timeout=2) for future in futures]
        assert len(processes) == 1
        assert pool.snapshot()["references"] == 5
        for lease in leases:
            lease.release()
    finally:
        unblock.set()
        pool.close()
    assert processes[0].closed == 1


def test_full_pool_times_out_without_evicting_active_browser():
    pool = ProxyTunnelPool(
        lambda _: FakeTunnel(18080), capacity=1, idle_seconds=30, acquire_timeout_seconds=0.01
    )
    first = pool.acquire("a")
    try:
        with pytest.raises(RuntimeError, match="timed out"):
            pool.acquire("b")
        assert pool.snapshot()["references"] == 1
    finally:
        first.release()
        pool.close()


def test_startup_failure_cleans_process_and_frees_capacity():
    class BrokenTunnel(FakeTunnel):
        def __enter__(self):
            raise RuntimeError("startup failed")

    process = BrokenTunnel(18080)
    pool = ProxyTunnelPool(lambda _: process, capacity=1, idle_seconds=30)
    try:
        with pytest.raises(RuntimeError, match="startup failed"):
            pool.acquire("a")
        assert pool.snapshot()["total"] == 0
        assert process.closed == 1
    finally:
        pool.close()


def test_node_identity_change_creates_new_tunnel(tunnel_pool):
    pool, processes, _ = tunnel_pool
    old = pool.acquire(("server", "old-credential"))
    new = pool.acquire(("server", "new-credential"))
    assert old.proxy_url != new.proxy_url
    assert len(processes) == 2
    old.release()
    new.release()


@pytest.mark.asyncio
async def test_cancelled_proxy_acquire_releases_completed_thread_lease(monkeypatch):
    entered = threading.Event()
    unblock = threading.Event()
    released = threading.Event()

    @contextmanager
    def slow_lease(**kwargs):
        entered.set()
        assert unblock.wait(2)
        try:
            yield "http://127.0.0.1:18080"
        finally:
            released.set()

    monkeypatch.setattr(transport_support, "proxy_lease", slow_lease)

    async def operation():
        async with transport_support.transport_proxy_lease({}, check_url="https://example.test"):
            pytest.fail("cancelled operation must not enter its body")

    task = asyncio.create_task(operation())
    try:
        assert await asyncio.to_thread(entered.wait, 2)
        task.cancel()
        await asyncio.sleep(0)
        assert not task.done()
    finally:
        unblock.set()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert released.is_set()
