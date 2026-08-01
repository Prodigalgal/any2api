import io
import threading
import time
from typing import Any, ClassVar

import httpx
from PIL import Image, ImageChops, ImageDraw

from any2api_automation.captcha.models import SolverEstimate, VisualAction
from any2api_automation.captcha.registry import _captcha_text_candidate, registry
from any2api_automation.captcha.strategy import (
    ChallengeAttemptResult,
    ChallengeDetection,
    ChallengePolicy,
    ChallengeRunner,
    ChallengeStrategy,
)
from any2api_automation.config import settings
from any2api_automation.lifecycle.browser import (
    BrowserContextProfile,
    BrowserFingerprintPolicy,
    BrowserFingerprintVariant,
    BrowserLaunchProfile,
)
from any2api_automation.lifecycle.registration import RegistrationStage, RegistrationTrace
from any2api_automation.providers.glm_challenge import (
    GlmAliyunChallenge,
    GlmCaptchaSurface,
    GlmSemanticSliderInput,
)
from any2api_automation.providers.longcat import LongcatAutomationProvider, _response_serial
from any2api_automation.providers.longcat_challenge import (
    _extract_longcat_piece,
    _map_longcat_gap,
)
from any2api_automation.providers.qwen import QwenAutomationProvider
from any2api_automation.providers.qwen_challenge import QwenSignupChallenge, _fuse_qwen_gap


class _Page:
    def __init__(self) -> None:
        self.waits: list[int] = []

    def wait_for_timeout(self, milliseconds: int) -> None:
        self.waits.append(milliseconds)


class _RetryStrategy(ChallengeStrategy):
    def __init__(self, solved_at: int) -> None:
        self.solved_at = solved_at
        self.solve_count = 0
        self.refresh_count = 0

    @property
    def strategy_id(self) -> str:
        return "fixture"

    def detect(self, page: Any, timeout_ms: int) -> ChallengeDetection | None:
        del page, timeout_ms
        return ChallengeDetection("slider")

    def solve(
        self, page: Any, detection: ChallengeDetection, attempt: int
    ) -> ChallengeAttemptResult:
        del page, detection
        self.solve_count += 1
        return ChallengeAttemptResult(f"attempt={attempt}")

    def verify(
        self,
        page: Any,
        detection: ChallengeDetection,
        attempt: ChallengeAttemptResult,
    ) -> bool:
        del page, detection, attempt
        return self.solve_count >= self.solved_at

    def refresh(self, page: Any, detection: ChallengeDetection) -> None:
        del page, detection
        self.refresh_count += 1


def test_challenge_runner_owns_retry_and_refresh_policy() -> None:
    strategy = _RetryStrategy(solved_at=2)
    result = ChallengeRunner().run(
        _Page(),
        strategy,
        ChallengePolicy(max_attempts=3, first_detection_timeout_ms=10),
    )

    assert result.solved is True
    assert result.attempts == 2
    assert strategy.solve_count == 2
    assert strategy.refresh_count == 1


class _Request:
    method = "POST"


class _SignupResponse:
    url = "https://chat.qwen.ai/api/v1/auths/signup"
    request = _Request()
    status = 200
    headers: ClassVar[dict[str, str]] = {"content-type": "application/json"}

    def json(self) -> dict[str, str]:
        return {"token": "fixture-token", "id": "fixture-user"}


def test_qwen_strategy_uses_signup_response_as_success_oracle() -> None:
    strategy = QwenSignupChallenge()
    strategy._observe_response(_SignupResponse())

    assert strategy.succeeded() is True
    assert strategy.token == "fixture-token"
    assert strategy.user_id == "fixture-user"


def test_qwen_fusion_reproduces_verified_success_sample() -> None:
    value, confidence = _fuse_qwen_gap(
        [
            SolverEstimate(solver="ddddocr_crop", value=68, confidence=0.88),
            SolverEstimate(solver="ddddocr_full", value=191, confidence=0.75),
            SolverEstimate(solver="recognizer", value=165, confidence=0.95),
            SolverEstimate(solver="opencv", value=159, confidence=0.52),
        ]
    )

    assert value == 163
    assert 0.52 <= confidence <= 0.54


def test_longcat_gap_mapping_accounts_for_visible_piece_offset() -> None:
    plan = _map_longcat_gap(
        gap_left=180,
        piece_left=0,
        max_travel=252,
        image_width=296,
        piece_width=44,
        piece_offset=23,
        fudge=0,
    )

    assert plan.target_piece == 157
    assert plan.drag_distance == 157


def test_longcat_gap_mapping_rejects_home_strip_false_match() -> None:
    try:
        _map_longcat_gap(15, 0, 252, 296, 44, 0, 0)
    except RuntimeError as error:
        assert "home piece strip" in str(error)
        return
    raise AssertionError("left-edge estimate should be rejected")


def test_longcat_piece_geometry_uses_background_delta_not_alpha_strip() -> None:
    background = Image.new("RGBA", (100, 80), "white")
    moving_strip = Image.new("RGBA", (40, 80), "white")
    ImageDraw.Draw(moving_strip).rectangle((10, 20, 27, 45), fill="black")
    background_bytes = io.BytesIO()
    piece_bytes = io.BytesIO()
    background.save(background_bytes, format="PNG")
    moving_strip.save(piece_bytes, format="PNG")

    geometry = _extract_longcat_piece(background_bytes.getvalue(), piece_bytes.getvalue())

    assert geometry is not None
    assert 9 <= geometry.offset_x <= 11
    assert 17 <= geometry.visual_width <= 19


def test_longcat_tap_solver_keeps_four_targets_separated_by_small_gaps() -> None:
    target = Image.new("RGB", (108, 28), "white")
    panel = Image.new("RGB", (260, 160), "white")
    target_draw = ImageDraw.Draw(target)
    panel_draw = ImageDraw.Draw(panel)
    origins = ((5, 4), (31, 4), (57, 4), (83, 4))
    placements = ((20, 18), (155, 20), (35, 105), (185, 98))

    def draw_icon(draw: ImageDraw.ImageDraw, kind: int, x: int, y: int) -> None:
        if kind == 0:
            draw.rectangle((x + 2, y + 2, x + 19, y + 19), outline="black", width=3)
        elif kind == 1:
            draw.ellipse((x + 2, y + 2, x + 19, y + 19), outline="black", width=3)
        elif kind == 2:
            draw.polygon(((x + 10, y + 1), (x + 20, y + 20), (x + 1, y + 20)), outline="black")
        else:
            draw.line((x + 3, y + 3, x + 18, y + 18), fill="black", width=3)
            draw.line((x + 18, y + 3, x + 3, y + 18), fill="black", width=3)

    for index, ((target_x, target_y), (panel_x, panel_y)) in enumerate(
        zip(origins, placements, strict=True)
    ):
        draw_icon(target_draw, index, target_x, target_y)
        draw_icon(panel_draw, index, panel_x, panel_y)

    target_bytes = io.BytesIO()
    panel_bytes = io.BytesIO()
    target.save(target_bytes, format="PNG")
    panel.save(panel_bytes, format="PNG")

    estimate = registry._solve_tap_sync(target_bytes.getvalue(), panel_bytes.getvalue())

    assert estimate is not None
    assert len(estimate.value) == 4
    assert estimate.detail == "targets=4"


