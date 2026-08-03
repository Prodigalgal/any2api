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
        var filter = new PublicApiKeyWebFilter(new Any2ApiProperties(), authenticator);
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
}
