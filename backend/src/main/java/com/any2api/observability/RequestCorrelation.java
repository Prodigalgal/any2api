package com.any2api.observability;

import java.util.function.Function;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.util.context.Context;

public final class RequestCorrelation {
    public static final String HEADER = "X-Any2API-Correlation-Id";
    private static final String CONTEXT_KEY = RequestCorrelation.class.getName();

    private RequestCorrelation() {}

    public static Function<Context, Context> context(String correlationId) {
        return context -> context.put(CONTEXT_KEY, correlationId);
    }

    public static ExchangeFilterFunction propagationFilter() {
        return (request, next) -> reactor.core.publisher.Mono.deferContextual(context -> {
            if (!context.hasKey(CONTEXT_KEY)) return next.exchange(request);
            var propagated = ClientRequest.from(request)
                .headers(headers -> {
                    headers.set(HEADER, context.get(CONTEXT_KEY));
                    headers.set("X-Request-Id", context.get(CONTEXT_KEY));
                })
                .build();
            return next.exchange(propagated);
        });
    }
}
