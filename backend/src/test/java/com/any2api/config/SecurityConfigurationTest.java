package com.any2api.config;

import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.any2api.auth.AdminSessionService;
import com.any2api.auth.AdminSessionWebFilter;
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

    private WebTestClient client() {
        var sessions = mock(AdminSessionService.class);
        when(sessions.verify(nullable(String.class))).thenReturn(Optional.empty());
        var adminSessionFilter = new AdminSessionWebFilter(sessions);
        var security = new SecurityConfiguration().securityWebFilterChain(
            ServerHttpSecurity.http(), adminSessionFilter);
        var proxy = new WebFilterChainProxy(security);
        WebHandler terminal = exchange -> {
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            return exchange.getResponse().setComplete();
        };
        return WebTestClient.bindToWebHandler(
            exchange -> proxy.filter(exchange, terminal::handle)).build();
    }
}
