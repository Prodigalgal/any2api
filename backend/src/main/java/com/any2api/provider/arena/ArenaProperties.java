package com.any2api.provider.arena;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("any2api.provider.arena")
public class ArenaProperties {
    private String baseUrl = "https://arena.ai";
    private Duration accountVerificationTimeout = Duration.ofSeconds(120);

    public String getBaseUrl() { return baseUrl; }

    public void setBaseUrl(String value) {
        var normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        baseUrl = normalized;
    }

    public Duration getAccountVerificationTimeout() { return accountVerificationTimeout; }

    public void setAccountVerificationTimeout(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("accountVerificationTimeout must be positive");
        }
        accountVerificationTimeout = value;
    }

    private String probeModel = "gemini-3.1-flash-lite";

    public String getProbeModel() { return probeModel; }

    public void setProbeModel(String value) {
        this.probeModel = value == null ? "" : value.trim();
    }

}
