from fastapi.testclient import TestClient

from any2api_automation.main import app

client = TestClient(app)


def test_live_health() -> None:
    response = client.get("/health/live")
    assert response.status_code == 200
    assert response.json()["service"] == "any2api-automation"


def test_capabilities_include_all_providers() -> None:
    response = client.get("/internal/v1/capabilities")
    assert response.status_code == 200
    providers = response.json()["providers"]
    provider_ids = [item["id"] for item in providers]
    assert provider_ids
    assert provider_ids == sorted(provider_ids)
    assert len(provider_ids) == len(set(provider_ids))
    assert all(
        set(item["operations"]) <= {"register", "reauthenticate", "keepalive"}
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
