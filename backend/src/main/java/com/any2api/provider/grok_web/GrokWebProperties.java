package com.any2api.provider.grok_web;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "any2api.provider.grok-web")
public class GrokWebProperties {
    private URI baseUrl = URI.create("https://grok.com");
    private URI accountsBaseUrl = URI.create("https://accounts.x.ai");
    private URI assetsBaseUrl = URI.create("https://assets.grok.com");
    private List<URI> imageAssetOrigins = List.of(
        URI.create("https://assets.grok.com"),
        URI.create("https://imagine-public.x.ai"),
        URI.create("https://imgen.x.ai"));
    private boolean allowNsfw;
    private int termsVersion = 5;
    private List<String> cookieDomains = List.of(".grok.com", ".x.ai");
    private String statsigManualValue = "";
    private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36";

    public URI getBaseUrl() { return baseUrl; }
    public void setBaseUrl(URI baseUrl) { this.baseUrl = baseUrl; }
    public URI getAccountsBaseUrl() { return accountsBaseUrl; }
    public void setAccountsBaseUrl(URI value) { accountsBaseUrl = value; }
    public URI getAssetsBaseUrl() { return assetsBaseUrl; }
    public void setAssetsBaseUrl(URI value) { assetsBaseUrl = value; }
    public List<URI> getImageAssetOrigins() { return List.copyOf(imageAssetOrigins); }
    public void setImageAssetOrigins(List<URI> value) {
        imageAssetOrigins = value == null ? List.of() : List.copyOf(value);
    }
    public boolean isAllowNsfw() { return allowNsfw; }
    public void setAllowNsfw(boolean value) { allowNsfw = value; }
    public int getTermsVersion() { return termsVersion; }
    public void setTermsVersion(int value) { termsVersion = value; }
    public List<String> getCookieDomains() { return List.copyOf(cookieDomains); }
    public void setCookieDomains(List<String> value) {
        cookieDomains = value == null ? List.of() : List.copyOf(value);
    }
    public String getStatsigManualValue() { return statsigManualValue; }
    public void setStatsigManualValue(String value) { statsigManualValue = value; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
}
