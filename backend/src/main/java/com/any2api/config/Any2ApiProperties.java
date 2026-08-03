package com.any2api.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "any2api")
public class Any2ApiProperties {

    private final Security security = new Security();
    private final Automation automation = new Automation();
    private final ProxyBootstrap proxyBootstrap = new ProxyBootstrap();
    private final Media media = new Media();
    private final CacheSettings cache = new CacheSettings();

    public Security getSecurity() {
        return security;
    }

    public Automation getAutomation() {
        return automation;
    }

    public ProxyBootstrap getProxyBootstrap() { return proxyBootstrap; }
    public Media getMedia() { return media; }
    public CacheSettings getCache() { return cache; }


    public static class Security {
        private String publicApiKey = "";
        private String adminUsername = "admin";
        private String adminPassword = "";
        private String internalToken = "";
        private String credentialMasterKey = "";
        private boolean adminSessionSecure;
        private long adminSessionTtlSeconds = 28800;
        private int loginPowDifficulty = 18;

        public String getPublicApiKey() {
            return publicApiKey;
        }

        public void setPublicApiKey(String publicApiKey) {
            this.publicApiKey = publicApiKey;
        }

        public String getAdminUsername() {
            return adminUsername;
        }

        public void setAdminUsername(String adminUsername) {
            this.adminUsername = adminUsername;
        }

        public String getAdminPassword() {
            return adminPassword;
        }

        public void setAdminPassword(String adminPassword) {
            this.adminPassword = adminPassword;
        }

        public String getInternalToken() {
            return internalToken;
        }

        public void setInternalToken(String internalToken) {
            this.internalToken = internalToken;
        }

        public String getCredentialMasterKey() {
            return credentialMasterKey;
        }

        public void setCredentialMasterKey(String credentialMasterKey) {
            this.credentialMasterKey = credentialMasterKey;
        }

        public boolean isAdminSessionSecure() { return adminSessionSecure; }
        public void setAdminSessionSecure(boolean adminSessionSecure) {
            this.adminSessionSecure = adminSessionSecure;
        }
        public long getAdminSessionTtlSeconds() { return adminSessionTtlSeconds; }
        public void setAdminSessionTtlSeconds(long adminSessionTtlSeconds) {
            this.adminSessionTtlSeconds = adminSessionTtlSeconds;
        }
        public int getLoginPowDifficulty() { return loginPowDifficulty; }
        public void setLoginPowDifficulty(int loginPowDifficulty) {
            this.loginPowDifficulty = loginPowDifficulty;
        }
    }

    public static class Automation {
        private URI baseUrl = URI.create("http://localhost:8090");

        public URI getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(URI baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    public static class ProxyBootstrap {
        private String directory = "";
        private String poolName = "Self-hosted Oracle";

        public String getDirectory() { return directory; }
        public void setDirectory(String directory) { this.directory = directory; }
        public String getPoolName() { return poolName; }
        public void setPoolName(String poolName) { this.poolName = poolName; }
    }

    public static class Media {
        private String publicBaseUrl = "";

        public String getPublicBaseUrl() { return publicBaseUrl; }
        public void setPublicBaseUrl(String value) { publicBaseUrl = value; }
    }

    public static class CacheSettings {
        private final Tier apiKey = new Tier(
            Duration.ofSeconds(30), Duration.ofMinutes(5), 10_000);
        private final Tier modelCatalog = new Tier(
            Duration.ofSeconds(3), Duration.ofSeconds(20), 1_000);

        public Tier getApiKey() { return apiKey; }
        public Tier getModelCatalog() { return modelCatalog; }
    }

    public static class Tier {
        private Duration localTtl;
        private Duration redisTtl;
        private long maximumEntries;

        Tier(Duration localTtl, Duration redisTtl, long maximumEntries) {
            this.localTtl = localTtl;
            this.redisTtl = redisTtl;
            this.maximumEntries = maximumEntries;
        }

        public Duration getLocalTtl() { return localTtl; }
        public void setLocalTtl(Duration value) { localTtl = value; }
        public Duration getRedisTtl() { return redisTtl; }
        public void setRedisTtl(Duration value) { redisTtl = value; }
        public long getMaximumEntries() { return maximumEntries; }
        public void setMaximumEntries(long value) { maximumEntries = value; }
    }

}