def test_browser_profile_only_overrides_user_agent_for_patchright() -> None:
    profile = BrowserContextProfile(
        locale="en-US",
        viewport_width=1280,
        viewport_height=900,
        patchright_user_agent="fixture-agent",
    )

    assert profile.options("camoufox")["locale"] == "en-US"
    assert "user_agent" not in profile.options("camoufox")
    assert profile.options("patchright")["user_agent"] == "fixture-agent"


def test_launch_profile_parses_explicit_boolean_without_string_truthiness() -> None:
    profile = BrowserLaunchProfile(headless=False)

    assert profile.resolve_headless({}, True) is False
    assert profile.resolve_headless({"headless": "true"}, False) is True
    assert profile.resolve_headless({"headless": "false"}, True) is False


def test_fingerprint_policy_resolves_one_coherent_variant() -> None:
    policy = BrowserFingerprintPolicy(
        variants=(
            BrowserFingerprintVariant(
                id="fixture-windows",
                os="windows",
                locale="zh-CN",
                timezone_id="Asia/Shanghai",
                viewport_width=1440,
                viewport_height=900,
                accept_language="zh-CN,zh;q=0.9",
            ),
        )
    )

    context, launch, variant_id = policy.resolve(BrowserContextProfile(), BrowserLaunchProfile())

    assert variant_id == "fixture-windows"
    assert context.locale == "zh-CN"
    assert context.timezone_id == "Asia/Shanghai"
    assert (context.viewport_width, context.viewport_height) == (1440, 900)
    assert launch.camoufox_os == "windows"
    assert launch.camoufox_fingerprint_preset is False


def test_provider_launch_profiles_reproduce_verified_browser_baselines() -> None:
    qwen = QwenAutomationProvider()
    longcat = LongcatAutomationProvider()

    assert qwen.browser_launch_profile().headless is False
    assert longcat.manifest.browser_backend == "patchright"
    assert longcat.browser_launch_profile().headless is True
    assert "--disable-blink-features=AutomationControlled" in (
        longcat.browser_launch_profile().patchright_args
    )
    assert longcat.browser_launch_profile().patchright_ignore_default_args == (
        "--enable-automation",
    )


class _JsonResponse:
    def json(self) -> dict[str, object]:
        return {"code": 0, "data": {"serialNumber": "fixture-serial"}}


def test_longcat_apply_requires_structured_serial_number() -> None:
    assert _response_serial(_JsonResponse()) == "fixture-serial"


class _VisionResponse:
    def raise_for_status(self) -> None:
        return None

    def json(self) -> dict[str, object]:
        return {"choices": [{"message": {"content": "POINTS=0.20,0.30;0.65,0.55;0.80,0.20"}}]}


class _VisionTextResponse:
    def raise_for_status(self) -> None:
        return None

    def json(self) -> dict[str, object]:
        return {"choices": [{"message": {"content": "`aB7x`\nignore this explanation"}}]}


class _VisionActionResponse:
    def raise_for_status(self) -> None:
        return None

    def json(self) -> dict[str, object]:
        return {
            "choices": [
                {"message": {"content": ('ACTIONS=[{"type":"drag","from":[74,23],"to":[28,32]}]')}}
            ]
        }


class _VisionInvalidActionResponse:
    def raise_for_status(self) -> None:
        return None

    def json(self) -> dict[str, object]:
        return {"choices": [{"message": {"content": "unable to format the result"}}]}


class _VisionAccountUnavailableResponse:
    status_code = 502
    headers: ClassVar[dict[str, str]] = {
        "X-Any2API-Provider": "minmax",
        "X-Any2API-Model": "MiniMax-M3",
    }

    def raise_for_status(self) -> None:
        request = httpx.Request("POST", "https://gateway.example")
        response = httpx.Response(502, request=request, headers=self.headers)
        raise httpx.HTTPStatusError("unavailable", request=request, response=response)

    def json(self) -> dict[str, object]:
        return {"error": {"type": "account_unavailable"}}


def test_visual_solver_parses_normalized_points_without_exposing_response(
    monkeypatch,
) -> None:
    monkeypatch.setenv("ANY2API_AUTOMATION_CAPTCHA_AI_ENABLED", "true")
    monkeypatch.setenv("ANY2API_AUTOMATION_JAVA_BASE_URL", "https://gateway.example")
    monkeypatch.setenv("ANY2API_PUBLIC_API_KEY", "fixture-secret")
    settings.cache_clear()
    request: dict[str, object] = {}

    def post(url, **kwargs):
        request.update({"url": url, **kwargs})
        return _VisionResponse()

    monkeypatch.setattr(
        "any2api_automation.captcha.registry.httpx.post",
        post,
    )

    estimate = registry.solve_visual_points_sync(b"fixture-image", "fixture prompt")

    settings.cache_clear()
    assert estimate is not None
    assert estimate.solver == "vision_points"
    assert estimate.value == [(0.2, 0.3), (0.65, 0.55), (0.8, 0.2)]
    assert request["url"] == "https://gateway.example/multimodal-random/v1/chat/completions"
    assert request["json"]["model"] == "random"
    assert request["json"]["reasoning_effort"] == "none"
    assert "max_completion_tokens" not in request["json"]
    assert request["headers"] == {"Authorization": "Bearer fixture-secret"}


