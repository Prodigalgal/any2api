package com.any2api.provider;

import java.nio.charset.StandardCharsets;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.node.ObjectNode;

@Component
public class RemoteProviderGateway {

    private final ProviderRegistry registry;
    private final WebClient.Builder webClientBuilder;

    public RemoteProviderGateway(ProviderRegistry registry, WebClient.Builder webClientBuilder) {
        this.registry = registry;
        this.webClientBuilder = webClientBuilder;
    }

    public Mono<Void> forward(
        String providerId,
        ProviderOperation operation,
        ObjectNode requestBody,
        String upstreamModel,
        ServerWebExchange exchange
    ) {
        var provider = registry.require(providerId);
        if (!provider.manifest().configured()) {
            return unavailable(exchange, providerId);
        }
        var request = provider.prepare(operation, requestBody, upstreamModel);
        var client = webClientBuilder.baseUrl(trimTrailingSlash(request.baseUrl().toString())).build();
        return client.post()
            .uri(request.upstreamPath())
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + request.apiKey())
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
            .bodyValue(request.body())
            .exchangeToMono(upstream -> {
                exchange.getResponse().setStatusCode(upstream.statusCode());
                var contentType = upstream.headers().contentType().orElse(MediaType.APPLICATION_JSON);
                exchange.getResponse().getHeaders().setContentType(contentType);
                exchange.getResponse().getHeaders().setCacheControl("no-store");
                if (MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType)) {
                    exchange.getResponse().getHeaders().set("X-Accel-Buffering", "no");
                }
                Flux<DataBuffer> body = upstream.bodyToFlux(DataBuffer.class);
                return exchange.getResponse().writeWith(body);
            });
    }

    private Mono<Void> unavailable(ServerWebExchange exchange, String providerId) {
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        var payload = ("{\"error\":{\"type\":\"provider_unavailable\",\"message\":\"provider "
            + providerId + " is not configured\"}}")
            .getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(
            Mono.just(exchange.getResponse().bufferFactory().wrap(payload)));
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
