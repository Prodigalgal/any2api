package com.any2api.provider;

import java.util.Map;

public record ProviderFailure(
    String type,
    String message,
    boolean retryable,
    Map<String, Object> detail
) {
}

