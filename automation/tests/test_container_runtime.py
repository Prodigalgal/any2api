from pathlib import Path


def test_browser_worker_uses_an_init_process_to_reap_children() -> None:
    root = Path(__file__).parents[1]
    dockerfile = (root / "Dockerfile").read_text(encoding="utf-8")
    runtime = (root / "Dockerfile.browser-runtime").read_text(encoding="utf-8")

    assert "tini tzdata xvfb" in runtime
    assert 'ENTRYPOINT ["/usr/bin/tini", "--"]' in dockerfile
    assert "/app/.venv/bin/uvicorn" in dockerfile
    assert "uv sync" not in dockerfile
    assert "patchright install" not in dockerfile
    assert "camoufox fetch" not in dockerfile
