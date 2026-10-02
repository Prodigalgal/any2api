package com.any2api.protocol.state;

public final class ResponseNotFoundException extends RuntimeException {
    public ResponseNotFoundException() { super("response does not exist or has expired"); }
}
