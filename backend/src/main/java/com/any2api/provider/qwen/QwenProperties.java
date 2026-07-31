package com.any2api.provider.qwen;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("any2api.provider.qwen")
public class QwenProperties {
    private String baseUrl = "https://chat.qwen.ai";
    private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        + "AppleWebKit/537.36 Chrome/150 Safari/537.36";
    private String source = "web";
    private String requestVersion = "2.1";
    private int maxUploadBytes = 20 * 1024 * 1024;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = trim(baseUrl); }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getRequestVersion() { return requestVersion; }
    public void setRequestVersion(String requestVersion) { this.requestVersion = requestVersion; }
    public int getMaxUploadBytes() { return maxUploadBytes; }
    public void setMaxUploadBytes(int maxUploadBytes) {
        if (maxUploadBytes < 1) throw new IllegalArgumentException("maxUploadBytes must be positive");
        this.maxUploadBytes = maxUploadBytes;
    }
    private static String trim(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
