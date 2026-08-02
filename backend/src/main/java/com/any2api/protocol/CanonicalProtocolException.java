package com.any2api.protocol;

public final class CanonicalProtocolException extends RuntimeException {
    private final String violation;

    public CanonicalProtocolException(String violation) {
        super("provider canonical event contract violation: " + violation);
        this.violation = violation;
    }

    public String violation() {
        return violation;
    }
}
