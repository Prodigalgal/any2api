package com.any2api.provider.glm;

import com.any2api.config.Any2ApiProperties;
import com.any2api.observability.RequestCorrelation;
import com.any2api.transport.BrowserTransportClient;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
final class GlmCaptchaClient {
    private final WebClient client;
    private final ObjectMapper mapper;
    private final String token;

    GlmCaptchaClient(
        WebClient.Builder builder,
        Any2ApiProperties properties,
        ObjectMapper mapper
    ) {
        this.client = builder.clone()
            .filter(RequestCorrelation.propagationFilter())
            .baseUrl(properties.getAutomation().getBaseUrl().toString())
            .build();
        this.mapper = mapper;
        this.token = properties.getSecurity().getInternalToken();
    }

    Mono<Flow> prepare(String browserSessionId, int timeoutSeconds) {
        var body = mapper.createObjectNode().put("timeout_seconds", timeoutSeconds);
        return client.post()
            .uri(
                "/internal/v1/providers/glm/browser-sessions/{sessionId}/captcha",
                browserSessionId)
            .headers(headers -> {
                headers.setContentType(MediaType.APPLICATION_JSON);
                if (!token.isBlank()) headers.setBearerAuth(token);
            })
            .bodyValue(body)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .map(result -> {
                var flowId = result.path("flow_id").asText("").trim();
                if (!flowId.matches("[0-9a-f]{32}")) {
                    throw new GlmUpstreamException(
                        502, "GLM captcha worker returned an invalid flow id");
                }
                return new Flow(flowId);
            });
    }

    Flux<byte[]> stream(
        String browserSessionId,
        Flow flow,
        BrowserTransportClient.Request request
    ) {
        return client.post()
            .uri(
                "/internal/v1/providers/glm/browser-sessions/{sessionId}"
                    + "/captcha/flows/{flowId}/stream",
                browserSessionId,
                flow.id())
            .headers(headers -> {
                headers.setContentType(MediaType.APPLICATION_JSON);
                if (!token.isBlank()) headers.setBearerAuth(token);
            })
            .bodyValue(requestBody(request))
            .exchangeToFlux(response -> {
                var status = response.statusCode().value();
                if (!response.statusCode().is2xxSuccessful()) {
                    return response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0])
                        .flatMapMany(body -> Flux.error(new GlmUpstreamException(
                            status, summarize(status, body))));
                }
                return response.bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class)
                    .map(buffer -> {
                        var bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        DataBufferUtils.release(buffer);
                        return bytes;
                    });
            });
    }

    private ObjectNode requestBody(BrowserTransportClient.Request request) {
        var body = mapper.createObjectNode()
            .put("method", request.method())
            .put("path", request.path())
            .put("fingerprint_profile", request.fingerprintProfile().externalName())
            .put("timeout_seconds", request.timeoutSeconds())
            .put("referer_path", request.refererPath());
        body.set("headers", mapper.valueToTree(request.headers()));
        if (request.body() != null && !request.body().isNull()) {
            body.set("json_body", request.body());
        }
        return body;
    }

    private String summarize(int status, byte[] body) {
        var compact = new String(body, StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
        if (compact.length() > 500) compact = compact.substring(0, 500);
        return compact.isBlank() ? "GLM bound browser flow returned HTTP " + status
            : "GLM bound browser flow returned HTTP " + status + ": " + compact;
    }

    record Flow(String id) {}
}
