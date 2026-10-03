package com.any2api.config;

import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.any2api.auth.AdminSessionService;
import com.any2api.auth.AdminSessionWebFilter;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebHandler;

class SecurityConfigurationTest {

    @Test
    void protectedAdminApiReturnsPlainUnauthorizedWithoutBasicChallenge() {
        var client = client();

        client.get().uri("/api/admin/v1/providers")
            .exchange()
            .expectStatus().isUnauthorized()
            .expectHeader().doesNotExist(HttpHeaders.WWW_AUTHENTICATE);
    }

    @Test
    void loginChallengeRemainsPublic() {
        client().get().uri("/api/admin/v1/login-challenge")
            .exchange()
            .expectStatus().isOk();
    }

    @Test
    void sessionStatusRemainsPublic() {
        client().get().uri("/api/admin/v1/session")
            .exchange()
            .expectStatus().isOk();
    }

    @Test
    void encodedSlashesAreAllowedOnlyInsideGetModelIds() {
        var client = client();
        for (var path : List.of("/v1/models/mimo%2Fgroup%2Fmodel",
            "/mimo/v1/models/group%2fmodel")) {
            client.get().uri(URI.create(path)).exchange().expectStatus().isOk();
        }
        client.post().uri(URI.create("/v1/models/mimo%2Fmodel"))
            .exchange().expectStatus().isBadRequest();
    }

    @Test
    void encodedRoutePrefixesAndUnsafeModelPathsRemainRejected() {
        var client = client();
        for (var path : List.of("/v1%2Fmodels/mimo/model", "/mimo%2Fv1/models/model",
            "/api/admin%2Fv1/models/model", "/v1/models/mimo%2F%2Fmodel",
            "/v1/models/mimo%2F..%2Fmodel", "/v1/models/mimo%5Cmodel",
            "/v1/models/mimo%00model", "/v1/models/mimo%252Fmodel")) {
            client.get().uri(URI.create(path)).exchange().expectStatus().isBadRequest();
        }
    }

    private WebTestClient client() {
        var sessions = mock(AdminSessionService.class);
        when(sessions.verify(nullable(String.class))).thenReturn(Optional.empty());
        var adminSessionFilter = new AdminSessionWebFilter(sessions);
        var security = new SecurityConfiguration().securityWebFilterChain(
            ServerHttpSecurity.http(), adminSessionFilter);
        var proxy = new WebFilterChainProxy(security);
        proxy.setFirewall(new SecurityConfiguration().modelIdServerWebExchangeFirewall());
        WebHandler terminal = exchange -> {
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            return exchange.getResponse().setComplete();
        };
        return WebTestClient.bindToWebHandler(
            exchange -> proxy.filter(exchange, terminal::handle)).build();
    }
}
