package com.any2api.protocol.state;

public final class ResponseStorageLimitException extends RuntimeException {
    public ResponseStorageLimitException() { super("stored response quota exceeded; delete previous responses or wait for expiry"); }
}
