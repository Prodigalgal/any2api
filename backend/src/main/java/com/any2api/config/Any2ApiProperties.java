package com.any2api.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "any2api")
public class Any2ApiProperties {

    private final Security security = new Security();
    private final Automation automation = new Automation();
    private final ProxyBootstrap proxyBootstrap = new ProxyBootstrap();
    private final Media media = new Media();
    private final CacheSettings cache = new CacheSettings();
    private final Observability observability = new Observability();
    private final Lifecycle lifecycle = new Lifecycle();
    private final ModelRuntime modelRuntime = new ModelRuntime();
    private final TempMail tempMail = new TempMail();
    private final Responses responses = new Responses();
    private final EgressGate egressGate = new EgressGate();

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
    public Lifecycle getLifecycle() { return lifecycle; }
    public ModelRuntime getModelRuntime() { return modelRuntime; }
    public TempMail getTempMail() { return tempMail; }
    public Responses getResponses() { return responses; }
    public EgressGate getEgressGate() { return egressGate; }

    public static class Responses {
        private Duration retention = Duration.ofHours(24);
        private int maxStateBytes = 2 << 20;
        private int maxStoredPerKey = 1000;
        public Duration getRetention() { return retention; }
        public void setRetention(Duration retention) {
            if (retention == null || retention.isZero() || retention.isNegative()) {
                throw new IllegalArgumentException("responses retention must be positive");
            }
            this.retention = retention;
        }
        public int getMaxStateBytes() { return maxStateBytes; }
        public void setMaxStateBytes(int maxStateBytes) {
            if (maxStateBytes < 1) throw new IllegalArgumentException("responses max state bytes must be positive");
            this.maxStateBytes = maxStateBytes;
        }
        public int getMaxStoredPerKey() { return maxStoredPerKey; }
        public void setMaxStoredPerKey(int maxStoredPerKey) {
            if (maxStoredPerKey < 1) throw new IllegalArgumentException("responses max stored per key must be positive");
            this.maxStoredPerKey = maxStoredPerKey;
        }
    }


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
        private java.util.Map<String, URI> providerUrls = new java.util.LinkedHashMap<>();
        private int maxResponseBytes = 8 << 20;

        public URI getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(URI baseUrl) {
            this.baseUrl = baseUrl;
        }

        public java.util.Map<String, URI> getProviderUrls() {
            return providerUrls;
        }

        public void setProviderUrls(java.util.Map<String, URI> providerUrls) {
            this.providerUrls = providerUrls == null
                ? new java.util.LinkedHashMap<>()
                : new java.util.LinkedHashMap<>(providerUrls);
        }

        public URI resolveProviderBaseUrl(String providerId) {
            if (providerId == null || providerId.isBlank()) {
                return baseUrl;
            }
            return providerUrls.getOrDefault(providerId.trim(), baseUrl);
        }


        public int getMaxResponseBytes() {
            return maxResponseBytes;
        }

