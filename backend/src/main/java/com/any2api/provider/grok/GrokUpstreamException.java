package com.any2api.provider.grok;

public class GrokUpstreamException extends RuntimeException {

    private final int status;

    public GrokUpstreamException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
