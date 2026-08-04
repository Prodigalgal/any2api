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
    private final Observability observability = new Observability();
    private final ModelRuntime modelRuntime = new ModelRuntime();

    public Security getSecurity() {
        return security;
    }

    public Automation getAutomation() {
        return automation;
    }

    public ProxyBootstrap getProxyBootstrap() { return proxyBootstrap; }
    public Media getMedia() { return media; }
    public CacheSettings getCache() { return cache; }
    public Observability getObservability() { return observability; }
    public ModelRuntime getModelRuntime() { return modelRuntime; }


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

    public static class Observability {
        private Duration operationRetention = Duration.ofDays(30);
        private Duration usageRetention = Duration.ofDays(90);

        public Duration getOperationRetention() { return operationRetention; }
        public void setOperationRetention(Duration value) {
            operationRetention = positive(value, "operation retention");
        }
        public Duration getUsageRetention() { return usageRetention; }
        public void setUsageRetention(Duration value) {
            usageRetention = positive(value, "usage retention");
        }

        private static Duration positive(Duration value, String name) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        }
    }

    public static class ModelRuntime {
        private int maxConcurrentRequests = 32;
        private Duration maxQueueWait = Duration.ofSeconds(2);
        private int circuitSlidingWindow = 20;
        private int circuitMinimumCalls = 5;
        private float circuitFailureRateThreshold = 60f;
        private Duration circuitOpenDuration = Duration.ofSeconds(30);
        private Duration healthWindow = Duration.ofHours(24);
        private Duration probeFreshness = Duration.ofMinutes(30);
        private int scheduledProbeBatchSize = 5;

        public int getMaxConcurrentRequests() { return maxConcurrentRequests; }
        public void setMaxConcurrentRequests(int value) { maxConcurrentRequests = positive(value, "max concurrent requests"); }
        public Duration getMaxQueueWait() { return maxQueueWait; }
        public void setMaxQueueWait(Duration value) { maxQueueWait = positive(value, "max queue wait"); }
        public int getCircuitSlidingWindow() { return circuitSlidingWindow; }
        public void setCircuitSlidingWindow(int value) { circuitSlidingWindow = positive(value, "circuit sliding window"); }
        public int getCircuitMinimumCalls() { return circuitMinimumCalls; }
        public void setCircuitMinimumCalls(int value) { circuitMinimumCalls = positive(value, "circuit minimum calls"); }
        public float getCircuitFailureRateThreshold() { return circuitFailureRateThreshold; }
        public void setCircuitFailureRateThreshold(float value) {
            if (value <= 0 || value > 100) throw new IllegalArgumentException("circuit failure rate threshold must be in (0,100]");
            circuitFailureRateThreshold = value;
        }
        public Duration getCircuitOpenDuration() { return circuitOpenDuration; }
        public void setCircuitOpenDuration(Duration value) { circuitOpenDuration = positive(value, "circuit open duration"); }
        public Duration getHealthWindow() { return healthWindow; }
        public void setHealthWindow(Duration value) { healthWindow = positive(value, "health window"); }
        public Duration getProbeFreshness() { return probeFreshness; }
        public void setProbeFreshness(Duration value) { probeFreshness = positive(value, "probe freshness"); }
        public int getScheduledProbeBatchSize() { return scheduledProbeBatchSize; }
        public void setScheduledProbeBatchSize(int value) { scheduledProbeBatchSize = positive(value, "scheduled probe batch size"); }

        private static int positive(int value, String name) {
            if (value < 1) throw new IllegalArgumentException(name + " must be positive");
            return value;
        }

        private static Duration positive(Duration value, String name) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        }
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
