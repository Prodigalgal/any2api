from __future__ import annotations

import logging
import re
import time
import uuid
from collections.abc import Iterator
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass

_correlation_id: ContextVar[str] = ContextVar("correlation_id", default="unbound")
_provider_id: ContextVar[str] = ContextVar("provider_id", default="unknown")
_operation: ContextVar[str] = ContextVar("operation", default="unknown")

_DATA = re.compile(r"data:[^;\s]+;base64,[A-Za-z0-9+/=]+", re.IGNORECASE)
_EMAIL = re.compile(r"(?<![\w.+-])[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}(?![\w.-])")
_URL_WITH_QUERY = re.compile(r"https?://[^\s?#]+\?[^\s#]+")
_SECRET_FIELD = re.compile(
    r"(?i)(?P<prefix>[\"']?(?:password|token|authorization|cookie|jwt|secret)"
    r"[\"']?\s*[:=]\s*[\"']?)[^,}'\"\r\n]+"
)
_VALID_CORRELATION = re.compile(r"^[A-Za-z0-9._:-]{8,100}$")
_PATH_ID = re.compile(r"/[0-9a-fA-F-]{24,}(?=/|$)")
_logger = logging.getLogger("any2api_automation.http")


@dataclass(frozen=True)
class OperationFailure(RuntimeError):
    code: str
    stage: str
    message: str
    error_type: str
    retryable: bool = True

    def __str__(self) -> str:
        return (
            f"provider operation failed at stage={self.stage} ({self.error_type}: {self.message})"
        )


def correlation_id() -> str:
    return _correlation_id.get()


@contextmanager
def bind_operation(correlation: str, provider: str, operation: str) -> Iterator[None]:
    correlation_token = _correlation_id.set(correlation)
    provider_token = _provider_id.set(provider)
    operation_token = _operation.set(operation)
    try:
        yield
    finally:
        _operation.reset(operation_token)
        _provider_id.reset(provider_token)
        _correlation_id.reset(correlation_token)


def failure_details(error: Exception) -> OperationFailure:
    if isinstance(error, OperationFailure):
        return OperationFailure(
            code=error.code,
            stage=error.stage,
            message=sanitize(error.message),
            error_type=error.error_type,
            retryable=error.retryable,
        )
    return OperationFailure(
        code="provider_operation_failed",
        stage="provider_operation",
        message=sanitize(str(error) or type(error).__name__),
        error_type=type(error).__name__,
    )


def sanitize(raw: str) -> str:
    value = _DATA.sub("<embedded-data>", raw)
    value = _EMAIL.sub("<email>", value)
    value = _URL_WITH_QUERY.sub("<url-with-query>", value)
    value = _SECRET_FIELD.sub(
        lambda match: f"{match.group('prefix')}<redacted>",
        value,
    )
    return " ".join(value.split())[:1200]


class CorrelationLoggingMiddleware:
    def __init__(self, app) -> None:
        self.app = app

    async def __call__(self, scope, receive, send) -> None:
        if scope["type"] != "http" or not scope.get("path", "").startswith("/internal/"):
            await self.app(scope, receive, send)
            return
        headers = {key.lower(): value for key, value in scope.get("headers", [])}
        incoming = headers.get(b"x-any2api-correlation-id", b"").decode("ascii", errors="ignore")
        correlation = incoming if _VALID_CORRELATION.fullmatch(incoming) else str(uuid.uuid4())
        method = scope.get("method", "UNKNOWN")
        path = _PATH_ID.sub("/{id}", scope.get("path", "/internal"))[:256]
        started = time.monotonic()
        completed = False

        async def observed_send(message) -> None:
            nonlocal completed
            if message["type"] == "http.response.start":
                message["headers"] = [
                    *message.get("headers", []),
                    (b"x-any2api-correlation-id", correlation.encode("ascii")),
                ]
            if message["type"] == "http.response.body" and not message.get("more_body", False):
                completed = True
                _logger.info(
                    "internal_request_finished correlation_id=%s method=%s path=%s "
                    "status=%s duration_ms=%s",
                    correlation,
                    method,
                    path,
                    scope.get("any2api_status", 200),
                    round((time.monotonic() - started) * 1000),
                )
            await send(message)

        async def status_send(message) -> None:
            if message["type"] == "http.response.start":
                scope["any2api_status"] = message["status"]
            await observed_send(message)

        with bind_operation(correlation, "internal", method.lower()):
            _logger.info(
                "internal_request_started correlation_id=%s method=%s path=%s",
                correlation,
                method,
                path,
            )
            try:
                await self.app(scope, receive, status_send)
            except Exception as error:
                if not completed:
                    completed = True
                    _logger.error(
                        "internal_request_failed correlation_id=%s method=%s path=%s "
                        "error_type=%s duration_ms=%s",
                        correlation,
                        method,
                        path,
                        type(error).__name__,
                        round((time.monotonic() - started) * 1000),
                    )
                raise
            finally:
                if not completed:
                    _logger.warning(
                        "internal_request_interrupted correlation_id=%s method=%s path=%s "
                        "duration_ms=%s",
                        correlation,
                        method,
                        path,
                        round((time.monotonic() - started) * 1000),
                    )
