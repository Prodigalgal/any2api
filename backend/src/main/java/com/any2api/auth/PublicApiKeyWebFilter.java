package com.any2api.auth;

import com.any2api.config.Any2ApiProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class PublicApiKeyWebFilter implements WebFilter {

    private final Any2ApiProperties properties;
    private final ApiKeyAuthenticator authenticator;

    public PublicApiKeyWebFilter(
        Any2ApiProperties properties,
        ApiKeyAuthenticator authenticator
    ) {
        this.properties = properties;
        this.authenticator = authenticator;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!isInferencePath(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }
        var expected = properties.getSecurity().getPublicApiKey();
        var header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        var actual = header != null && header.startsWith("Bearer ") ? header.substring(7) : "";
        if (expected != null && !expected.isBlank() && constantTimeEquals(expected, actual)) {
            exchange.getAttributes().put(
                ApiKeyAuthorization.GRANT_ATTRIBUTE, ApiKeyGrant.unrestricted());
            return chain.filter(exchange);
        }
        return authenticator.authenticate(actual)
            .flatMap(grant -> {
                if (grant.isEmpty()) {
                    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                    return exchange.getResponse().setComplete();
                }
                exchange.getAttributes().put(ApiKeyAuthorization.GRANT_ATTRIBUTE, grant.get());
                return chain.filter(exchange);
            });
    }

    private boolean isInferencePath(String path) {
        return path.startsWith("/v1/")
            || path.matches("^/[a-z][a-z0-9_-]{1,31}/v1(?:/.*)?$");
    }

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
            left.getBytes(StandardCharsets.UTF_8),
            right.getBytes(StandardCharsets.UTF_8));
    }
}
