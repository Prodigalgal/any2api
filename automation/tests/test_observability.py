import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from fastapi import HTTPException

from any2api_automation import provider_api
from any2api_automation.observability import OperationFailure, failure_details, sanitize


@pytest.mark.asyncio
@pytest.mark.parametrize("stage", ["lane", "action"])
async def test_keepalive_deadline_covers_resource_wait_and_cancels_action(monkeypatch, stage):
    monkeypatch.setattr(
        provider_api,
        "core_settings",
        lambda: SimpleNamespace(
            inference_first_frame_timeout_seconds=0.02,
        ),
    )
    monkeypatch.setattr(
        provider_api.provider_registry,
        "require",
        lambda _: SimpleNamespace(
            manifest=SimpleNamespace(operations=("keepalive",)),
        ),
    )
    lane = asyncio.Semaphore(0 if stage == "lane" else 1)
    monkeypatch.setattr(provider_api, "lanes", SimpleNamespace(batch=lane))
    stopped = asyncio.Event()

    async def wait(_request):
        try:
            await asyncio.Event().wait()
        finally:
            stopped.set()

    execute = AsyncMock(side_effect=wait)
    monkeypatch.setattr(provider_api.action_dispatcher, "execute", execute)
    request = provider_api.ProviderOperationRequest(
        operation="keepalive",
        context=provider_api.OperationContext(
            correlation_id="keepalive-deadline",
            aggregate_type="ACCOUNT",
            aggregate_id="fixture",
            attempt=1,
        ),
    )
    with pytest.raises(HTTPException) as failure:
        await asyncio.wait_for(provider_api.execute("arena", request), 0.5)
    assert failure.value.status_code == 504
    assert failure.value.detail["error"]["code"] == "operation_timeout"
    assert execute.await_count == int(stage == "action")
    assert stopped.is_set() is (stage == "action")
    assert not lane.locked() if stage == "action" else lane.locked()


@pytest.mark.asyncio
async def test_keepalive_deadline_does_not_shorten_reauthentication(monkeypatch):
    monkeypatch.setattr(
        provider_api,
        "core_settings",
        lambda: SimpleNamespace(
            inference_first_frame_timeout_seconds=0.01,
        ),
    )
    monkeypatch.setattr(
        provider_api.provider_registry,
        "require",
        lambda _: SimpleNamespace(
            manifest=SimpleNamespace(operations=("reauthenticate",)),
        ),
    )

    async def execute(_request):
        await asyncio.sleep(0.03)
        return {"healthy": True}

    monkeypatch.setattr(provider_api.action_dispatcher, "execute", execute)
    request = provider_api.ProviderOperationRequest(
        operation="reauthenticate",
        context=provider_api.OperationContext(
            correlation_id="reauth-deadline",
            aggregate_type="ACCOUNT",
            aggregate_id="fixture",
            attempt=1,
        ),
    )
    assert (await provider_api.execute("arena", request))["healthy"] is True


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


def test_operation_failure_can_be_raised_with_cause() -> None:
    cause = RuntimeError("upstream failure")

    with pytest.raises(OperationFailure) as raised:
        raise OperationFailure(
            code="provider_failed",
            stage="activation",
            message="activation failed",
            error_type="UpstreamError",
        ) from cause

    assert raised.value.__cause__ is cause
