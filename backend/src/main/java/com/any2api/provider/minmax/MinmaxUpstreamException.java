package com.any2api.provider.minmax;

final class MinmaxUpstreamException extends RuntimeException {
    private final int status;
    MinmaxUpstreamException(int status, String message) { super(message); this.status = status; }
    int status() { return status; }
}
