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
 * Idle connections expire before Automation's five-second keep-alive;
 * LifecycleAutomationClient retries one transient DNS/connect failure.
 */
@Configuration
public class WebClientConfiguration {

    @Bean(destroyMethod = "dispose")
    public ConnectionProvider internalConnectionProvider() {
        return ConnectionProvider.builder("any2api-internal")
            .maxConnections(200)
            .pendingAcquireTimeout(Duration.ofSeconds(10))
            .maxIdleTime(Duration.ofSeconds(3))
            .evictInBackground(Duration.ofSeconds(1))
            .build();
    }

    @Bean
    public WebClient.Builder webClientBuilder(ConnectionProvider internalConnectionProvider) {
        var http = HttpClient.create(internalConnectionProvider)
            .responseTimeout(Duration.ofMinutes(15));
        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(http));
    }
}
