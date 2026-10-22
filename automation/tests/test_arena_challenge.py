import asyncio
import io
import json
from dataclasses import replace
from unittest.mock import Mock

import httpx
import pytest
from PIL import Image

from any2api_automation.captcha.policy import CaptchaAiPolicy, bind_captcha_policy
from any2api_automation.captcha.registry import SolverRegistry
from any2api_automation.config import settings
from any2api_automation.providers import arena_challenge as challenge


@pytest.mark.parametrize(
    "content,expected",
    [
        ('{"select":[9,1]}', (1, 9)),
        ('{"select":[]}', ()),
        ('{"select":[1,1]}', None),
        ('{"select":[0]}', None),
        ('{"select":[10]}', None),
        ('{"select":[true]}', None),
        ('{"select":["2"]}', None),
        ('{"select":{}}', None),
        ('{"select":[1],"extra":2}', None),
        ("[1,2]", None),
        ('{"select":[1]} trailing', None),
    ],
)
def test_grid_selection_is_strict_and_has_one_network_attempt(monkeypatch, content, expected):
    registry = SolverRegistry()
    complete = Mock(return_value=content)
    monkeypatch.setattr(registry, "_visual_completion_sync", complete)
    assert (
        registry.solve_visual_grid_sync(
            b"challenge-only", 3, timeout_seconds=4, ai_policy=CaptchaAiPolicy()
        )
        == expected
    )
    assert complete.call_args.kwargs["max_attempts"] == 1
    assert complete.call_args.kwargs["function"]["name"] == "select_grid"
    schema = complete.call_args.kwargs["function"]["parameters"]
    assert schema["properties"]["select"]["items"]["maximum"] == 9


@pytest.mark.parametrize("columns", [3, 4])
def test_grid_numbering_only_changes_cell_pixels_and_keeps_the_raw_image(columns):
    buffer = io.BytesIO()
    Image.new("RGB", (480, 600), "#1a73e8").save(buffer, format="PNG")
    raw = buffer.getvalue()
    cell = 480 / columns
    boxes = [
        [col / columns, (120 + row * cell) / 600, 1 / columns, cell / 600]
        for row in range(columns)
        for col in range(columns)
    ]
    numbered = challenge._numbered_grid_image(raw, boxes)
    with Image.open(io.BytesIO(raw)) as original, Image.open(io.BytesIO(numbered)) as result:
        assert result.size == original.size
        assert result.crop((0, 0, 480, 120)).tobytes() == original.crop((0, 0, 480, 120)).tobytes()
        for col, row in ((col, row) for row in range(columns) for col in range(columns)):
            bounds = (round(col * cell) + 2, round(120 + row * cell) + 2)
            assert result.getpixel(bounds) == (0, 0, 0)
    assert buffer.getvalue() == raw


@pytest.mark.parametrize(
    "bounds",
    [[-0.1, 0, 0.1, 0.1], [0, 0, 0, 0.1], [0, 0.9, 0.1, 0.2], [float("nan"), 0, 0.1, 0.1]],
)
def test_grid_numbering_rejects_invalid_geometry(bounds):
    buffer = io.BytesIO()
    Image.new("RGB", (480, 600), "blue").save(buffer, format="PNG")
    with pytest.raises(ValueError, match="Arena challenge cell"):
        challenge._numbered_grid_image(buffer.getvalue(), [bounds] * 9)


@pytest.mark.parametrize(
    "calls",
    [
        None,
        [],
        [None],
        [{"type": "custom", "function": {"name": "select_grid", "arguments": '{"select":[1]}'}}],
        [{"type": "function", "function": {"name": "different", "arguments": '{"select":[1]}'}}],
        [{"type": "function", "function": {"name": "select_grid", "arguments": {"select": [1]}}}],
        [{"type": "function"}, {"type": "function"}],
    ],
)
def test_grid_rejects_missing_wrong_or_multiple_function_calls(calls):
    assert (
        SolverRegistry._visual_function_arguments(
            {"content": '{"select":[1]}', "tool_calls": calls}, "select_grid"
        )
        == ""
    )