def test_visual_solver_prepends_private_shared_prompt(monkeypatch) -> None:
    monkeypatch.setenv("ANY2API_AUTOMATION_CAPTCHA_AI_ENABLED", "true")
    monkeypatch.setenv("ANY2API_PUBLIC_API_KEY", "fixture-secret")
    monkeypatch.setenv("ANY2API_AUTOMATION_CAPTCHA_AI_PROMPT_PREFIX", "shared header")
    settings.cache_clear()
    request: dict[str, object] = {}

    def post(url, **kwargs):
        del url
        request.update(kwargs)
        return _VisionResponse()

    monkeypatch.setattr("any2api_automation.captcha.registry.httpx.post", post)

    registry.solve_visual_points_sync(b"fixture-image", "provider prompt")

    settings.cache_clear()
    content = request["json"]["messages"][1]["content"]
    assert content[0]["text"] == "shared header\n\nprovider prompt"


def test_visual_text_solver_keeps_only_captcha_characters(monkeypatch) -> None:
    monkeypatch.setenv("ANY2API_AUTOMATION_CAPTCHA_AI_ENABLED", "true")
    monkeypatch.setenv("ANY2API_PUBLIC_API_KEY", "fixture-secret")
    settings.cache_clear()
    monkeypatch.setattr(
        "any2api_automation.captcha.registry.httpx.post",
        lambda *args, **kwargs: _VisionTextResponse(),
    )

    estimate = registry.solve_visual_text_sync(b"fixture-image", "fixture prompt")

    settings.cache_clear()
    assert estimate is not None
    assert estimate.solver == "vision_text"
    assert estimate.value == "aB7x"


def test_visual_completion_reroutes_transient_random_account_contention(monkeypatch) -> None:
    monkeypatch.setenv("ANY2API_AUTOMATION_CAPTCHA_AI_ENABLED", "true")
    monkeypatch.setenv("ANY2API_PUBLIC_API_KEY", "fixture-secret")
    settings.cache_clear()
    responses = iter((_VisionAccountUnavailableResponse(), _VisionActionResponse()))
    calls = 0

    def post(*args, **kwargs):
        nonlocal calls
        calls += 1
        return next(responses)

    monkeypatch.setattr("any2api_automation.captcha.registry.httpx.post", post)

    content = registry._visual_completion_sync(
        b"fixture-image", "fixture prompt", max_tokens=100, timeout_seconds=10
    )

    settings.cache_clear()
    assert calls == 2
    assert content.startswith("ACTIONS=")
    assert "attempt=2" in registry.visual_diagnostic()


def test_visual_text_candidate_extracts_structured_or_emphasized_answers_only() -> None:
    assert _captcha_text_candidate("CAPTCHA=A91X") == "A91X"
    assert _captcha_text_candidate("The four characters are: **A01X**") == "A01X"
    assert _captcha_text_candidate("`aB7x`") == "aB7x"
    assert _captcha_text_candidate("I cannot determine the captcha") is None


def test_visual_choice_solver_requires_a_unique_majority(monkeypatch) -> None:
    monkeypatch.setenv("ANY2API_AUTOMATION_CAPTCHA_AI_ACTION_SAMPLES", "5")
    settings.cache_clear()
    responses = iter(("CHOICE=B", "CHOICE=B", "CHOICE=B", "CHOICE=A", "CHOICE=C"))
    prompts: list[str] = []

    def complete(image, prompt, **kwargs):
        del image, kwargs
        prompts.append(prompt)
        return next(responses)

    monkeypatch.setattr(
        registry,
        "_visual_completion_sync",
        complete,
    )

    choice = registry.solve_visual_choice_sync(
        b"fixture-image",
        "Choose a panel.",
        ("A", "B", "C"),
    )

    settings.cache_clear()
    assert choice == "B"
    assert all(prompt.startswith("The first characters") for prompt in prompts)
    assert "choice_consensus:B:3/5" in registry.visual_diagnostic()


def test_visual_choice_solver_rejects_ties_and_unknown_labels(monkeypatch) -> None:
    monkeypatch.setenv("ANY2API_AUTOMATION_CAPTCHA_AI_ACTION_SAMPLES", "5")
    settings.cache_clear()
    responses = iter(("CHOICE=A", "CHOICE=A", "CHOICE=B", "CHOICE=B", "CHOICE=Z"))
    monkeypatch.setattr(
        registry,
        "_visual_completion_sync",
        lambda *args, **kwargs: next(responses),
    )

    choice = registry.solve_visual_choice_sync(
        b"fixture-image",
        "Choose a panel.",
        ("A", "B", "C"),
    )

    settings.cache_clear()
    assert choice is None
    assert "votes=A:2,B:2" in registry.visual_diagnostic()


def test_visual_choice_votes_count_each_provider_once() -> None:
    votes, summary = registry._visual_provider_choice_votes(
        [
            ("A", "response_received:provider=qwen:model=one", 0),
            ("A", "response_received:provider=qwen:model=one", 1),
            ("B", "response_received:provider=mimo:model=two", 2),
            ("B", "response_received:provider=minmax:model=three", 3),
        ]
    )

    assert votes == ["A", "B", "B"]
    assert "qwen=A" in summary
    assert "mimo=B" in summary
    assert "minmax=B" in summary


def test_visual_choice_accepts_strong_samples_from_multiple_provider_sources(monkeypatch) -> None:
    monkeypatch.setenv("ANY2API_AUTOMATION_CAPTCHA_AI_ACTION_SAMPLES", "5")
    settings.cache_clear()
    responses = iter(
        (
            ("CHOICE=C", "response_received:provider=qwen:model=one"),
            ("CHOICE=C", "response_received:provider=qwen:model=one"),
            ("CHOICE=C", "response_received:provider=qwen:model=one"),
            ("CHOICE=C", "response_received:provider=mimo:model=two"),
            ("CHOICE=B", "response_received:provider=mimo:model=two"),
        )
    )

    def complete(*args, **kwargs):
        del args, kwargs
        content, diagnostic = next(responses)
        registry._diagnostics.visual = diagnostic
        return content

    monkeypatch.setattr(registry, "_visual_completion_sync", complete)

    choice = registry.solve_visual_choice_sync(b"fixture-image", "Choose.", ("A", "B", "C"))

    settings.cache_clear()
    assert choice == "C"
    assert "choice_strong_samples:C:4/5:sources=2" in registry.visual_diagnostic()


def test_visual_choice_rejects_strong_samples_from_one_provider(monkeypatch) -> None:
    monkeypatch.setenv("ANY2API_AUTOMATION_CAPTCHA_AI_ACTION_SAMPLES", "5")
    settings.cache_clear()
    responses = iter(("CHOICE=C", "CHOICE=C", "CHOICE=C", "CHOICE=C", "CHOICE=B"))

    def complete(*args, **kwargs):
        del args, kwargs
        registry._diagnostics.visual = "response_received:provider=qwen:model=one"
        return next(responses)

    monkeypatch.setattr(registry, "_visual_completion_sync", complete)

    choice = registry.solve_visual_choice_sync(b"fixture-image", "Choose.", ("A", "B", "C"))

    settings.cache_clear()
    assert choice is None


