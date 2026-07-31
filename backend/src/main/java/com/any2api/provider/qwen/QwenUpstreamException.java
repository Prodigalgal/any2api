package com.any2api.provider.qwen;

final class QwenUpstreamException extends RuntimeException {
    private final int status;
    QwenUpstreamException(int status, String message) { super(message); this.status = status; }
    int status() { return status; }
}
