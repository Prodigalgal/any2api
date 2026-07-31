import os
from pathlib import Path

from any2api_automation.captcha.artifacts import record_captcha_artifact
from any2api_automation.config import settings


def test_captcha_artifacts_are_private_sanitized_and_bounded(monkeypatch, tmp_path: Path) -> None:
    monkeypatch.setenv("ANY2API_AUTOMATION_CAPTCHA_DIAGNOSTICS_DIR", str(tmp_path))
    monkeypatch.setenv("ANY2API_AUTOMATION_CAPTCHA_DIAGNOSTICS_MAX_FILES", "10")
    settings.cache_clear()

    names = [record_captcha_artifact("GLM ../ Semantic", b"image") for _ in range(12)]

    settings.cache_clear()
    assert all(name.startswith("glm-semantic-") for name in names)
    artifacts = list(tmp_path.glob("*.png"))
    assert len(artifacts) == 10
    if os.name != "nt":
        assert all(artifact.stat().st_mode & 0o777 == 0o600 for artifact in artifacts)
