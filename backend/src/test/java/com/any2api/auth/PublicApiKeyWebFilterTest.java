package com.any2api.auth;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.any2api.config.Any2ApiProperties;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebHandler;
import reactor.core.publisher.Mono;

class PublicApiKeyWebFilterTest {
    @Test
    void bothRandomRouteFamiliesRequireAnApiKey() {
        var authenticator = mock(ApiKeyAuthenticator.class);
        when(authenticator.authenticate(anyString())).thenReturn(Mono.just(Optional.empty()));
        var properties = new Any2ApiProperties();
        var rateLimiter = new ApiKeyRateLimiter(properties);
        var filter = new PublicApiKeyWebFilter(properties, authenticator, rateLimiter);
        WebHandler terminal = exchange -> {
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            return exchange.getResponse().setComplete();
        };
        var client = WebTestClient.bindToWebHandler(
            exchange -> filter.filter(exchange, terminal::handle)).build();

        client.post().uri("/random/v1/chat/completions")
            .exchange().expectStatus().isUnauthorized();
        client.post().uri("/multimodal-random/v1/chat/completions")
            .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void rejectsWith429WhenRateLimitExceeded() {
        var authenticator = mock(ApiKeyAuthenticator.class);
        var keyId = java.util.UUID.randomUUID();
        var grant = new ApiKeyGrant(keyId, "test", java.util.Map.of(), java.util.Set.of(),
            java.util.Set.of(), null, true);
        when(authenticator.authenticate(anyString())).thenReturn(Mono.just(Optional.of(grant)));

        // 限流器设置为最大并发 1，最大 RPM 1
        var rateLimiter = new ApiKeyRateLimiter(1, 1);
        var filter = new PublicApiKeyWebFilter(new Any2ApiProperties(), authenticator, rateLimiter);

        WebHandler terminal = exchange -> {
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            return exchange.getResponse().setComplete();
        };
        var client = WebTestClient.bindToWebHandler(
            exchange -> filter.filter(exchange, terminal::handle)).build();

        // 第一次请求成功
        client.post().uri("/v1/chat/completions")
            .header("Authorization", "Bearer valid-key")
            .exchange().expectStatus().isOk();

        // 第二次请求立即触发 RPM 限制 -> 429 Too Many Requests
        client.post().uri("/v1/chat/completions")
            .header("Authorization", "Bearer valid-key")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
            .expectHeader().valueEquals("Retry-After", "5")
            .expectHeader().valueEquals("X-RateLimit-Remaining-Requests", "0")
            .expectBody().jsonPath("$.error.type").isEqualTo("rate_limit_error");
    }
}
