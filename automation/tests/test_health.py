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
    providers = {item["id"] for item in response.json()["providers"]}
    assert providers == {"grok", "mimo", "qwen", "longcat"}
