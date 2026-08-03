package com.any2api.api.openai;

import com.any2api.auth.ApiKeyAuthorization;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.protocol.CanonicalRequestParser;
import com.any2api.protocol.OpenAiResponseWriter;
import com.any2api.provider.InferenceCoordinator;
import com.any2api.routing.ProviderRouteResolver;
import com.any2api.routing.RandomInferenceRouter;
import com.any2api.provider.RandomModelRole;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
public class OpenAiGatewayController {

    private final ProviderRouteResolver routeResolver;
    private final CanonicalRequestParser requestParser;
    private final InferenceCoordinator coordinator;
    private final OpenAiResponseWriter responseWriter;
    private final RandomInferenceRouter randomRouter;
    private final ApiKeyAuthorization authorization;

    public OpenAiGatewayController(
        ProviderRouteResolver routeResolver,
        CanonicalRequestParser requestParser,
        InferenceCoordinator coordinator,
        OpenAiResponseWriter responseWriter,
        RandomInferenceRouter randomRouter,
        ApiKeyAuthorization authorization
    ) {
        this.routeResolver = routeResolver;
        this.requestParser = requestParser;
        this.coordinator = coordinator;
        this.responseWriter = responseWriter;
        this.randomRouter = randomRouter;
        this.authorization = authorization;
    }

    @PostMapping({
        "/v1/chat/completions",
        "/{provider:[a-z][a-z0-9_-]{1,31}}/v1/chat/completions"
    })
    public Mono<Void> chat(@RequestBody ObjectNode request, ServerWebExchange exchange) {
        return execute(request, exchange, CanonicalRequest.Protocol.CHAT_COMPLETIONS);
    }

    @PostMapping({
        "/v1/responses",
        "/{provider:[a-z][a-z0-9_-]{1,31}}/v1/responses"
    })
    public Mono<Void> responses(@RequestBody ObjectNode request, ServerWebExchange exchange) {
        return execute(request, exchange, CanonicalRequest.Protocol.RESPONSES);
    }

    @PostMapping("/random/v1/chat/completions")
    public Mono<Void> randomChat(
        @RequestBody ObjectNode request,
        ServerWebExchange exchange
    ) {
        return executeRandom(request, exchange, CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            RandomModelRole.TOP_TEXT);
    }

    @PostMapping("/random/v1/responses")
    public Mono<Void> randomResponses(
        @RequestBody ObjectNode request,
        ServerWebExchange exchange
    ) {
        return executeRandom(request, exchange, CanonicalRequest.Protocol.RESPONSES,
            RandomModelRole.TOP_TEXT);
    }

    @PostMapping("/multimodal-random/v1/chat/completions")
    public Mono<Void> multimodalRandomChat(
        @RequestBody ObjectNode request,
        ServerWebExchange exchange
    ) {
        return executeRandom(request, exchange, CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            RandomModelRole.TOP_MULTIMODAL);
    }

    @PostMapping("/multimodal-random/v1/responses")
    public Mono<Void> multimodalRandomResponses(
        @RequestBody ObjectNode request,
        ServerWebExchange exchange
    ) {
        return executeRandom(request, exchange, CanonicalRequest.Protocol.RESPONSES,
            RandomModelRole.TOP_MULTIMODAL);
    }

    private Mono<Void> execute(
        ObjectNode request,
        ServerWebExchange exchange,
        CanonicalRequest.Protocol protocol
    ) {
        var model = request.path("model").asText("");
        var route = routeResolver.resolve(exchange.getRequest().getPath().value(), model);
        authorization.require(
            exchange, authorization.protocol(protocol), route.providerId(), route.upstreamModel());
        var canonical = requestParser.parse(protocol, route, request);
        return responseWriter.write(canonical, coordinator.execute(canonical), exchange);
    }

    private Mono<Void> executeRandom(
        ObjectNode request,
        ServerWebExchange exchange,
        CanonicalRequest.Protocol protocol,
        RandomModelRole role
    ) {
        var grant = authorization.grant(exchange);
        if (!grant.allowsProtocol(authorization.protocol(protocol))) {
            throw new com.any2api.auth.ApiKeyScopeException(
                "API key does not allow this protocol");
        }
        return randomRouter.select(protocol, request, role, grant).flatMap(selection -> {
            var canonical = selection.request();
            exchange.getResponse().getHeaders().set(
                "X-Any2API-Provider", canonical.providerId());
            exchange.getResponse().getHeaders().set(
                "X-Any2API-Model", canonical.model());
            return responseWriter.write(
                canonical, coordinator.execute(canonical, selection.account()), exchange);
        });
    }
}
