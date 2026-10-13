package com.any2api.provider.glm;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("any2api.provider.glm")
public class GlmProperties {
    private String baseUrl = "https://chat.z.ai";
    private Duration accountVerificationTimeout = Duration.ofSeconds(120);

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = trim(baseUrl); }
    public Duration getAccountVerificationTimeout() { return accountVerificationTimeout; }
    public void setAccountVerificationTimeout(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("accountVerificationTimeout must be positive");
        }
        accountVerificationTimeout = value;
    }

    private static String trim(String value) {
        var normalized = value == null ? "" : value.trim();
        return normalized.endsWith("/")
            ? normalized.substring(0, normalized.length() - 1) : normalized;
    }
}