@pytest.mark.parametrize(
    "arguments,expected", [('{"select":[7,8,9]}', (7, 8, 9)), ('{"select":[7,8,9]},9]}', None)]
)
def test_grid_uses_required_function_arguments_without_repairing_json(
    monkeypatch, arguments, expected
):
    monkeypatch.setenv("ANY2API_AUTOMATION_CAPTCHA_AI_ENABLED", "true")
    monkeypatch.setenv("ANY2API_PUBLIC_API_KEY", "fixture-key")
    settings.cache_clear()
    response = httpx.Response(
        200,
        request=httpx.Request("POST", "https://gateway.example"),
        json={
            "choices": [
                {
                    "message": {
                        "content": None,
                        "tool_calls": [
                            {
                                "type": "function",
                                "function": {"name": "select_grid", "arguments": arguments},
                            }
                        ],
                    }
                }
            ]
        },
    )
    post = Mock(return_value=response)
    monkeypatch.setattr("any2api_automation.captcha.registry.httpx.post", post)
    try:
        assert (
            SolverRegistry().solve_visual_grid_sync(
                b"challenge-only",
                3,
                timeout_seconds=4,
                ai_policy=CaptchaAiPolicy(internal_model="mimo/mimo-v2.6-pro"),
            )
            == expected
        )
        assert post.call_count == 1
        request = post.call_args.kwargs["json"]
        assert request["tool_choice"] == {"type": "function", "function": {"name": "select_grid"}}
        assert request["parallel_tool_calls"] is False
        assert request["reasoning_effort"] == "none"
    finally:
        settings.cache_clear()


def test_grid_request_does_not_retry_gateway_failure(monkeypatch):
    monkeypatch.setenv("ANY2API_AUTOMATION_CAPTCHA_AI_ENABLED", "true")
    monkeypatch.setenv("ANY2API_PUBLIC_API_KEY", "fixture-key")
    settings.cache_clear()
    post = Mock(side_effect=httpx.ConnectError("fixture"))
    monkeypatch.setattr("any2api_automation.captcha.registry.httpx.post", post)
    try:
        assert (
            SolverRegistry().solve_visual_grid_sync(
                b"challenge-only", 4, timeout_seconds=4, ai_policy=CaptchaAiPolicy()
            )
            is None
        )
        assert post.call_count == 1
    finally:
        settings.cache_clear()


def test_grid_request_keeps_transport_error_diagnostic(monkeypatch):
    registry = SolverRegistry()
    registry._diagnostics.visual = "request_error:ReadTimeout"
    monkeypatch.setattr(registry, "_visual_completion_sync", lambda *args, **kwargs: "")
    assert (
        registry.solve_visual_grid_sync(
            b"challenge-only", 3, timeout_seconds=4, ai_policy=CaptchaAiPolicy()
        )
        is None
    )
    assert registry.visual_diagnostic() == "request_error:ReadTimeout"


def test_four_by_four_grid_accepts_cell_sixteen(monkeypatch):
    registry = SolverRegistry()
    monkeypatch.setattr(registry, "_visual_completion_sync", lambda *a, **kw: '{"select":[16]}')
    assert registry.solve_visual_grid_sync(
        b"challenge-only", 4, timeout_seconds=4, ai_policy=CaptchaAiPolicy()
    ) == (16,)


@pytest.mark.parametrize("color,active", [("gray", False), ("#1a73e8", True)])
def test_gray_challenge_cannot_trigger_recognition(color, active):
    buffer = io.BytesIO()
    Image.new("RGB", (300, 400), color).save(buffer, format="PNG")
    assert challenge._active_challenge_image(buffer.getvalue()) is active


