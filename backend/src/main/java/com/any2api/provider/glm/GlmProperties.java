package com.any2api.provider.glm;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("any2api.provider.glm")
public class GlmProperties {
    private String baseUrl = "https://chat.z.ai";
    private String frontendVersion = "1.1.79";
    private String signatureKey = "key-@@@@)))()((9))-xxxx&&&%%%%%";
    private String region = "overseas";
    private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        + "AppleWebKit/537.36 Chrome/136 Safari/537.36";
    private String browserProfile = "chrome136";
    private int timeoutSeconds = 180;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = trim(baseUrl); }
    public String getFrontendVersion() { return frontendVersion; }
    public void setFrontendVersion(String frontendVersion) {
        this.frontendVersion = frontendVersion.trim();
    }
    public String getSignatureKey() { return signatureKey; }
    public void setSignatureKey(String signatureKey) { this.signatureKey = signatureKey; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region.trim(); }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public String getBrowserProfile() { return browserProfile; }
    public void setBrowserProfile(String browserProfile) { this.browserProfile = browserProfile; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    private static String trim(String value) {
        var normalized = value == null ? "" : value.trim();
        return normalized.endsWith("/")
            ? normalized.substring(0, normalized.length() - 1) : normalized;
    }
}
