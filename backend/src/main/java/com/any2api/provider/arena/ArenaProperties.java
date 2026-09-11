package com.any2api.provider.arena;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("any2api.provider.arena")
public class ArenaProperties {
    private String baseUrl = "https://arena.ai";
    private Duration modelProbeTimeout = Duration.ofSeconds(240);

    public String getBaseUrl() { return baseUrl; }

    public void setBaseUrl(String value) {
        var normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        baseUrl = normalized;
    }

    public Duration getModelProbeTimeout() { return modelProbeTimeout; }

    public void setModelProbeTimeout(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("modelProbeTimeout must be positive");
        }
        modelProbeTimeout = value;
    }
}
