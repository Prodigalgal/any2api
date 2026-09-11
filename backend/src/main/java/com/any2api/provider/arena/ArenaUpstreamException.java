package com.any2api.provider.arena;

final class ArenaUpstreamException extends RuntimeException {
    private final int status;

    ArenaUpstreamException(int status, String message) {
        super(message);
        this.status = status;
    }

    int status() { return status; }
}