class Surface:
    def __init__(self):
        self.content = 0
        self.selected = {2}
        self.closed = False
        self.ready = True
        self.dynamic = False
        self.toggles = []
        self.submissions = 0
        self.recognition_image = None

    async def read(self):
        if self.closed:
            return None
        return challenge.GridSnapshot(
            image=f"{self.content}:{sorted(self.selected)}".encode(),
            signature=str(self.content),
            selected=frozenset(self.selected),
            columns=3,
            ready=self.ready,
            recognition_image=self.recognition_image,
        )

    async def toggle(self, cell):
        self.toggles.append(cell)
        self.selected.symmetric_difference_update({cell})
        if self.dynamic:
            self.content += 1
            self.selected.clear()

    async def submit(self):
        self.submissions += 1
        self.closed = True


class DynamicCellSurface(Surface):
    def __init__(self):
        super().__init__()
        self.selected.clear()
        self.sources = list(range(9))
        self.instruction = "synthetic dynamic instruction"
        self.controls_ready = True
        self.pending = set()
        self.after_toggle = None
        self.clicked_while_other_pending = []

    async def read(self):
        snapshot = await super().read()
        if snapshot is None:
            return None
        return replace(
            snapshot,
            signature=json.dumps([self.instruction, self.sources]),
            instruction=self.instruction,
            cells=tuple(
                challenge.GridCellSnapshot(str(source), index + 1 not in self.pending)
                for index, source in enumerate(self.sources)
            ),
            controls_ready=self.controls_ready,
            ready=self.ready and self.controls_ready and not self.pending,
        )

    async def toggle(self, cell):
        if self.pending:
            self.clicked_while_other_pending.append(cell)
        self.toggles.append(cell)
        if cell in {1, 3}:
            self.sources[cell - 1] += 100
            self.content += 1
            self.pending.add(cell)
            asyncio.get_running_loop().call_later(0.02, self.pending.discard, cell)
        else:
            self.selected.symmetric_difference_update({cell})
        if self.after_toggle:
            self.after_toggle(cell)


@pytest.mark.asyncio
async def test_dynamic_click_keeps_answers_for_other_unchanged_cells(flow, monkeypatch):
    _, recognize = flow
    surface = DynamicCellSurface()
    monkeypatch.setattr(challenge, "ArenaRecaptchaGrid", lambda _: surface)
    recognize.side_effect = [(1, 3), ()]
    await solve()
    assert surface.toggles == [1, 3]
    assert surface.clicked_while_other_pending == [3]
    assert recognize.call_count == 2
    assert surface.submissions == 1


@pytest.mark.asyncio
@pytest.mark.parametrize("changed", ["instruction", "target", "controls"])
async def test_dynamic_click_rejects_stale_or_inactive_target(flow, monkeypatch, changed):
    _, recognize = flow
    surface = DynamicCellSurface()
    monkeypatch.setattr(challenge, "ArenaRecaptchaGrid", lambda _: surface)

    def change_after_first(cell):
        if cell != 1:
            return
        if changed == "instruction":
            surface.instruction = "different task"
        elif changed == "target":
            surface.sources[2] += 1000
        else:
            surface.controls_ready = False

    surface.after_toggle = change_after_first
    recognize.side_effect = [(1, 3), (9,)]
    await solve()
    assert 3 not in surface.toggles
    assert surface.toggles == ([1] if changed == "controls" else [1, 9])
    assert surface.submissions == (0 if changed == "controls" else 1)


@pytest.fixture
def flow(monkeypatch):
    surface = Surface()
    monkeypatch.setattr(challenge, "ArenaRecaptchaGrid", lambda _: surface)
    monkeypatch.setattr(challenge.registry, "visual_ai_available", lambda _: True)
    recognize = Mock(return_value=(1, 3))
    monkeypatch.setattr(challenge.registry, "solve_visual_grid_sync", recognize)

    async def run(function, *args, **kwargs):
        return function(*args, **kwargs)

    monkeypatch.setattr(challenge, "run_blocking", run)
    return surface, recognize


