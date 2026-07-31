package com.any2api.provider.mimo;

final class MimoUpstreamException extends RuntimeException {
    private final int status;

    MimoUpstreamException(int status, String message) {
        super(message);
        this.status = status;
    }

    int status() { return status; }
}
