from types import SimpleNamespace

import pytest
from fastapi import HTTPException

from any2api_automation import provider_api
from any2api_automation.observability import OperationFailure, failure_details, sanitize


def test_failure_details_redact_private_values() -> None:
    raw = (
        'failed {"token":"top secret", "password": "private-value"} '
        "for operator@example.test at https://example.test/callback?code=private "
        "image=data:image/png;base64,QUJDRA=="
    )

    sanitized = sanitize(raw)

    assert all(
        marker in sanitized
        for marker in ("<redacted>", "<email>", "<url-with-query>", "<embedded-data>")
    )
    assert all(
        value not in sanitized
        for value in (
            "top secret",
            "private-value",
            "operator@example.test",
            "code=private",
            "QUJDRA==",
        )
    )


@pytest.mark.asyncio
async def test_provider_failure_preserves_correlation_and_stage(monkeypatch) -> None:
    class FailingProvider:
        manifest = SimpleNamespace(operations=("register",))

        async def register(self, _payload):
            raise OperationFailure(
                code="challenge_failed",
                stage="captcha",
                message='token="private-value" was rejected',
                error_type="SolverRejected",
            )

    monkeypatch.setattr(
        provider_api.provider_registry, "require", lambda _provider: FailingProvider()
    )
    request = provider_api.ProviderOperationRequest(
        operation="register",
        payload={},
        context=provider_api.OperationContext(
            correlation_id="registration-correlation-123",
            aggregate_type="REGISTRATION_JOB",
            aggregate_id="job-123",
            attempt=2,
        ),
    )

    with pytest.raises(HTTPException) as raised:
        await provider_api.execute("deepseek", request)

    error = raised.value.detail["error"]
    assert error["correlation_id"] == "registration-correlation-123"
    assert error["code"] == "challenge_failed"
    assert error["stage"] == "captcha"
    assert "private-value" not in error["message"]
    assert "<redacted>" in error["message"]


def test_operation_failure_is_resanitized_at_the_boundary() -> None:
    failure = failure_details(
        OperationFailure(
            code="provider_failed",
            stage="request",
            message='authorization="private-value"',
            error_type="ProviderError",
        )
    )

    assert "private-value" not in failure.message
    assert "<redacted>" in failure.message
