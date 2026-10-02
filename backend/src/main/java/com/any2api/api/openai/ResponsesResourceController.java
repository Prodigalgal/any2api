package com.any2api.api.openai;

import com.any2api.auth.ApiKeyAuthorization;
import com.any2api.protocol.state.ResponsesService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.node.ObjectNode;

@RestController
public class ResponsesResourceController {
    private final ResponsesService responses;
    private final ApiKeyAuthorization authorization;

    public ResponsesResourceController(ResponsesService responses, ApiKeyAuthorization authorization) {
        this.responses = responses;
        this.authorization = authorization;
    }

    @GetMapping({"/v1/responses/{id}", "/{provider:[a-z][a-z0-9_-]{1,31}}/v1/responses/{id}",
        "/random/v1/responses/{id}", "/multimodal-random/v1/responses/{id}"})
    public Mono<ObjectNode> retrieve(@PathVariable String id,
        @PathVariable(required=false) String provider, @RequestParam(defaultValue="false") boolean stream,
        ServerWebExchange exchange) {
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        if (stream) return Mono.error(com.any2api.protocol.OpenAiRequestException.unsupported(
            "stream", "stored responses are retrieved as JSON; stream resumption is unsupported"));
        return responses.retrieve(id, provider, authorization.grant(exchange));
    }

    @DeleteMapping({"/v1/responses/{id}", "/{provider:[a-z][a-z0-9_-]{1,31}}/v1/responses/{id}",
        "/random/v1/responses/{id}", "/multimodal-random/v1/responses/{id}"})
    public Mono<ObjectNode> delete(@PathVariable String id,
        @PathVariable(required=false) String provider, ServerWebExchange exchange) {
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        return responses.delete(id, provider, authorization.grant(exchange));
    }

    @GetMapping({"/v1/responses/{id}/input_items", "/{provider:[a-z][a-z0-9_-]{1,31}}/v1/responses/{id}/input_items",
        "/random/v1/responses/{id}/input_items", "/multimodal-random/v1/responses/{id}/input_items"})
    public Mono<ObjectNode> inputItems(@PathVariable String id,
        @PathVariable(required=false) String provider, @RequestParam(defaultValue="20") int limit,
        @RequestParam(defaultValue="desc") String order, @RequestParam(required=false) String after,
        @RequestParam(required=false) String before, ServerWebExchange exchange) {
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        return responses.listInput(id, provider, authorization.grant(exchange), limit, order, after, before);
    }
}
