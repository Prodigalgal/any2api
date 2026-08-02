package com.any2api.provider.deepseek;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("any2api.provider.deepseek")
public class DeepseekProperties {
    private String baseUrl = "https://chat.deepseek.com";
    private String assetHost = "fe-static.deepseek.com";
    private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36";
    private String browserProfile = "chrome146";
    private String bundleId = "com.deepseek.chat";
    private String platform = "web";
    private String clientVersion = "2.3.0";
    private String locale = "en_US";
    private int timezoneOffsetSeconds = 32400;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String value) { baseUrl = trimUrl(value); }
    public String getAssetHost() { return assetHost; }
    public void setAssetHost(String value) { assetHost = value == null ? "" : value.trim(); }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String value) { userAgent = value == null ? "" : value.trim(); }
    public String getBrowserProfile() { return browserProfile; }
    public void setBrowserProfile(String value) { browserProfile = value == null ? "" : value.trim(); }
    public String getBundleId() { return bundleId; }
    public void setBundleId(String value) { bundleId = value == null ? "" : value.trim(); }
    public String getPlatform() { return platform; }
    public void setPlatform(String value) { platform = value == null ? "" : value.trim(); }
    public synchronized String getClientVersion() { return clientVersion; }
    public synchronized void setClientVersion(String value) {
        clientVersion = value == null ? "" : value.trim();
    }
    public String getLocale() { return locale; }
    public void setLocale(String value) { locale = value == null ? "" : value.trim(); }
    public int getTimezoneOffsetSeconds() { return timezoneOffsetSeconds; }
    public void setTimezoneOffsetSeconds(int value) { timezoneOffsetSeconds = value; }

    synchronized boolean applyOfficialVersion(String value) {
        var normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.equals(clientVersion)) return false;
        clientVersion = normalized;
        return true;
    }

    private static String trimUrl(String value) {
        var result = value == null ? "" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }
}
