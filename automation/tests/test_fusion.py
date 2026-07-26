from any2api_automation.captcha.fusion import fuse_offsets, fuse_text
from any2api_automation.captcha.models import SolverEstimate


def test_offset_fusion_selects_consistent_cluster() -> None:
    value, confidence, consensus = fuse_offsets(
        [
            SolverEstimate(solver="ddddocr", value=181.0, confidence=0.8),
            SolverEstimate(solver="recognizer", value=184.0, confidence=0.9),
            SolverEstimate(solver="opencv", value=40.0, confidence=0.7),
        ],
        maximum_spread=10,
    )
    assert consensus.accepted
    assert consensus.votes == 2
    assert value is not None and 181 < value < 184
    assert confidence > 0.8


def test_text_fusion_normalizes_case() -> None:
    value, _, consensus = fuse_text(
        [
            SolverEstimate(solver="original", value="a7Kd", confidence=0.7),
            SolverEstimate(solver="contrast", value="A7KD", confidence=0.8),
        ]
    )
    assert value == "A7KD"
    assert consensus.votes == 2
