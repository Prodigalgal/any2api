package com.any2api.provider.glm;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("any2api.provider.glm")
public class GlmProperties {
    private String baseUrl = "https://chat.z.ai";

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = trim(baseUrl); }
    private static String trim(String value) {
        var normalized = value == null ? "" : value.trim();
        return normalized.endsWith("/")
            ? normalized.substring(0, normalized.length() - 1) : normalized;
    }
}