def test_visual_action_solver_normalizes_percentage_coordinates(monkeypatch) -> None:
    monkeypatch.setenv("ANY2API_AUTOMATION_CAPTCHA_AI_ENABLED", "true")
    monkeypatch.setenv("ANY2API_PUBLIC_API_KEY", "fixture-secret")
    monkeypatch.setenv("ANY2API_AUTOMATION_CAPTCHA_AI_ACTION_SAMPLES", "3")
    settings.cache_clear()
    monkeypatch.setattr(
        "any2api_automation.captcha.registry.httpx.post",
        lambda *args, **kwargs: _VisionActionResponse(),
    )

    fixture = io.BytesIO()
    Image.new("RGB", (200, 100), "white").save(fixture, format="PNG")
    actions = registry.solve_visual_actions_sync(fixture.getvalue(), "fixture prompt")

    settings.cache_clear()
    assert len(actions) == 1
    assert actions[0].type == "drag"
    assert actions[0].start == (0.74, 0.23)
    assert actions[0].end == (0.28, 0.32)
    assert "consensus:3/3" in registry.visual_diagnostic()


def test_visual_action_solver_aggregates_same_image_random_samples(
    monkeypatch,
) -> None:
    monkeypatch.setenv("ANY2API_AUTOMATION_CAPTCHA_AI_ENABLED", "true")
    monkeypatch.setenv("ANY2API_PUBLIC_API_KEY", "fixture-secret")
    monkeypatch.setenv("ANY2API_AUTOMATION_CAPTCHA_AI_ACTION_SAMPLES", "3")
    settings.cache_clear()
    responses = iter(
        (_VisionInvalidActionResponse(), _VisionActionResponse(), _VisionActionResponse())
    )
    calls: list[str] = []

    def post(*args, **kwargs):
        calls.append(kwargs["json"]["messages"][1]["content"][1]["image_url"]["url"])
        return next(responses)

    monkeypatch.setattr("any2api_automation.captcha.registry.httpx.post", post)
    fixture = io.BytesIO()
    Image.new("RGB", (200, 100), "white").save(fixture, format="PNG")

    actions = registry.solve_visual_actions_sync(
        fixture.getvalue(),
        "fixture prompt",
        timeout_seconds=10,
    )

    settings.cache_clear()
    assert len(actions) == 1
    assert len(calls) == 3
    assert calls[0] == calls[1] == calls[2]
    assert "consensus:2/3" in registry.visual_diagnostic()


def test_visual_action_consensus_uses_majority_cluster_median() -> None:
    samples = [
        [VisualAction(type="drag", start=(0.05, 0.5), end=(0.75, 0.5))],
        [VisualAction(type="drag", start=(0.07, 0.5), end=(0.79, 0.5))],
        [VisualAction(type="drag", start=(0.2, 0.5), end=(0.4, 0.5))],
    ]

    actions = registry._visual_action_consensus(samples, 3)

    assert len(actions) == 1
    assert tuple(round(value, 2) for value in actions[0].start) == (0.06, 0.5)
    assert tuple(round(value, 2) for value in actions[0].end) == (0.77, 0.5)
    assert "consensus:2/3" in registry.visual_diagnostic()


def test_visual_action_consensus_rejects_broad_single_center_neighborhood() -> None:
    samples = [
        [VisualAction(type="drag", start=(0, 0.5), end=(0.36, 0.5))],
        [VisualAction(type="drag", start=(0, 0.5), end=(0.42, 0.5))],
        [VisualAction(type="drag", start=(0, 0.5), end=(0.47, 0.5))],
        [VisualAction(type="drag", start=(0, 0.5), end=(0.57, 0.5))],
    ]

    assert registry._visual_action_consensus(samples, 5) == []


def test_visual_action_solver_reports_only_safe_vote_diagnostics(monkeypatch) -> None:
    monkeypatch.setenv("ANY2API_AUTOMATION_CAPTCHA_AI_ACTION_SAMPLES", "3")
    settings.cache_clear()
    responses = iter(
        (
            'ACTIONS=[{"type":"drag","from":[0,50],"to":[20,50]}]',
            'ACTIONS=[{"type":"drag","from":[0,50],"to":[50,50]}]',
            'ACTIONS=[{"type":"drag","from":[0,50],"to":[80,50]}]',
        )
    )
    monkeypatch.setattr(
        registry,
        "_visual_completion_sync",
        lambda *args, **kwargs: next(responses),
    )
    fixture = io.BytesIO()
    Image.new("RGB", (200, 100), "white").save(fixture, format="PNG")

    actions = registry.solve_visual_actions_sync(fixture.getvalue(), "private prompt")

    settings.cache_clear()
    diagnostic = registry.visual_diagnostic()
    assert actions == []
    assert "drag[0.000,0.500,0.200,0.500]" in diagnostic
    assert "private prompt" not in diagnostic


def test_visual_action_batch_has_an_absolute_wall_clock_deadline(monkeypatch) -> None:
    monkeypatch.setenv("ANY2API_AUTOMATION_CAPTCHA_AI_ACTION_SAMPLES", "2")
    monkeypatch.setenv("ANY2API_AUTOMATION_CAPTCHA_AI_ACTION_SAMPLE_TIMEOUT_SECONDS", "1")
    settings.cache_clear()
    release = threading.Event()

    def blocked(*args, **kwargs):
        release.wait(5)
        return ""

    monkeypatch.setattr(registry, "_visual_completion_sync", blocked)
    fixture = io.BytesIO()
    Image.new("RGB", (200, 100), "white").save(fixture, format="PNG")

    started = time.monotonic()
    actions = registry.solve_visual_actions_sync(fixture.getvalue(), "fixture prompt")
    elapsed = time.monotonic() - started
    release.set()

    settings.cache_clear()
    assert actions == []
    assert elapsed < 2
    assert "completed=0/2" in registry.visual_diagnostic()


class _GlmSuccessPage:
    def __init__(self) -> None:
        self.state = {"status": "missing", "ticket": ""}
        self.screenshot_called = False

    def evaluate(self, script: str, argument: object | None = None) -> object:
        if argument is not None:
            self.state = {"status": "success", "ticket": "t" * 64}
            return None
        return dict(self.state)

    def wait_for_timeout(self, milliseconds: int) -> None:
        return None

    def screenshot(self, **kwargs: object) -> bytes:
        self.screenshot_called = True
        return b"should-not-be-used"


