package com.any2api.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;

class RequestCorrelationTest {

    @Test
    void propagatesCorrelationOnlyWhenTheReactiveRequestContextProvidesIt() {
        var captured = new AtomicReference<ClientRequest>();
        var request = ClientRequest.create(HttpMethod.POST, URI.create("http://automation/internal"))
            .build();
        var next = (org.springframework.web.reactive.function.client.ExchangeFunction) value -> {
            captured.set(value);
            return reactor.core.publisher.Mono.just(
                ClientResponse.create(HttpStatus.OK).build());
        };

        RequestCorrelation.propagationFilter().filter(request, next)
            .contextWrite(RequestCorrelation.context("request-correlation-123"))
            .block();

        assertThat(captured.get().headers().getFirst(RequestCorrelation.HEADER))
            .isEqualTo("request-correlation-123");
    }
}
