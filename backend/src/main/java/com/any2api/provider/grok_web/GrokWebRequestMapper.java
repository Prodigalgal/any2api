package com.any2api.provider.grok_web;

import com.any2api.protocol.CanonicalRequest;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
final class GrokWebRequestMapper {
    private final ObjectMapper mapper;
    private final GrokWebToolProtocol tools;

    GrokWebRequestMapper(ObjectMapper mapper, GrokWebToolProtocol tools) {
        this.mapper = mapper;
        this.tools = tools;
    }

    Prepared prepare(CanonicalRequest request) {
        var spec = GrokWebModelCatalog.require(request.model());
        if (spec.kind() != GrokWebModelCatalog.Kind.CHAT) {
            throw new IllegalArgumentException("Grok Web model is not a conversation model: " + request.model());
        }
        var configuration = tools.parse(request);
        var prompt = tools.inject(prompt(request), configuration);
        return new Prepared(payload(prompt, spec.mode()), configuration, tools.sieve(configuration));
    }

    void validateTools(CanonicalRequest request) {
        tools.parse(request);
    }

    private String prompt(CanonicalRequest request) {
        var value = new StringBuilder();
        for (var message : request.messages()) {
            var history = tools.history(message);
            var type = message.path("type").asText("").trim().toLowerCase();
            if ("function_call".equals(type) || "function_call_output".equals(type)) {
                if (!history.isBlank()) value.append(history).append("\n\n");
                continue;
            }
            var role = message.path("role").asText("user");
            var content = text(message.path("content"));
            if (!history.isBlank()) content = content.isBlank() ? history : content + "\n" + history;
            if (!content.isBlank()) value.append('[').append(role).append("]\n")
                .append(content).append("\n\n");
        }
        return value.toString().trim();
    }

    private String text(JsonNode content) {
        if (content.isTextual()) return content.asText();
        if (!content.isArray()) return content.isMissingNode() ? "" : content.toString();
        var value = new StringBuilder();
        for (var part : content) {
            var type = part.path("type").asText("text");
            if ("text".equals(type) || "input_text".equals(type) || "output_text".equals(type)) {
                value.append(part.path("text").asText(""));
            }
        }
        return value.toString();
    }

    private ObjectNode payload(String message, String mode) {
        var payload = mapper.createObjectNode()
            .put("disableMemory", true)
            .put("disableSearch", false)
            .put("disableSelfHarmShortCircuit", false)
            .put("disableTextFollowUps", false)
            .put("enableImageGeneration", true)
            .put("enableImageStreaming", true)
            .put("enableSideBySide", true)
            .put("forceConcise", false)
            .put("forceSideBySide", false)
            .put("imageGenerationCount", 2)
            .put("isAsyncChat", false)
            .put("message", message)
            .put("modeId", mode)
            .put("returnImageBytes", false)
            .put("returnRawGrokInXaiRequest", false)
            .put("sendFinalMetadata", true)
            .put("temporary", true);
        payload.set("collectionIds", mapper.createArrayNode());
        payload.set("disabledConnectorIds", mapper.createArrayNode());
        payload.set("fileAttachments", mapper.createArrayNode());
        payload.set("imageAttachments", mapper.createArrayNode());
        payload.set("responseMetadata", mapper.createObjectNode());
        payload.set("deviceEnvInfo", mapper.createObjectNode()
            .put("darkModeEnabled", false).put("devicePixelRatio", 2)
            .put("screenHeight", 1328).put("screenWidth", 2056)
            .put("viewportHeight", 1083).put("viewportWidth", 2056));
        return payload;
    }

    record Prepared(
        ObjectNode body,
        GrokWebToolProtocol.Configuration tools,
        GrokWebToolProtocol.StreamSieve toolSieve
    ) {}
}