def test_glm_waits_for_delayed_official_callback() -> None:
    class Page:
        def __init__(self) -> None:
            self.reads = 0

        def evaluate(self, script: str) -> dict[str, str]:
            del script
            self.reads += 1
            if self.reads < 4:
                return {"status": "running", "ticket": ""}
            return {"status": "success", "ticket": "t" * 64}

        def wait_for_timeout(self, milliseconds: int) -> None:
            del milliseconds

    state = GlmAliyunChallenge()._wait_for_challenge_result(Page(), 2)

    assert state["status"] == "success"


class _GlmMouse:
    def __init__(self) -> None:
        self.events: list[tuple[object, ...]] = []

    def move(self, x: float, y: float, **kwargs: object) -> None:
        self.events.append(("move", x, y))

    def click(self, x: float, y: float, **kwargs: object) -> None:
        self.events.append(("click", x, y))

    def down(self) -> None:
        self.events.append(("down",))

    def up(self) -> None:
        self.events.append(("up",))


class _GlmActionPage:
    def __init__(self) -> None:
        self.mouse = _GlmMouse()

    def evaluate(self, script: str) -> object:
        return {"width": 1000, "height": 500}

    def wait_for_timeout(self, milliseconds: int) -> None:
        return None


def test_glm_challenge_accepts_only_official_success_ticket_without_ai() -> None:
    page = _GlmSuccessPage()
    challenge = GlmAliyunChallenge()

    ticket = challenge.solve(page)

    assert len(ticket) == 64
    assert challenge.last_diagnostic == "mode=traceless, attempts=0, ticket=accepted"
    assert page.screenshot_called is False


def test_glm_challenge_uses_separate_authentication_profile() -> None:
    challenge = GlmAliyunChallenge.for_authentication()
    chat = GlmAliyunChallenge.for_chat()

    assert challenge.profile.scene_id == "36qgs6xb"
    assert challenge.profile.mode == "embed"
    assert challenge.profile.region == "cn"
    assert challenge.profile.semantic_slider is True
    assert chat.profile.scene_id == "didk33e0"
    assert chat.profile.mode == "popup"
    assert chat.profile.region == "sgp"


def test_glm_challenge_observes_provider_owned_captcha_before_navigation() -> None:
    class Page:
        def __init__(self) -> None:
            self.scripts: list[str] = []

        def add_init_script(self, script: str) -> None:
            self.scripts.append(script)

    page = Page()

    GlmAliyunChallenge.for_authentication().arm_official(page)

    assert len(page.scripts) == 1
    assert "Object.defineProperty(window, 'initAliyunCaptcha'" in page.scripts[0]
    assert "__any2apiGlmCaptchaRawInit" in page.scripts[0]
    assert "originalSuccess" in page.scripts[0]


def test_glm_challenge_prefers_captured_official_initialization(monkeypatch) -> None:
    class Page:
        def __init__(self) -> None:
            self.states = iter(
                (
                    {"status": "loading", "ticket": ""},
                    {"status": "running", "ticket": ""},
                )
            )

        def evaluate(self, script: str) -> dict[str, str]:
            del script
            return next(self.states)

        def wait_for_timeout(self, milliseconds: int) -> None:
            del milliseconds

    challenge = GlmAliyunChallenge.for_authentication()

    def unexpected_fallback(_page: object) -> None:
        raise AssertionError("captured official captcha must not use fallback installation")

    monkeypatch.setattr(
        challenge,
        "_install",
        unexpected_fallback,
    )

    assert challenge._use_official_initialization(Page()) is True


def test_glm_challenge_starts_current_official_embed_control() -> None:
    class Locator:
        def __init__(self, *, visible: bool, text: str = "") -> None:
            self.first = self
            self.visible = visible
            self.text = text
            self.clicked = False

        def count(self) -> int:
            return int(self.visible)

        def is_visible(self) -> bool:
            return self.visible

        def text_content(self) -> str:
            return self.text

        def click(self) -> None:
            self.clicked = True

    icon = Locator(visible=False)
    body = Locator(visible=True, text="Click to start verification")

    class Page:
        def evaluate(self, script: str) -> bool:
            del script
            return False

        def locator(self, selector: str) -> Locator:
            if selector == "#aliyunCaptcha-start-icon":
                return icon
            if selector == "#aliyunCaptcha-captcha-body":
                return body
            raise AssertionError(f"unexpected selector: {selector}")

    assert GlmAliyunChallenge.for_authentication()._start_if_required(Page()) is True
    assert body.clicked is True


def test_glm_captcha_loader_bounds_existing_script_race() -> None:
    from any2api_automation.providers.glm_challenge import _INSTALL_CAPTCHA

    assert "waitForInitializer(5000)" in _INSTALL_CAPTCHA
    assert "captcha script load failed" in _INSTALL_CAPTCHA
    assert "_any2api=${Date.now()}" in _INSTALL_CAPTCHA
    assert "__any2apiGlmCaptchaRawInit" in _INSTALL_CAPTCHA


def test_glm_challenge_maps_normalized_click_and_drag_to_viewport() -> None:
    page = _GlmActionPage()
    challenge = GlmAliyunChallenge()

    challenge._execute(
        page,
        [
            VisualAction(type="click", at=(0.25, 0.4)),
            VisualAction(type="drag", start=(0.75, 0.2), end=(0.3, 0.6)),
        ],
    )

    assert ("click", 250.0, 200.0) in page.mouse.events
    assert ("move", 750.0, 100.0) in page.mouse.events
    assert page.mouse.events[-1] == ("up",)


def test_glm_slider_anchors_model_displacement_to_real_handle() -> None:
    challenge = GlmAliyunChallenge()
    surface = GlmCaptchaSurface(b"", x=100, y=200, width=400, height=500)
    action = VisualAction(type="drag", start=(0.2, 0.7), end=(0.45, 0.7))

    start, end = challenge._slider_drag_points(
        action,
        surface,
        {"x": 120, "y": 300, "width": 40, "height": 40},
        300,
    )

    assert start == (140, 320)
    assert end == (215, 320)


