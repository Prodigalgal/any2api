package com.any2api.provider.longcat;

final class LongcatUpstreamException extends RuntimeException {
    private final int status;
    LongcatUpstreamException(int status, String message) { super(message); this.status = status; }
    int status() { return status; }
}
