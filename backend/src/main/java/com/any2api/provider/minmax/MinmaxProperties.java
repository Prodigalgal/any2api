package com.any2api.provider.minmax;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("any2api.provider.minmax")
public class MinmaxProperties {
    private String baseUrl = "https://agent.minimax.io";
    private String streamBaseUrl = "https://agent-stream.minimax.io";
    private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        + "AppleWebKit/537.36 Chrome/150 Safari/537.36";
    private String signatureSalt = "I*7Cf%WZ#S&%1RlZJ&C2";
    private String yySalt = "ooui";
    private String devicePlatform = "web";
    private int bizId = 3;
    private String appId = "3001";
    private String versionCode = "22201";
    private String language = "en";
    private String client = "web";
    private String region = "en";
    private String osName = "Windows";
    private String browserName = "Chrome";
    private int deviceMemory = 8;
    private int cpuCoreCount = 8;
    private String browserLanguage = "en-US";
    private String browserPlatform = "Win32";
    private int screenWidth = 1440;
    private int screenHeight = 900;
    private int maxUploadBytes = 20 * 1024 * 1024;
    private String profileAssetHosts = "cdn.hailuoai.com";

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = trim(baseUrl); }
    public String getStreamBaseUrl() { return streamBaseUrl; }
    public void setStreamBaseUrl(String streamBaseUrl) { this.streamBaseUrl = trim(streamBaseUrl); }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public String getSignatureSalt() { return signatureSalt; }
    public void setSignatureSalt(String signatureSalt) { this.signatureSalt = signatureSalt; }
    public String getYySalt() { return yySalt; }
    public void setYySalt(String yySalt) { this.yySalt = yySalt; }
    public String getDevicePlatform() { return devicePlatform; }
    public void setDevicePlatform(String devicePlatform) { this.devicePlatform = devicePlatform; }
    public int getBizId() { return bizId; }
    public void setBizId(int bizId) { this.bizId = bizId; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getVersionCode() { return versionCode; }
    public void setVersionCode(String versionCode) { this.versionCode = versionCode; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getClient() { return client; }
    public void setClient(String client) { this.client = client; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getOsName() { return osName; }
    public void setOsName(String osName) { this.osName = osName; }
    public String getBrowserName() { return browserName; }
    public void setBrowserName(String browserName) { this.browserName = browserName; }
    public int getDeviceMemory() { return deviceMemory; }
    public void setDeviceMemory(int deviceMemory) { this.deviceMemory = deviceMemory; }
    public int getCpuCoreCount() { return cpuCoreCount; }
    public void setCpuCoreCount(int cpuCoreCount) { this.cpuCoreCount = cpuCoreCount; }
    public String getBrowserLanguage() { return browserLanguage; }
    public void setBrowserLanguage(String browserLanguage) { this.browserLanguage = browserLanguage; }
    public String getBrowserPlatform() { return browserPlatform; }
    public void setBrowserPlatform(String browserPlatform) { this.browserPlatform = browserPlatform; }
    public int getScreenWidth() { return screenWidth; }
    public void setScreenWidth(int screenWidth) { this.screenWidth = screenWidth; }
    public int getScreenHeight() { return screenHeight; }
    public void setScreenHeight(int screenHeight) { this.screenHeight = screenHeight; }
    public int getMaxUploadBytes() { return maxUploadBytes; }
    public void setMaxUploadBytes(int maxUploadBytes) {
        if (maxUploadBytes < 1) throw new IllegalArgumentException("maxUploadBytes must be positive");
        this.maxUploadBytes = maxUploadBytes;
    }
    public String getProfileAssetHosts() { return profileAssetHosts; }
    public void setProfileAssetHosts(String profileAssetHosts) {
        this.profileAssetHosts = profileAssetHosts;
    }

    synchronized RequestProfile requestProfile() {
        return new RequestProfile(
            signatureSalt, yySalt, devicePlatform, bizId, appId, versionCode,
            language, client, region, osName, browserName, deviceMemory,
            cpuCoreCount, browserLanguage, browserPlatform, screenWidth, screenHeight);
    }

    synchronized boolean applyOfficialProfile(
        String discoveredSignatureSalt,
        String discoveredYySalt,
        String discoveredVersionCode
    ) {
        var nextSignatureSalt = nonBlank(discoveredSignatureSalt, signatureSalt);
        var nextYySalt = nonBlank(discoveredYySalt, yySalt);
        var nextVersionCode = nonBlank(discoveredVersionCode, versionCode);
        var changed = !nextSignatureSalt.equals(signatureSalt)
            || !nextYySalt.equals(yySalt)
            || !nextVersionCode.equals(versionCode);
        signatureSalt = nextSignatureSalt;
        yySalt = nextYySalt;
        versionCode = nextVersionCode;
        return changed;
    }

    private static String nonBlank(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate.trim();
    }

    record RequestProfile(
        String signatureSalt,
        String yySalt,
        String devicePlatform,
        int bizId,
        String appId,
        String versionCode,
        String language,
        String client,
        String region,
        String osName,
        String browserName,
        int deviceMemory,
        int cpuCoreCount,
        String browserLanguage,
        String browserPlatform,
        int screenWidth,
        int screenHeight
    ) {}
    private static String trim(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