def test_glm_slider_falls_back_to_surface_width_without_scene_geometry() -> None:
    challenge = GlmAliyunChallenge()
    surface = GlmCaptchaSurface(b"", x=100, y=200, width=400, height=500)
    action = VisualAction(type="drag", start=(0.2, 0.7), end=(0.45, 0.7))

    start, end = challenge._slider_drag_points(
        action,
        surface,
        {"x": 120, "y": 300, "width": 40, "height": 40},
        0,
    )

    assert start == (140, 320)
    assert end == (240, 320)


def test_glm_slider_calibrates_sdk_non_linear_piece_motion() -> None:
    class Mouse:
        def __init__(self, page) -> None:
            self.page = page
            self.down_state = False

        def move(self, x: float, y: float, steps: int | None = None) -> None:
            del y, steps
            if self.down_state:
                handle_delta = max(0.0, min(260.0, x - 590.0))
                self.page.piece_x = 570.0 + 281.0 * (handle_delta / 260.0) ** 2

        def down(self) -> None:
            self.down_state = True

        def up(self) -> None:
            self.down_state = False

    class Locator:
        def __init__(self, page, selector: str) -> None:
            self.page = page
            self.selector = selector

        @property
        def first(self):
            return self

        def bounding_box(self) -> dict[str, float]:
            if self.selector == "#aliyunCaptcha-img":
                return {"x": 570, "y": 291.5, "width": 300, "height": 300}
            if self.selector == "#aliyunCaptcha-puzzle":
                return {"x": self.page.piece_x, "y": 291.5, "width": 19, "height": 300}
            assert self.selector == "#aliyunCaptcha-sliding-body"
            return {"x": 570, "y": 599.5, "width": 300, "height": 40}

    class Page:
        def __init__(self) -> None:
            self.piece_x = 570.0
            self.mouse = Mouse(self)

        def locator(self, selector: str) -> Locator:
            return Locator(self, selector)

        def wait_for_timeout(self, milliseconds: int) -> None:
            del milliseconds

    page = Page()
    diagnostic = GlmAliyunChallenge()._drag_slider_to_scene_target(
        page,
        VisualAction(type="drag", start=(0.032, 0.5), end=(0.515, 0.5)),
        GlmCaptchaSurface(b"", x=570, y=291.5, width=300, height=300, slider=True),
        {"x": 570, "y": 599.5, "width": 40, "height": 40},
        300,
    )

    desired_piece_center = 570 + 0.515 * 300
    actual_piece_center = page.piece_x + 19 / 2
    assert diagnostic is not None
    assert "adaptive_curve=2.000" in diagnostic
    assert abs(actual_piece_center - desired_piece_center) <= 1.5
    assert page.mouse.down_state is False


def test_glm_slider_uses_local_estimates_only_when_they_reach_consensus() -> None:
    challenge = GlmAliyunChallenge()

    action, candidates = challenge._slider_action_from_estimates(
        [
            SolverEstimate(solver="ddddocr", value=94, confidence=0.82),
            SolverEstimate(solver="opencv", value=88, confidence=0.24),
        ],
        300,
    )

    assert candidates == [88, 94]
    assert action is not None
    assert action.start == (0, 0.5)
    assert action.end == (91 / 300, 0.5)

    action, candidates = challenge._slider_action_from_estimates(
        [
            SolverEstimate(solver="ddddocr", value=194, confidence=0.82),
            SolverEstimate(solver="opencv", value=148, confidence=0.24),
        ],
        300,
    )

    assert action is None
    assert candidates == [148, 194]


def test_glm_semantic_slider_refreshes_when_semantic_sources_are_unavailable(monkeypatch) -> None:
    challenge = GlmAliyunChallenge()
    surface = GlmCaptchaSurface(b"rendered", x=10, y=20, width=300, height=300, slider=True)
    monkeypatch.setattr(
        challenge,
        "_semantic_slider_input",
        lambda page, image: GlmSemanticSliderInput(b"semantic-input", 0.05),
    )
    monkeypatch.setattr(
        registry,
        "solve_visual_actions_sync",
        lambda *args, **kwargs: (_ for _ in ()).throw(
            AssertionError("incomplete semantic input must never produce a blind drag")
        ),
    )

    actions = challenge._solve_actions(object(), surface, 42)

    assert actions == []
    assert "semantic_sources_unavailable" in challenge.last_diagnostic


def test_glm_semantic_slider_never_executes_false_local_consensus(monkeypatch) -> None:
    challenge = GlmAliyunChallenge()
    surface = GlmCaptchaSurface(b"rendered", x=10, y=20, width=300, height=300, slider=True)

    def unexpected_local_solver(page, captured):
        raise AssertionError("semantic slider must not use geometric local consensus")

    monkeypatch.setattr(challenge, "_local_slider_action", unexpected_local_solver)
    monkeypatch.setattr(
        challenge,
        "_semantic_slider_input",
        lambda page, image: GlmSemanticSliderInput(b"semantic-input", 0.02),
    )
    assert challenge._solve_actions(object(), surface, 42) == []


def test_glm_semantic_slider_never_calls_ai_action_solver(monkeypatch) -> None:
    challenge = GlmAliyunChallenge()
    surface = GlmCaptchaSurface(b"rendered", x=10, y=20, width=300, height=300, slider=True)
    monkeypatch.setattr(
        challenge,
        "_semantic_slider_input",
        lambda page, image: GlmSemanticSliderInput(
            b"semantic-input",
            0.05,
            b"background",
            b"piece",
        ),
    )
    monkeypatch.setattr(
        registry,
        "solve_visual_actions_sync",
        lambda *args, **kwargs: (_ for _ in ()).throw(
            AssertionError("GLM semantic captcha must not invoke the AI action solver")
        ),
    )

    assert challenge._solve_actions(object(), surface, 42) == []
    assert "decision=refresh" in challenge.last_diagnostic


def test_glm_non_slider_visual_challenge_never_calls_ai(monkeypatch) -> None:
    challenge = GlmAliyunChallenge()
    surface = GlmCaptchaSurface(b"rendered", x=10, y=20, width=300, height=300)
    monkeypatch.setattr(
        registry,
        "solve_visual_actions_sync",
        lambda *args, **kwargs: (_ for _ in ()).throw(
            AssertionError("unsupported GLM visual challenges must refresh without AI")
        ),
    )

    assert challenge._solve_actions(object(), surface, 42) == []
    assert "deterministic_only" in challenge.last_diagnostic


