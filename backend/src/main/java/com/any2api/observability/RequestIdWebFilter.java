package com.any2api.observability;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class RequestIdWebFilter implements WebFilter {
    public static final String ATTRIBUTE = RequestIdWebFilter.class.getName() + ".requestId";
    public static final String PROVIDER_ATTRIBUTE = RequestIdWebFilter.class.getName() + ".provider";
    public static final String MODEL_ATTRIBUTE = RequestIdWebFilter.class.getName() + ".model";
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var supplied = exchange.getRequest().getHeaders().getFirst("X-Request-Id");
        var requestId = supplied != null && VALID.matcher(supplied).matches()
            ? supplied : UUID.randomUUID().toString();
        exchange.getAttributes().put(ATTRIBUTE, requestId);
        exchange.getResponse().getHeaders().set("X-Request-Id", requestId);
        exchange.getResponse().getHeaders().set("X-Any2API-Request-Id", requestId);
        return chain.filter(exchange).contextWrite(RequestCorrelation.context(requestId));
    }

    public static String get(ServerWebExchange exchange) {
        return String.valueOf(exchange.getAttributeOrDefault(ATTRIBUTE, UUID.randomUUID().toString()));
    }
}
