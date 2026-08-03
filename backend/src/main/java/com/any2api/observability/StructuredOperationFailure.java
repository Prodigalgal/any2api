package com.any2api.observability;

public interface StructuredOperationFailure {
    String errorCode();
    String stage();
    String detail();
}
