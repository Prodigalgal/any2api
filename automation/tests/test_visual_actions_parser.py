import io

from PIL import Image

from any2api_automation.captcha.registry import registry


def _make_dummy_image() -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", (200, 100), "white").save(buf, format="PNG")
    return buf.getvalue()


def test_parse_visual_actions_single_dict():
    img = _make_dummy_image()
    content = 'ACTIONS={"type":"drag","from":[0.77,0.31],"to":[0.61,0.42]}'
    actions = registry._parse_visual_actions(img, content)
    assert len(actions) == 1
    assert actions[0].type == "drag"
    assert actions[0].start == (0.77, 0.31)
    assert actions[0].end == (0.61, 0.42)


def test_parse_visual_actions_markdown_code_block():
    img = _make_dummy_image()
    content = """I have analyzed the image carefully.
```json
[
  {"type": "click", "at": [0.45, 0.55]}
]
```
Done."""
    actions = registry._parse_visual_actions(img, content)
    assert len(actions) == 1
    assert actions[0].type == "click"
    assert actions[0].at == (0.45, 0.55)


def test_parse_visual_actions_cot_reasoning_multiple_actions_marker():
    img = _make_dummy_image()
    content = """Let's consider ACTIONS=.
Step 1: Inspect first candidate ACTIONS=[{"type":"drag","from":[0.1,0.1],"to":[0.2,0.2]}].
Step 2: Correct to destination:
ACTIONS=[{"type":"drag","from":[0.77,0.31],"to":[0.61,0.42]}]"""
    actions = registry._parse_visual_actions(img, content)
    assert len(actions) == 1
    assert actions[0].type == "drag"
    assert actions[0].start == (0.77, 0.31)
    assert actions[0].end == (0.61, 0.42)


def test_parse_visual_actions_regex_fallback():
    img = _make_dummy_image()
    content = "Target drag from [0.77, 0.31] to [0.61, 0.42]."
    actions = registry._parse_visual_actions(img, content)
    assert len(actions) == 1
    assert actions[0].type == "drag"
    assert actions[0].start == (0.77, 0.31)
    assert actions[0].end == (0.61, 0.42)