def test_glm_semantic_slider_input_magnifies_detached_object() -> None:
    scene = Image.new("RGB", (300, 300), "white")
    piece = Image.new("RGBA", (300, 300), (0, 0, 0, 0))
    ImageDraw.Draw(piece).rectangle((2, 130, 12, 142), fill="black")
    scene_bytes = io.BytesIO()
    piece_bytes = io.BytesIO()
    scene.save(scene_bytes, format="PNG")
    piece.save(piece_bytes, format="PNG")

    result = GlmAliyunChallenge()._compose_semantic_slider_input(
        scene_bytes.getvalue(),
        piece_bytes.getvalue(),
    )

    with Image.open(io.BytesIO(result)) as composed:
        assert composed.size == (640, 340)
        assert composed.convert("RGB").getpixel((160, 180)) == (0, 0, 0)
        assert composed.convert("RGB").getpixel((336, 161)) == (255, 255, 255)


def test_glm_semantic_slider_extracts_object_from_rendered_background_delta() -> None:
    background = Image.new("RGB", (300, 300), "white")
    scene = background.copy()
    ImageDraw.Draw(scene).ellipse((3, 120, 25, 138), fill="black")
    empty_piece = Image.new("RGBA", (300, 300), (0, 0, 0, 0))

    def png(image: Image.Image) -> bytes:
        output = io.BytesIO()
        image.save(output, format="PNG")
        return output.getvalue()

    result = GlmAliyunChallenge()._compose_semantic_slider_input(
        png(scene),
        png(empty_piece),
        png(background),
    )

    with Image.open(io.BytesIO(result)) as composed:
        assert composed.size == (640, 340)
        assert composed.convert("RGB").getpixel((160, 180)) == (0, 0, 0)


def test_glm_semantic_slider_always_provides_left_edge_fallback_panel() -> None:
    scene = Image.new("RGB", (300, 300), "white")
    ImageDraw.Draw(scene).text((2, 220), "5", fill="black")
    empty_piece = Image.new("RGBA", (300, 300), (0, 0, 0, 0))

    def png(image: Image.Image) -> bytes:
        output = io.BytesIO()
        image.save(output, format="PNG")
        return output.getvalue()

    result = GlmAliyunChallenge()._compose_semantic_slider_input(
        png(scene),
        png(empty_piece),
    )

    with Image.open(io.BytesIO(result)) as composed:
        assert composed.size == (640, 340)
        panel = composed.convert("RGB").crop((20, 40, 300, 320))
        assert ImageChops.difference(panel, Image.new("RGB", panel.size, "white")).getbbox()


def test_glm_semantic_candidate_sheet_places_real_foreground_at_each_center() -> None:
    background = Image.new("RGB", (300, 300), "white")
    piece = Image.new("RGBA", (300, 300), (0, 0, 0, 0))
    ImageDraw.Draw(piece).rectangle((20, 120, 40, 150), fill="black")

    def png(image: Image.Image) -> bytes:
        output = io.BytesIO()
        image.save(output, format="PNG")
        return output.getvalue()

    result = GlmAliyunChallenge()._candidate_contact_sheet(
        png(background),
        png(piece),
        (0.25, 0.75),
        ("A", "B"),
    )

    with Image.open(io.BytesIO(result)) as sheet:
        first = sheet.convert("RGB").crop((12, 40, 232, 260))
        second = sheet.convert("RGB").crop((244, 40, 464, 260))
        first_box = ImageChops.difference(first, Image.new("RGB", first.size, "white")).getbbox()
        second_box = ImageChops.difference(second, Image.new("RGB", second.size, "white")).getbbox()
        assert first_box is not None
        assert second_box is not None
        assert abs((first_box[0] + first_box[2]) / 2 / 220 - 0.25) < 0.03
        assert abs((second_box[0] + second_box[2]) / 2 / 220 - 0.75) < 0.03


def test_glm_semantic_candidate_sheet_stays_within_gateway_image_budget() -> None:
    background = Image.effect_noise((300, 300), 80).convert("RGB")
    piece = Image.new("RGBA", (30, 300), (0, 0, 0, 0))
    ImageDraw.Draw(piece).ellipse((0, 120, 29, 150), fill="black")

    def png(image: Image.Image) -> bytes:
        output = io.BytesIO()
        image.save(output, format="PNG")
        return output.getvalue()

    result = GlmAliyunChallenge()._candidate_contact_sheet(
        png(background),
        png(piece),
        tuple(index / 10 for index in range(1, 10)),
        tuple("ABCDEFGHI"),
    )

    assert result.startswith(b"\x89PNG\r\n\x1a\n")
    assert len(result) <= 120_000


def test_glm_semantic_candidate_sheet_keeps_detached_object_reference() -> None:
    reference = Image.new("RGB", (640, 340), "white")
    ImageDraw.Draw(reference).rectangle((60, 100, 180, 260), fill="red")
    candidates = Image.new("RGB", (708, 260), "white")
    ImageDraw.Draw(candidates).rectangle((500, 80, 620, 220), fill="blue")

    def png(image: Image.Image) -> bytes:
        output = io.BytesIO()
        image.save(output, format="PNG")
        return output.getvalue()

    result = GlmAliyunChallenge()._candidate_sheet_with_reference(
        png(reference),
        png(candidates),
    )

    assert len(result) <= 120_000
    with Image.open(io.BytesIO(result)) as sheet:
        rgb = sheet.convert("RGB")
        assert rgb.getpixel((130, 190))[0] > 180
        assert rgb.getpixel((560, rgb.height - 80))[2] > 180


def test_glm_semantic_candidate_sheet_preserves_narrow_sdk_piece_geometry() -> None:
    background = Image.new("RGB", (300, 300), "white")
    piece = Image.new("RGBA", (32, 300), (0, 0, 0, 0))
    ImageDraw.Draw(piece).rectangle((0, 120, 31, 150), fill="black")

    def png(image: Image.Image) -> bytes:
        output = io.BytesIO()
        image.save(output, format="PNG")
        return output.getvalue()

    challenge = GlmAliyunChallenge()
    result = challenge._candidate_contact_sheet(
        png(background),
        png(piece),
        (0.25, 0.75),
        ("A", "B"),
    )

    with Image.open(io.BytesIO(result)) as sheet:
        first = sheet.convert("RGB").crop((12, 40, 232, 260))
        first_box = first.convert("L").point(lambda value: 255 if value < 64 else 0).getbbox()
        assert first_box is not None
        assert first_box[2] - first_box[0] < 30
        assert abs((first_box[0] + first_box[2]) / 2 / 220 - 0.25) < 0.03


