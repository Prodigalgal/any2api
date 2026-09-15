package com.any2api.config;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/**
 * Shared HTTP client for internal service calls.
 * Short idle time drops stale ClusterIP connections after pod rollouts;
 * LifecycleAutomationClient retries one transient DNS/connect failure.
 */
@Configuration
public class WebClientConfiguration {

    @Bean
    public WebClient.Builder webClientBuilder() {
        var provider = ConnectionProvider.builder("any2api-internal")
            .maxConnections(200)
            .pendingAcquireTimeout(Duration.ofSeconds(10))
            .maxIdleTime(Duration.ofSeconds(30))
            .build();
        var http = HttpClient.create(provider)
            .responseTimeout(Duration.ofMinutes(5));
        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(http));
    }
}
