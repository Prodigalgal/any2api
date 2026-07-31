import json
import struct
from urllib.parse import quote

from any2api_automation.providers.grok_protocol.castle_service import mint_js_args
from any2api_automation.providers.grok_protocol.client import _next_script_urls
from any2api_automation.providers.grok_protocol.same_session import (
    _extract_router_state_tree,
    encode_grpc_create_email,
)


def test_next_script_discovery_preserves_current_deployment_query() -> None:
    html = """
    <script src="/_next/static/chunks/current.js?dpl=deployment-1"></script>
    <script src='/_next/static/chunks/second.js'></script>
    <script src="https://attacker.invalid/_next/static/chunks/ignored.js"></script>
    <script src="/not-a-chunk.js"></script>
    """

    assert _next_script_urls(html) == [
        "https://accounts.x.ai/_next/static/chunks/current.js?dpl=deployment-1",
        "https://accounts.x.ai/_next/static/chunks/second.js",
    ]


def test_castle_mint_uses_current_signup_request_parameters() -> None:
    assert mint_js_args(" user@example.com ")["request"] == {
        "method": "email_password",
        "flow": "signup",
        "email": "user@example.com",
    }


def test_create_email_grpc_frame_includes_castle_request_token() -> None:
    frame = encode_grpc_create_email("user@example.com", "castle-token")

    assert frame[0] == 0
    payload_size = struct.unpack(">I", frame[1:5])[0]
    assert payload_size == len(frame[5:])
    assert frame[5:] == (b"\x0a\x10user@example.com\x1a\x0ccastle-token")


def test_router_state_tree_is_read_from_current_next_flight_payload() -> None:
    tree = [
        "",
        {"children": ["(app)", {"children": ["(auth)", {"children": ["sign-up", {}]}]}]},
        "$undefined",
        "$undefined",
        16,
    ]
    flight_record = "0:" + json.dumps({"f": [[tree, "rendered", None, False]]}) + "\n"
    escaped_record = json.dumps(flight_record)[1:-1]
    html = f'<script>self.__next_f.push([1,"{escaped_record}"])</script>'

    assert _extract_router_state_tree(html) == quote(
        json.dumps(tree, separators=(",", ":")),
        safe="",
    )
