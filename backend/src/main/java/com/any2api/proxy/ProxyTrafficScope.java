package com.any2api.proxy;

public enum ProxyTrafficScope {
    REGISTRATION,
    LIFECYCLE,
    INFERENCE;

    public static ProxyTrafficScope parse(String value) {
        try {
            return valueOf(value == null ? "" : value.trim().toUpperCase());
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("unsupported proxy traffic scope", error);
        }
    }
}
