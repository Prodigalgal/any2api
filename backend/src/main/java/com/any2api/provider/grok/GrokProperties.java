package com.any2api.provider.grok;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "any2api.provider.grok")
public class GrokProperties {

    private URI baseUrl = URI.create("https://cli-chat-proxy.grok.com/v1");
    private URI tokenUrl = URI.create("https://auth.x.ai/oauth2/token");
    private String oidcClientId = "xai-grok-cli";
    private String clientVersion = "0.2.112";
    private String tokenAuth = "xai-grok-cli";
    private String clientIdentifier = "grok-shell";

    public URI getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(URI baseUrl) {
        this.baseUrl = baseUrl;
    }

    public URI getTokenUrl() {
        return tokenUrl;
    }

    public void setTokenUrl(URI tokenUrl) {
        this.tokenUrl = tokenUrl;
    }

    public String getOidcClientId() {
        return oidcClientId;
    }

    public void setOidcClientId(String oidcClientId) {
        this.oidcClientId = oidcClientId;
    }

    public String getClientVersion() {
        return clientVersion;
    }

    public void setClientVersion(String clientVersion) {
        this.clientVersion = clientVersion;
    }

    public String getTokenAuth() { return tokenAuth; }
    public void setTokenAuth(String tokenAuth) { this.tokenAuth = tokenAuth; }
    public String getClientIdentifier() { return clientIdentifier; }
    public void setClientIdentifier(String clientIdentifier) { this.clientIdentifier = clientIdentifier; }
}
