import asyncio
import io
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

    async def read(self):
        if self.closed:
            return None
        return challenge.GridSnapshot(
            image=f"{self.content}:{sorted(self.selected)}".encode(),
            signature=str(self.content),
            selected=frozenset(self.selected),
            columns=3,
            ready=self.ready,
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
async def test_changed_image_after_ai_never_applies_stale_answer(flow):
    surface, recognize = flow

    def changed(*args, **kwargs):
        surface.content += 1
        return (1, 3)

    recognize.side_effect = changed
    await solve()
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
