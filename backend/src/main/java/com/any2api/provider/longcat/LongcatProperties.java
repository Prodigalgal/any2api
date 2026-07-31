package com.any2api.provider.longcat;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("any2api.provider.longcat")
public class LongcatProperties {
    private String baseUrl = "https://longcat.chat";
    private String appKey = "fe_com.sankuai.friday.fe.longcat";
    private String language = "zh";
    private String requestedWith = "XMLHttpRequest";
    private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        + "AppleWebKit/537.36 Chrome/131 Safari/537.36";

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = trim(baseUrl); }
    public String getAppKey() { return appKey; }
    public void setAppKey(String appKey) { this.appKey = appKey; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getRequestedWith() { return requestedWith; }
    public void setRequestedWith(String requestedWith) { this.requestedWith = requestedWith; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

    private static String trim(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
