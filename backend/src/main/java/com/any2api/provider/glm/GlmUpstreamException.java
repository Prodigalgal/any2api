package com.any2api.provider.glm;

final class GlmUpstreamException extends RuntimeException {
    private final int status;

    GlmUpstreamException(int status, String message) {
        super(message);
        this.status = status;
    }

    int status() { return status; }
}