def test_glm_capture_recognizes_narrow_portrait_slider_scene() -> None:
    class Locator:
        def __init__(self, box: dict[str, float]) -> None:
            self.box = box

        @property
        def first(self):
            return self

        def count(self) -> int:
            return 1

        def is_visible(self) -> bool:
            return True

        def bounding_box(self) -> dict[str, float]:
            return self.box

    class Page:
        def evaluate(self, script: str) -> dict[str, int]:
            assert "innerWidth" in script
            return {"width": 1440, "height": 900}

        def locator(self, selector: str) -> Locator:
            if selector == "#aliyunCaptcha-img":
                return Locator({"x": 620, "y": 300, "width": 186, "height": 265})
            assert selector == "#aliyunCaptcha-sliding-slider"
            return Locator({"x": 570, "y": 600, "width": 40, "height": 40})

        def screenshot(self, **kwargs) -> bytes:
            assert kwargs["clip"]["width"] == 186
            return b"portrait-scene"

    result = GlmAliyunChallenge()._capture_surface(Page())

    assert result.slider is True
    assert result.width == 186
    assert result.height == 265
    assert result.image == b"portrait-scene"


def test_glm_capture_preserves_slider_mode_during_transient_locator_failure() -> None:
    class Locator:
        def __init__(self, page, selector: str) -> None:
            self.page = page
            self.selector = selector

        @property
        def first(self):
            return self

        def count(self) -> int:
            return 1

        def is_visible(self) -> bool:
            if self.selector == "#aliyunCaptcha-img" and self.page.failures == 0:
                self.page.failures += 1
                raise RuntimeError("transient DOM replacement")
            return True

        def bounding_box(self) -> dict[str, float]:
            return {"x": 500, "y": 200, "width": 300, "height": 300}

    class Page:
        def __init__(self) -> None:
            self.failures = 0
            self.waits: list[int] = []

        def locator(self, selector: str) -> Locator:
            return Locator(self, selector)

        def wait_for_timeout(self, milliseconds: int) -> None:
            self.waits.append(milliseconds)

        def screenshot(self, **kwargs: object) -> bytes:
            assert kwargs["clip"] == {"x": 500.0, "y": 200.0, "width": 300.0, "height": 300.0}
            return b"stable-slider-scene"

    page = Page()
    result = GlmAliyunChallenge()._capture_surface(page)

    assert result.slider is True
    assert result.image == b"stable-slider-scene"
    assert page.waits == [100]


def test_glm_semantic_slider_rejects_ambiguous_cv_without_ai(monkeypatch) -> None:
    challenge = GlmAliyunChallenge()
    semantic = GlmSemanticSliderInput(
        b"reference",
        0.05,
        b"background",
        b"piece",
    )
    monkeypatch.setattr(
        "any2api_automation.providers.glm_challenge.estimate_blurred_object_placement",
        lambda background, piece: None,
    )
    monkeypatch.setattr(
        registry,
        "solve_visual_choice_sync",
        lambda *args, **kwargs: (_ for _ in ()).throw(
            AssertionError("ambiguous OpenCV results must not fall back to AI")
        ),
    )

    target, diagnostic = challenge._semantic_slider_target(semantic)

    assert target is None
    assert diagnostic == "opencv_blur=unavailable:decision=refresh"


def test_glm_slider_images_accept_browser_canvas_data_urls() -> None:
    background = Image.new("RGB", (300, 300), "white")
    piece = Image.new("RGBA", (300, 300), (0, 0, 0, 0))
    ImageDraw.Draw(piece).ellipse((2, 120, 18, 136), fill="black")

    def data_url(image: Image.Image) -> str:
        encoded = io.BytesIO()
        image.save(encoded, format="PNG")
        import base64

        return "data:image/png;base64," + base64.b64encode(encoded.getvalue()).decode("ascii")

    class CanvasPage:
        def evaluate(self, script: str) -> dict[str, str]:
            assert "canvas.toDataURL('image/png')" in script
            return {"background": data_url(background), "piece": data_url(piece)}

    background_bytes, piece_bytes = GlmAliyunChallenge()._slider_images(CanvasPage())

    with Image.open(io.BytesIO(background_bytes)) as decoded_background:
        assert decoded_background.size == (300, 300)
    with Image.open(io.BytesIO(piece_bytes)) as decoded_piece:
        assert decoded_piece.size == (300, 300)


def test_glm_semantic_input_waits_for_sources_after_sdk_dom_refresh(monkeypatch) -> None:
    challenge = GlmAliyunChallenge()
    background = Image.new("RGB", (300, 300), "white")
    piece = Image.new("RGBA", (300, 300), (0, 0, 0, 0))
    ImageDraw.Draw(piece).rectangle((10, 120, 30, 150), fill="black")

    def png(image: Image.Image) -> bytes:
        output = io.BytesIO()
        image.save(output, format="PNG")
        return output.getvalue()

    attempts = 0

    def slider_images(page):
        nonlocal attempts
        del page
        attempts += 1
        if attempts < 3:
            raise ValueError("SDK image source is being replaced")
        return png(background), png(piece)

    class Page:
        def evaluate(self, script: str) -> float:
            del script
            return 0.05

        def wait_for_timeout(self, milliseconds: int) -> None:
            assert milliseconds == 100

    monkeypatch.setattr(challenge, "_slider_images", slider_images)

    result = challenge._semantic_slider_input(Page(), png(background))

    assert attempts == 3
    assert result.background
    assert result.piece


def test_registration_trace_reports_last_confirmed_stage() -> None:
    trace = RegistrationTrace("fixture")
    trace.mark(RegistrationStage.FORM_READY)
    trace.mark(RegistrationStage.CHALLENGE_CLEARED)

    assert trace.current == "challenge_cleared"
    assert "stage=challenge_cleared" in str(trace.failure(RuntimeError("failed")))


def test_registration_trace_redacts_sensitive_failure_details(caplog) -> None:
    trace = RegistrationTrace("fixture")
    trace.mark(RegistrationStage.FORM_SUBMITTED)

    failure = trace.failure(
        RuntimeError(
            "request for user@example.com failed at "
            "https://example.com/activate?token=secret "
            "password=guessme token=abc123"
        )
    )

    assert "stage=form_submitted" in str(failure)
    assert "user@example.com" not in caplog.text
    assert "token=secret" not in caplog.text
    assert "guessme" not in caplog.text
    assert "abc123" not in caplog.text
    assert "<email>" in caplog.text
    assert "<url-with-query>" in caplog.text
    assert "password=<redacted>" in caplog.text
