import importlib
import logging
import time

from fastapi.testclient import TestClient

from any2api_automation.main import app, registry

client = TestClient(app)


def test_application_logger_has_an_output_handler() -> None:
    assert logging.getLogger("any2api_automation").handlers


def test_live_health() -> None:
    response = client.get("/health/live")
    assert response.status_code == 200
    assert response.json()["service"] == "any2api-automation"


def test_readiness_failure_is_visible_to_http_probes_without_failing_liveness(monkeypatch):
    monkeypatch.setattr(registry, "capabilities", lambda: {"fixture": {"available": False}})
    response = client.get("/health/ready")
    assert response.status_code == 503
    assert response.json()["ok"] is False
    assert client.get("/health/live").status_code == 200


def test_capabilities_include_all_providers() -> None:
    response = client.get(
        "/internal/v1/capabilities",
        headers={"X-Any2API-Correlation-Id": "capability-correlation-123"},
    )
    assert response.status_code == 200
    assert response.headers["X-Any2API-Correlation-Id"] == "capability-correlation-123"
    providers = response.json()["providers"]
    provider_ids = [item["id"] for item in providers]
    assert provider_ids
    assert provider_ids == sorted(provider_ids)
    assert len(provider_ids) == len(set(provider_ids))
    assert all(
        set(item["operations"]) <= {"register", "reauthenticate", "keepalive", "daily_checkin"}
        and item["operations"]
        for item in providers
    )


def test_provider_internal_routers_are_discovered_from_plugins() -> None:
    response = client.post(
        "/internal/v1/providers/qwen/risk-headers",
        json={"url": "https://example.com", "method": "GET"},
    )
    assert response.status_code == 400

    response = client.post(
        "/internal/v1/providers/glm/browser-sessions/missing/captcha",
        json={"timeout_seconds": 60},
    )
    assert response.status_code == 404


def test_stalled_reaper_fails_readiness_and_then_liveness_without_provider_calls(monkeypatch):
    main = importlib.import_module("any2api_automation.main")
    monkeypatch.setattr(registry, "capabilities", lambda: {"fixture": {"available": True}})
    monkeypatch.setattr(main.process_reaper.stats, "last_sweep_at", time.time() - 130)
    assert client.get("/health/ready").status_code == 503
    assert client.get("/health/live").status_code == 200
    monkeypatch.setattr(main.process_reaper.stats, "last_sweep_at", time.time() - 250)
    assert client.get("/health/live").status_code == 503


def test_cancelled_stalled_workers_are_visible_to_health_checks(monkeypatch):
    main = importlib.import_module("any2api_automation.main")
    monkeypatch.setattr(main, "workers_healthy", lambda grace: False)
    assert client.get("/health/ready").status_code == 503
    assert client.get("/health/live").status_code == 503
