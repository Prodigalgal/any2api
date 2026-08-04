package com.any2api.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class RequestIdWebFilterTest {
    @Test
    void preservesValidClientRequestIdInBothResponseHeaders() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/healthz")
            .header("X-Request-Id", "client-request-42"));

        new RequestIdWebFilter().filter(exchange, current -> {
            current.getResponse().setStatusCode(HttpStatus.OK);
            return current.getResponse().setComplete();
        }).block();

        assertThat(exchange.getResponse().getHeaders().getFirst("X-Request-Id"))
            .isEqualTo("client-request-42");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Any2API-Request-Id"))
            .isEqualTo("client-request-42");
        assertThat(RequestIdWebFilter.get(exchange)).isEqualTo("client-request-42");
    }
}
