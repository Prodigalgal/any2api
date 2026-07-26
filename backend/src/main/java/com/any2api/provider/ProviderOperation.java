package com.any2api.provider;

public enum ProviderOperation {
    CHAT_COMPLETIONS("/v1/chat/completions", ProviderCapability.CHAT_COMPLETIONS),
    RESPONSES("/v1/responses", ProviderCapability.RESPONSES);

    private final String upstreamPath;
    private final ProviderCapability capability;

    ProviderOperation(String upstreamPath, ProviderCapability capability) {
        this.upstreamPath = upstreamPath;
        this.capability = capability;
    }

    public String upstreamPath() {
        return upstreamPath;
    }

    public ProviderCapability capability() {
        return capability;
    }
}
