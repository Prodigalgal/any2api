"""Redis script contracts, exercised against a real isolated Redis test instance."""

import os
from uuid import uuid4

import pytest
import redis

from any2api_automation.lifecycle.proxy import _LUA_ACQUIRE_LEASE, _LUA_RELEASE_LEASE


@pytest.fixture
def reservation_store():
    url = os.environ.get("ANY2API_TEST_REDIS_URL")
    if not url:
        pytest.skip("set ANY2API_TEST_REDIS_URL for Redis integration")
    client = redis.Redis.from_url(url, decode_responses=True)
    key = "any2api:test:proxy-reservation:" + uuid4().hex
    try:
        yield client, key
    finally:
        client.delete(key)
        client.close()


def test_exclusive_reservation_only_owner_can_release(reservation_store):
    client, key = reservation_store
    client.set(key, "owner", ex=60)
    assert client.eval(_LUA_RELEASE_LEASE, 1, key, "other") == 0
    assert client.get(key) == "owner"
    assert client.eval(_LUA_RELEASE_LEASE, 1, key, "owner") == 1
    assert client.eval(_LUA_RELEASE_LEASE, 1, key, "owner") == 0
    assert not client.exists(key)


def test_concurrent_reservation_release_preserves_other_owners(reservation_store):
    client, key = reservation_store
    assert client.eval(_LUA_ACQUIRE_LEASE, 1, key, 2, "first", 60) == 1
    assert client.eval(_LUA_ACQUIRE_LEASE, 1, key, 2, "second", 60) == 1
    assert client.eval(_LUA_ACQUIRE_LEASE, 1, key, 2, "third", 60) == 0
    assert client.eval(_LUA_RELEASE_LEASE, 1, key, "first") == 1
    assert client.smembers(key) == {"second"}
    assert client.eval(_LUA_ACQUIRE_LEASE, 1, key, 2, "third", 60) == 1
    assert client.eval(_LUA_RELEASE_LEASE, 1, key, "first") == 0
    assert client.smembers(key) == {"second", "third"}


def test_release_of_missing_or_unknown_key_is_safe(reservation_store):
    client, key = reservation_store
    assert client.eval(_LUA_RELEASE_LEASE, 1, key, "owner") == 0
    client.hset(key, "foreign", "value")
    assert client.eval(_LUA_RELEASE_LEASE, 1, key, "owner") == 0
    assert client.hgetall(key) == {"foreign": "value"}
