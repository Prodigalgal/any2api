package com.any2api.auth;

import com.any2api.config.Any2ApiProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import java.util.regex.Pattern;
import com.any2api.observability.RequestIdWebFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class PublicApiKeyWebFilter implements WebFilter {
    private static final Pattern ROOT_INFERENCE_PATH = Pattern.compile("^/v1(?:/.*)?$");
    private static final Pattern RANDOM_INFERENCE_PATH = Pattern.compile(
        "^/(?:random|multimodal-random)/v1(?:/.*)?$");
    private static final Pattern PROVIDER_INFERENCE_PATH = Pattern.compile(
        "^/[a-z][a-z0-9_-]{1,31}/v1(?:/.*)?$");

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
                    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    var requestId = RequestIdWebFilter.get(exchange);
                    var body = ("{\"error\":{\"type\":\"authentication_error\","
                        + "\"code\":\"invalid_api_key\",\"message\":\"invalid API key\","
                        + "\"param\":null,\"retryable\":false,\"provider\":null,"
                        + "\"model\":null,\"request_id\":\"" + requestId + "\"}}")
                        .getBytes(StandardCharsets.UTF_8);
                    return exchange.getResponse().writeWith(Mono.just(
                        exchange.getResponse().bufferFactory().wrap(body)));
                }
                exchange.getAttributes().put(ApiKeyAuthorization.GRANT_ATTRIBUTE, grant.get());
                return chain.filter(exchange);
            });
    }

    static boolean isInferencePath(String path) {
        return ROOT_INFERENCE_PATH.matcher(path).matches()
            || RANDOM_INFERENCE_PATH.matcher(path).matches()
            || PROVIDER_INFERENCE_PATH.matcher(path).matches();
    }

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
            left.getBytes(StandardCharsets.UTF_8),
            right.getBytes(StandardCharsets.UTF_8));
    }
}