async def solve():
    await challenge.solve_arena_image_challenge(
        object(), deadline=asyncio.get_running_loop().time() + 3
    )


@pytest.mark.asyncio
async def test_static_grid_reconciles_selection_and_submits_once(flow):
    surface, recognize = flow
    await solve()
    assert surface.selected == {1, 3}
    assert surface.toggles == [1, 2, 3]
    assert surface.submissions == recognize.call_count == 1


@pytest.mark.asyncio
async def test_grid_recognition_uses_labeled_copy_and_a_bounded_complete_response_window(flow):
    surface, recognize = flow
    surface.recognition_image = b"numbered-grid-only"
    await challenge.solve_arena_image_challenge(
        object(), deadline=asyncio.get_running_loop().time() + 90
    )
    assert recognize.call_args.args[0] == b"numbered-grid-only"
    assert recognize.call_args.kwargs["timeout_seconds"] == 30
    assert surface.submissions == 1


@pytest.mark.asyncio
async def test_changed_image_after_ai_never_applies_stale_answer(flow):
    surface, recognize = flow

    def changed(*args, **kwargs):
        surface.content += 1
        return (1, 3)

    recognize.side_effect = changed
    result = await challenge.solve_arena_image_challenge(
        object(), deadline=asyncio.get_running_loop().time() + 3
    )
    assert recognize.call_count == 3
    assert result.outcome is challenge.ArenaChallengeOutcome.EXHAUSTED
    assert surface.toggles == []
    assert surface.submissions == 0


@pytest.mark.asyncio
async def test_changed_images_are_recognized_again_before_using_only_the_current_answer(flow):
    surface, recognize = flow

    def next_answer(*args, **kwargs):
        if recognize.call_count < 3:
            surface.content += 1
            surface.selected.clear()
            return (1, 3)
        return (7,)

    recognize.side_effect = next_answer
    result = await challenge.solve_arena_image_challenge(
        object(), deadline=asyncio.get_running_loop().time() + 3
    )
    assert [call.args[0] for call in recognize.call_args_list] == [b"0:[2]", b"1:[]", b"2:[]"]
    assert surface.toggles == [7]
    assert surface.submissions == 1
    assert result.ai_calls == 3
    assert not result.should_finish_pending_widget


@pytest.mark.asyncio
async def test_unready_replacement_is_waited_for_without_using_the_previous_answer(flow):
    surface, recognize = flow

    def next_answer(*args, **kwargs):
        if recognize.call_count == 1:
            surface.content += 1
            surface.selected.clear()
            surface.ready = False
            asyncio.get_running_loop().call_soon(lambda: setattr(surface, "ready", True))
            return (1, 3)
        return (9,)

    recognize.side_effect = next_answer
    result = await challenge.solve_arena_image_challenge(
        object(), deadline=asyncio.get_running_loop().time() + 3
    )
    assert recognize.call_count == 2
    assert surface.toggles == [9]
    assert surface.submissions == 1
    assert not result.should_finish_pending_widget


@pytest.mark.asyncio
@pytest.mark.parametrize("cancelled", [False, True])
async def test_replacement_wait_preserves_deadline_and_cancellation(flow, cancelled):
    surface, recognize = flow
    started = asyncio.Event()

    def changed(*args, **kwargs):
        surface.content += 1
        surface.ready = False
        started.set()
        return (1,)

    recognize.side_effect = changed
    task = asyncio.create_task(
        challenge.solve_arena_image_challenge(
            object(), deadline=asyncio.get_running_loop().time() + 3
        )
    )
    if cancelled:
        await started.wait()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
    else:
        result = await task
        assert result.outcome is challenge.ArenaChallengeOutcome.DEADLINE
    assert recognize.call_count == 1
    assert surface.toggles == []
    assert surface.submissions == 0


