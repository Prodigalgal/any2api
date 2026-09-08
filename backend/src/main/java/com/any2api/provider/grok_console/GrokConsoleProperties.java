package com.any2api.provider.grok_console;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "any2api.provider.grok-console")
public class GrokConsoleProperties {
    private URI baseUrl = URI.create("https://console.x.ai");
    private String cluster = "https://us-east-1.api.x.ai";

    public URI getBaseUrl() { return baseUrl; }
    public void setBaseUrl(URI baseUrl) { this.baseUrl = baseUrl; }
    public String getCluster() { return cluster; }
    public void setCluster(String cluster) { this.cluster = cluster; }
}
