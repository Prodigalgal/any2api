package com.any2api.provider.glm;

import com.any2api.protocol.CanonicalRequest;
import com.any2api.transport.OfficialBrowserTransportClient;
import com.any2api.transport.OfficialBrowserSemanticCommandFactory;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;

@Component
final class GlmProtocolClient {
    private final OfficialBrowserTransportClient transport;
    private final OfficialBrowserSemanticCommandFactory semanticCommands;

    GlmProtocolClient(
        OfficialBrowserTransportClient transport,
        OfficialBrowserSemanticCommandFactory semanticCommands
    ) {
        this.transport = transport;
        this.semanticCommands = semanticCommands;
    }

    Flux<byte[]> chat(
        JsonNode credential,
        CanonicalRequest request,
        Map<String, Object> proxyPool,
        String affinityKey,
        Consumer<JsonNode> credentialPatchSink
    ) {
        var command = semanticCommands.chat(request);
        var status = new AtomicInteger(-1);
        return transport.stream(
                "glm",
                "chat",
                command,
                credential,
                proxyPool,
                affinityKey)
            .handle((frame, sink) -> {
                var type = frame.path("type").asText("");
                if ("status".equals(type)) {
                    status.set(frame.path("status").asInt(502));
                } else if ("error".equals(type)) {
                    var code = status.get() < 0 ? 502 : status.get();
                    sink.error(new GlmUpstreamException(
                        code,
                        summarize(code, frame.path("data").asText(""))));
                } else if ("data".equals(type) && status.get() < 400) {
                    sink.next(frame.path("data").asText("")
                        .getBytes(StandardCharsets.UTF_8));
                } else if ("credential_patch".equals(type)) {
                    credentialPatchSink.accept(frame.path("data"));
                }
            })
            .cast(byte[].class)
            .concatWith(Flux.defer(() -> status.get() >= 400
                ? Flux.error(new GlmUpstreamException(
                    status.get(),
                    "GLM upstream returned HTTP " + status.get()))
                : Flux.empty()));
    }

    private String summarize(int status, String body) {
        var compact = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        if (compact.length() > 1000) compact = compact.substring(0, 1000);
        return compact.isBlank() ? "GLM upstream returned HTTP " + status
            : "GLM upstream returned HTTP " + status + ": " + compact;
    }
}
