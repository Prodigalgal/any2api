import pytest

from any2api_automation.config import settings
from any2api_automation.lifecycle.proxy import (
    DirectNode,
    VlessNode,
    _NodeReservation,
    _ordered_nodes,
    _parse_nodes,
    _singbox_config,
    proxy_attempt_payload,
    proxy_lease,
    proxy_parameters,
)


def test_explicit_proxy_preserves_provider_exception() -> None:
    with (
        pytest.raises(LookupError, match="provider-flow"),
        proxy_lease(explicit_url="http://127.0.0.1:8080", dynamic=False, check_url=""),
    ):
        raise LookupError("provider-flow")


def test_node_is_exclusive_for_one_flow() -> None:
    config = settings()
    previous = config.dynamic_proxy_distributed_leases
    config.dynamic_proxy_distributed_leases = False
    node = VlessNode(
        "id", "proxy.example", 443, "proxy.example", "proxy.example", "/", "chrome", "tls", "ws"
    )
    try:
        first = _NodeReservation.acquire(node)
        assert first is not None
        assert _NodeReservation.acquire(node) is None
        first.release()
        second = _NodeReservation.acquire(node)
        assert second is not None
        second.release()
    finally:
        config.dynamic_proxy_distributed_leases = previous


def test_admin_node_pool_overrides_environment_defaults() -> None:
    parameters = proxy_parameters(
        {
            "proxy_pool": {
                "mode": "NODE_LIST",
                "nodes": ["http://proxy.example:8080", "socks5://proxy.example:1080"],
            }
        }
    )
    assert parameters == {
        "explicit_url": "",
        "dynamic": True,
        "subscription_url": "",
        "node_urls": ["http://proxy.example:8080", "socks5://proxy.example:1080"],
        "affinity_key": "",
        "strict_affinity": False,
        "node_offset": 0,
    }


def test_unbound_provider_uses_direct_egress_by_default() -> None:
    parameters = proxy_parameters({})

    assert parameters["explicit_url"] == ""
    assert parameters["dynamic"] is False
    assert parameters["subscription_url"] == ""
    assert parameters["node_urls"] is None


def test_proxy_node_list_supports_vless_and_direct_nodes() -> None:
    nodes = _parse_nodes(
        [
            "http://proxy.example:8080",
            "vless://identity@edge.example:443?type=ws&security=tls&host=edge.example&path=%2Fws",
        ]
    )
    assert isinstance(nodes[0], DirectNode)
    assert isinstance(nodes[1], VlessNode)


def test_proxy_affinity_is_stable_and_identity_specific() -> None:
    nodes = _parse_nodes(
        [
            "http://proxy-a.example:8080",
            "http://proxy-b.example:8080",
            "http://proxy-c.example:8080",
        ]
    )

    first = _ordered_nodes(nodes, "identity-a")
    repeated = _ordered_nodes(list(reversed(nodes)), "identity-a")

    assert first == repeated
    assert {node.url for node in first} == {node.url for node in nodes}
    assert _ordered_nodes(nodes, "identity-b") != first


def test_proxy_attempts_rotate_stable_affinity_across_nodes() -> None:
    nodes = _parse_nodes(
        [
            "http://proxy-a.example:8080",
            "http://proxy-b.example:8080",
            "http://proxy-c.example:8080",
        ]
    )
    first = _ordered_nodes(nodes, "identity-a", 0)

    assert _ordered_nodes(nodes, "identity-a", 1) == first[1:] + first[:1]
    assert _ordered_nodes(nodes, "identity-a", 2) == first[2:] + first[:2]

    initial = proxy_attempt_payload({}, identity="mail@example.test", attempt=1)
    retry = proxy_attempt_payload({}, identity="mail@example.test", attempt=2)
    assert initial["proxy_affinity_key"] == retry["proxy_affinity_key"]
    assert initial["proxy_node_offset"] == 0
    assert retry["proxy_node_offset"] == 1


def test_proxy_node_offset_rejects_invalid_values() -> None:
    with pytest.raises(ValueError, match="offset"):
        proxy_parameters({"proxy_node_offset": -1})


def test_proxy_affinity_rejects_unbounded_or_unsafe_keys() -> None:
    nodes = _parse_nodes(["http://proxy.example:8080"])
    with pytest.raises(ValueError, match="affinity key"):
        _ordered_nodes(nodes, "identity/with/path")


def test_vless_reality_tcp_node_maps_to_singbox() -> None:
    node = _parse_nodes(
        [
            (
                "vless://identity@edge.example:8443?encryption=none&flow=xtls-rprx-vision"
                "&security=reality&sni=www.example.com&fp=chrome&pbk=public-key"
                "&sid=short-id&type=tcp"
            )
        ]
    )[0]
    assert isinstance(node, VlessNode)

    config = _singbox_config(node, 18080)
    outbound = config["outbounds"][0]
    assert outbound["flow"] == "xtls-rprx-vision"
    assert outbound["tls"]["reality"] == {
        "enabled": True,
        "public_key": "public-key",
        "short_id": "short-id",
    }
    assert "transport" not in outbound
