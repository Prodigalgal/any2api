import pytest

from any2api_automation.providers.page_fetch_browser import _data_shape, _resolve_target_path


def test_endpoint_key_resolves_without_a_fallback_path() -> None:
    assert (
        _resolve_target_path("", "completion", {"completion": "/api/v0/chat/completion"})
        == "/api/v0/chat/completion"
    )


def test_missing_endpoint_key_still_requires_a_valid_fallback_path() -> None:
    with pytest.raises(ValueError, match="same-origin path"):
        _resolve_target_path("", "missing", {})


def test_data_shape_exposes_event_metadata_without_event_content() -> None:
    assert _data_shape(
        '{"event":{"type":"content","content":"secret answer"},"lastOne":false}'
    ) == (
        "json:top=event,lastOne;event=content;event_keys=content,type;"
        "content_type=str;content_len=13"
    )


def test_data_shape_marks_non_json_payloads() -> None:
    assert _data_shape("upstream returned html") == "non_json"
