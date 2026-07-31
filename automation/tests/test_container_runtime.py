from pathlib import Path


def test_browser_worker_uses_an_init_process_to_reap_children() -> None:
    dockerfile = (Path(__file__).parents[1] / "Dockerfile").read_text(encoding="utf-8")

    assert "tini tzdata xvfb" in dockerfile
    assert 'ENTRYPOINT ["/usr/bin/tini", "--"]' in dockerfile