@pytest.mark.asyncio
async def test_dynamic_replacement_stops_at_three_calls_without_stale_submission(flow):
    surface, recognize = flow
    surface.dynamic = True
    await solve()
    assert recognize.call_count == 3
    assert surface.toggles == [1, 1, 1]
    assert surface.submissions == 0


@pytest.mark.asyncio
async def test_invalid_recognition_has_no_actions(flow):
    surface, recognize = flow
    recognize.return_value = None
    await solve()
    assert recognize.call_count == 3
    assert surface.toggles == []
    assert surface.submissions == 0


@pytest.mark.asyncio
async def test_invalid_answers_can_recover_inside_the_same_three_call_budget(flow):
    surface, recognize = flow
    recognize.side_effect = [None, None, (1, 3)]
    result = await challenge.solve_arena_image_challenge(
        object(), deadline=asyncio.get_running_loop().time() + 3
    )
    assert result.ai_calls == 3
    assert result.submissions == 1
    assert not result.should_finish_pending_widget
    assert surface.toggles == [1, 2, 3]


@pytest.mark.asyncio
async def test_failed_budget_is_a_terminal_result_without_clicks(flow):
    surface, recognize = flow
    recognize.return_value = None
    result = await challenge.solve_arena_image_challenge(
        object(), deadline=asyncio.get_running_loop().time() + 3
    )
    assert result.outcome is challenge.ArenaChallengeOutcome.EXHAUSTED
    assert result.ai_calls == 3
    assert result.should_finish_pending_widget
    assert surface.toggles == []


@pytest.mark.asyncio
async def test_disabled_policy_does_not_recognize(flow):
    surface, recognize = flow
    with bind_captcha_policy(CaptchaAiPolicy(enabled=False)):
        await solve()
    recognize.assert_not_called()
    assert surface.submissions == 0


@pytest.mark.asyncio
async def test_default_recognition_is_internal_and_cannot_reenter_arena(flow):
    _, recognize = flow
    await solve()
    policy = recognize.call_args.kwargs["ai_policy"]
    assert policy.mode == "internal"
    assert policy.internal_models == ("mimo/mimo-v2.6-pro",)


@pytest.mark.asyncio
@pytest.mark.parametrize("model", ["arena/max", "random", "unqualified-model"])
async def test_recursive_or_unqualified_ai_routes_cannot_consume_recognition(flow, model):
    surface, recognize = flow
    with bind_captcha_policy(CaptchaAiPolicy(internal_model=model)):
        await solve()
    recognize.assert_not_called()
    assert surface.submissions == 0


@pytest.mark.asyncio
async def test_pending_challenge_does_not_consume_ai_budget(flow):
    surface, recognize = flow
    surface.ready = False
    await solve()
    recognize.assert_not_called()
    assert surface.submissions == 0


@pytest.mark.asyncio
async def test_cancel_during_recognition_propagates_and_never_clicks(flow, monkeypatch):
    surface, _recognize = flow
    started = asyncio.Event()

    async def blocked(*args, **kwargs):
        started.set()
        await asyncio.Future()

    monkeypatch.setattr(challenge, "run_blocking", blocked)
    task = asyncio.create_task(solve())
    await started.wait()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert surface.toggles == []
    assert surface.submissions == 0


@pytest.mark.asyncio
async def test_recognition_absolute_deadline_stops_without_clicking(flow, monkeypatch):
    surface, _recognize = flow
    monkeypatch.setattr(challenge, "_AI_TIMEOUT_SECONDS", 0.01)

    async def blocked(*args, **kwargs):
        await asyncio.Future()

    monkeypatch.setattr(challenge, "run_blocking", blocked)
    await solve()
    assert surface.toggles == []
    assert surface.submissions == 0


@pytest.mark.asyncio
async def test_disappeared_challenge_does_not_submit(flow):
    surface, recognize = flow

    def vanished(*args, **kwargs):
        surface.closed = True
        return (1,)

    recognize.side_effect = vanished
    await solve()
    assert surface.toggles == []
    assert surface.submissions == 0
