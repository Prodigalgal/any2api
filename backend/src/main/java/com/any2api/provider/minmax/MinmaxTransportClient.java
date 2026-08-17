package com.any2api.provider.minmax;

import com.any2api.config.Any2ApiProperties;
import com.any2api.observability.RequestCorrelation;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@Component
final class MinmaxTransportClient {
    private final WebClient client;
    private final String token;

    MinmaxTransportClient(WebClient.Builder builder, Any2ApiProperties properties) {
        client = builder.clone()
            .filter(RequestCorrelation.propagationFilter())
            .baseUrl(properties.getAutomation().getBaseUrl().toString())
            .build();
        token = properties.getSecurity().getInternalToken();
    }

    Mono<TransportResponse> request(
        String method,
        String path,
        String body,
        JsonNode credential,
        Map<String, Object> proxyPool,
        String affinityKey
    ) {
        return client.post()
            .uri("/internal/v1/providers/minmax/transport/request")
            .headers(this::headers)
            .bodyValue(command(method, path, body, credential, proxyPool, affinityKey))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .map(value -> new TransportResponse(
                value.path("status").asInt(502), value.path("body").asText(""),
                value.path("credential_patch").deepCopy()));
    }

    Flux<JsonNode> stream(
        String method,
        String path,
        String body,
        JsonNode credential,
        Map<String, Object> proxyPool,
        String affinityKey
    ) {
        return client.post()
            .uri("/internal/v1/providers/minmax/transport/stream")
            .headers(this::headers)
            .bodyValue(command(method, path, body, credential, proxyPool, affinityKey))
            .retrieve()
            .bodyToFlux(JsonNode.class);
    }

    private Map<String, Object> command(
        String method,
        String path,
        String body,
        JsonNode credential,
        Map<String, Object> proxyPool,
        String affinityKey
    ) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("credential", credential);
        if (proxyPool != null && !proxyPool.isEmpty()) payload.put("proxy_pool", proxyPool);
        if (affinityKey != null && !affinityKey.isBlank()) {
            payload.put("proxy_affinity_key", affinityKey);
            payload.put("strict_proxy_affinity", true);
        }
        return Map.of(
            "method", method,
            "path", path,
            "body", body == null ? "" : body,
            "payload", payload);
    }

    private void headers(HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        if (!token.isBlank()) headers.setBearerAuth(token);
    }

    record TransportResponse(int status, String body, JsonNode credentialPatch) {}
}
