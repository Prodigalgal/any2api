package com.any2api.config;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "any2api")
public class Any2ApiProperties {

    private final Security security = new Security();
    private final Automation automation = new Automation();
    private final Map<String, Provider> providers = new LinkedHashMap<>();

    public Security getSecurity() {
        return security;
    }

    public Automation getAutomation() {
        return automation;
    }

    public Map<String, Provider> getProviders() {
        return providers;
    }

    public static class Security {
        private String publicApiKey = "";
        private String adminUsername = "admin";
        private String adminPassword = "";
        private String internalToken = "";

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

    public static class Provider {
        private URI baseUrl;
        private String apiKey = "";

        public URI getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(URI baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public boolean configured() {
            return baseUrl != null && apiKey != null && !apiKey.isBlank();
        }
    }
}
