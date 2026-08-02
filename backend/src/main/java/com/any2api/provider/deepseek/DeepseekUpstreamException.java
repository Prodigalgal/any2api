package com.any2api.provider.deepseek;

final class DeepseekUpstreamException extends RuntimeException {
    private final int status;

    DeepseekUpstreamException(int status, String message) {
        super(message);
        this.status = status;
    }

    int status() { return status; }
}
