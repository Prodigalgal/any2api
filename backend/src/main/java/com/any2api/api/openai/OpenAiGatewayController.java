package com.any2api.api.openai;

import com.any2api.provider.RemoteProviderGateway;
import com.any2api.provider.ProviderOperation;
import com.any2api.routing.ProviderRouteResolver;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
public class OpenAiGatewayController {

    private final ProviderRouteResolver routeResolver;
    private final RemoteProviderGateway gateway;

    public OpenAiGatewayController(ProviderRouteResolver routeResolver, RemoteProviderGateway gateway) {
        this.routeResolver = routeResolver;
        this.gateway = gateway;
    }

    @PostMapping({
        "/v1/chat/completions",
        "/{provider:[a-z][a-z0-9_-]{1,31}}/v1/chat/completions"
    })
    public Mono<Void> chat(@RequestBody ObjectNode request, ServerWebExchange exchange) {
        return forward(request, exchange, ProviderOperation.CHAT_COMPLETIONS);
    }

    @PostMapping({
        "/v1/responses",
        "/{provider:[a-z][a-z0-9_-]{1,31}}/v1/responses"
    })
    public Mono<Void> responses(@RequestBody ObjectNode request, ServerWebExchange exchange) {
        return forward(request, exchange, ProviderOperation.RESPONSES);
    }

    private Mono<Void> forward(
        ObjectNode request,
        ServerWebExchange exchange,
        ProviderOperation operation
    ) {
        var model = request.path("model").asText("");
        var route = routeResolver.resolve(exchange.getRequest().getPath().value(), model);
        return gateway.forward(route.providerId(), operation, request, route.upstreamModel(), exchange);
    }
}
