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
    assert len(provider_ids) == len(set(provider_ids))
    assert all(item["challenge_types"] for item in providers)