        public void setMaxResponseBytes(int value) {
            if (value < (256 << 10) || value > (32 << 20)) {
                throw new IllegalArgumentException(
                    "automation max response bytes must be between 262144 and 33554432");
            }
            maxResponseBytes = value;
        }
    }

    public static class TempMail {
        private String apiBase = "";
        private String adminPassword = "";
        private String sitePassword = "";
        private List<String> domains = List.of();
        private double pollSeconds = 4;
        private int messageTimeoutSeconds = 240;
        private int requestTimeoutSeconds = 30;

        public String getApiBase() { return apiBase; }
        public void setApiBase(String value) { apiBase = value == null ? "" : value; }
        public String getAdminPassword() { return adminPassword; }
        public void setAdminPassword(String value) {
            adminPassword = value == null ? "" : value;
        }
        public String getSitePassword() { return sitePassword; }
        public void setSitePassword(String value) { sitePassword = value == null ? "" : value; }
        public List<String> getDomains() { return domains; }
        public void setDomains(List<String> value) {
            domains = value == null ? List.of() : List.copyOf(value);
        }
        public double getPollSeconds() { return pollSeconds; }
        public void setPollSeconds(double value) { pollSeconds = value; }
        public int getMessageTimeoutSeconds() { return messageTimeoutSeconds; }
        public void setMessageTimeoutSeconds(int value) { messageTimeoutSeconds = value; }
        public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
        public void setRequestTimeoutSeconds(int value) { requestTimeoutSeconds = value; }
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
        private Duration redisAccessTimeout = Duration.ofMillis(250);
        private final Tier apiKey = new Tier(
            Duration.ofSeconds(30), Duration.ofMinutes(5), 10_000);
        private final Tier modelCatalog = new Tier(
            Duration.ofSeconds(3), Duration.ofSeconds(20), 1_000);

        public Tier getApiKey() { return apiKey; }
        public Tier getModelCatalog() { return modelCatalog; }
        public Duration getRedisAccessTimeout() { return redisAccessTimeout; }
        public void setRedisAccessTimeout(Duration value) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException("cache Redis access timeout must be positive");
            }
            redisAccessTimeout = value;
        }
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

    public static class Lifecycle {
        private int actionConcurrency = 2;

        public int getActionConcurrency() { return actionConcurrency; }
        public void setActionConcurrency(int value) {
            if (value < 1 || value > 8) {
                throw new IllegalArgumentException("lifecycle action concurrency must be between 1 and 8");
            }
            actionConcurrency = value;
        }

        private final ConditionalRegistration conditionalRegistration = new ConditionalRegistration();

        public ConditionalRegistration getConditionalRegistration() {
            return conditionalRegistration;
        }

        private final AccountPurge accountPurge = new AccountPurge();

        public AccountPurge getAccountPurge() {
            return accountPurge;
        }

        public static class AccountPurge {
            private boolean enabled = true;
            private Duration checkInterval = Duration.ofHours(1);
            private Duration retentionPeriod = Duration.ofHours(72);
            private int failureThreshold = 5;
            private int batchSize = 100;

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean value) { this.enabled = value; }

            public Duration getCheckInterval() { return checkInterval; }
            public void setCheckInterval(Duration value) {
                if (value == null || value.isNegative() || value.isZero()) {
                    throw new IllegalArgumentException("check interval must be positive");
                }
                this.checkInterval = value;
            }

            public Duration getRetentionPeriod() { return retentionPeriod; }
            public void setRetentionPeriod(Duration value) {
                if (value == null || value.isNegative() || value.isZero()) {
                    throw new IllegalArgumentException("retention period must be positive");
                }
                this.retentionPeriod = value;
            }

            public int getFailureThreshold() { return failureThreshold; }
            public void setFailureThreshold(int value) {
                if (value < 1) throw new IllegalArgumentException("failure threshold must be positive");
                this.failureThreshold = value;
            }

            public int getBatchSize() { return batchSize; }
            public void setBatchSize(int value) {
                if (value < 1) throw new IllegalArgumentException("batch size must be positive");
                this.batchSize = value;
            }
        }

        public static class ConditionalRegistration {
            private boolean enabled = true;
            private int minActiveAccounts = 50;
            private int batchTarget = 5;
            private Duration cooldown = Duration.ofHours(24);
            private Duration checkInterval = Duration.ofSeconds(30);

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean value) { this.enabled = value; }

            public int getMinActiveAccounts() { return minActiveAccounts; }
            public void setMinActiveAccounts(int value) {
                if (value < 1) throw new IllegalArgumentException("min active accounts must be positive");
                this.minActiveAccounts = value;
            }

            public int getBatchTarget() { return batchTarget; }
            public void setBatchTarget(int value) {
                if (value < 1) throw new IllegalArgumentException("batch target must be positive");
                this.batchTarget = value;
            }

            public Duration getCooldown() { return cooldown; }
            public void setCooldown(Duration value) {
                if (value == null || value.isNegative() || value.isZero()) {
                    throw new IllegalArgumentException("cooldown must be positive");
                }
                this.cooldown = value;
            }

            public Duration getCheckInterval() { return checkInterval; }
            public void setCheckInterval(Duration value) {
                if (value == null || value.isNegative() || value.isZero()) {
                    throw new IllegalArgumentException("check interval must be positive");
                }
                this.checkInterval = value;
            }
        }
    }

    public static class ModelRuntime {
        private int maxConcurrentRequests = 32;
        private Duration maxQueueWait = Duration.ofSeconds(2);
        public int getMaxConcurrentRequests() { return maxConcurrentRequests; }
        public void setMaxConcurrentRequests(int value) { maxConcurrentRequests = positive(value, "max concurrent requests"); }
        public Duration getMaxQueueWait() { return maxQueueWait; }
        public void setMaxQueueWait(Duration value) { maxQueueWait = positive(value, "max queue wait"); }
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

    public static class EgressGate {
        private boolean enabled = true;
        private int defaultNodeConcurrency = 6;
        private int defaultProviderConcurrency = 12;
        private Duration defaultStagger = Duration.ofMillis(300);
        private Duration acquireTimeout = Duration.ofSeconds(120);
        private java.util.Map<String, Integer> providerConcurrency = new java.util.HashMap<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { this.enabled = value; }

        public int getDefaultNodeConcurrency() { return defaultNodeConcurrency; }
        public void setDefaultNodeConcurrency(int value) {
            if (value < 1) throw new IllegalArgumentException("default node concurrency must be positive");
            this.defaultNodeConcurrency = value;
        }

        public int getDefaultProviderConcurrency() { return defaultProviderConcurrency; }
        public void setDefaultProviderConcurrency(int value) {
            if (value < 1) throw new IllegalArgumentException("default provider concurrency must be positive");
            this.defaultProviderConcurrency = value;
        }

        public Duration getAcquireTimeout() { return acquireTimeout; }
        public void setAcquireTimeout(Duration value) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException("acquire timeout must be positive");
            }
            this.acquireTimeout = value;
        }

        public Duration getDefaultStagger() { return defaultStagger; }
        public void setDefaultStagger(Duration value) {
            if (value == null || value.isNegative()) {
                throw new IllegalArgumentException("default stagger must not be negative");
            }
            this.defaultStagger = value;
        }

        public java.util.Map<String, Integer> getProviderConcurrency() { return providerConcurrency; }
        public void setProviderConcurrency(java.util.Map<String, Integer> value) {
            if (value != null) this.providerConcurrency = new java.util.HashMap<>(value);
        }
    }

}
