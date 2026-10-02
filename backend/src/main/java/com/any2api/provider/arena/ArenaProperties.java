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

    private String probeModel = "gemini-3.1-flash-lite";
    private java.util.List<String> fallbackProbeModels = java.util.List.of("gpt-5.4-mini-high", "Max");

    public String getProbeModel() { return probeModel; }

    public void setProbeModel(String value) {
        this.probeModel = value == null ? "" : value.trim();
    }

    public java.util.List<String> getFallbackProbeModels() {
        return fallbackProbeModels;
    }

    public void setFallbackProbeModels(java.util.List<String> values) {
        this.fallbackProbeModels = values == null ? java.util.List.of() : java.util.List.copyOf(values);
    }
}
