package com.any2api.lifecycle;

import com.any2api.observability.StructuredOperationFailure;

public final class AutomationInvocationException extends RuntimeException
    implements StructuredOperationFailure {
    private final String correlationId;
    private final int status;
    private final String errorCode;
    private final String stage;
    private final String detail;

    AutomationInvocationException(
        String correlationId,
        int status,
        String errorCode,
        String stage,
        String detail,
        Throwable cause
    ) {
        super(detail, cause);
        this.correlationId = correlationId;
        this.status = status;
        this.errorCode = errorCode;
        this.stage = stage;
        this.detail = detail;
    }

    public String correlationId() { return correlationId; }
    public int status() { return status; }
    public String errorCode() { return errorCode; }
    public String stage() { return stage; }
    public String detail() { return detail; }
}
