package com.any2api.provider.mimo;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("any2api.provider.mimo")
public class MimoProperties {
    private String baseUrl = "https://aistudio.xiaomimimo.com";
    private String timezone = "Asia/Shanghai";
    private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        + "AppleWebKit/537.36 Chrome/143 Safari/537.36";
    private int maxUploadBytes = 25 * 1024 * 1024;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = trim(baseUrl); }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public int getMaxUploadBytes() { return maxUploadBytes; }
    public void setMaxUploadBytes(int maxUploadBytes) {
        if (maxUploadBytes < 1) throw new IllegalArgumentException("maxUploadBytes must be positive");
        this.maxUploadBytes = maxUploadBytes;
    }

    private static String trim(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
