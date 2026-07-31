package com.any2api.provider.longcat;

import com.any2api.protocol.CanonicalRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
final class LongcatRequestMapper {
    private static final Map<String, ModelMode> MODEL_MODES = Map.of(
        "longcat-flash", new ModelMode("1", false, false),
        "longcat-default", new ModelMode("1", false, false),
        "longcat-thinking", new ModelMode("1", true, false),
        "longcat-reason", new ModelMode("1", true, false),
        "longcat-search", new ModelMode("1", false, true),
        "longcat-reason-search", new ModelMode("1", true, true),
        "longcat-pro", new ModelMode("2", true, true));

    private final ObjectMapper mapper;
    private final LongcatToolProtocol toolProtocol;

    LongcatRequestMapper(ObjectMapper mapper, LongcatToolProtocol toolProtocol) {
        this.mapper = mapper;
        this.toolProtocol = toolProtocol;
    }

    LongcatPreparedRequest prepare(CanonicalRequest request) {
        var mode = MODEL_MODES.getOrDefault(request.model(), new ModelMode("1", false, false));
        var options = request.providerOptions();
        var agentId = string(options.get("agent_id"),
            request.rawRequest().path("agent_id").asText(mode.agentId()));
        var rawReason = request.rawRequest().path("reason_enabled");
        var rawSearch = request.rawRequest().path("search_enabled");
        var reason = bool(options.get("reason_enabled"), rawReason.isBoolean()
            ? rawReason.asBoolean() : reasoning(request, mode.reason()));
        var search = bool(options.get("search_enabled"), rawSearch.isBoolean()
            ? rawSearch.asBoolean() : mode.search());
        var toolPlan = toolProtocol.plan(request);
        var prompt = toolProtocol.appendContract(prompt(request.messages()), toolPlan);
        return new LongcatPreparedRequest(prompt, agentId, reason, search, toolPlan, mapper);
    }

    private String prompt(List<JsonNode> messages) {
        var blocks = new ArrayList<String>();
        for (var message : messages) {
            var role = message.path("role").asText("user").toUpperCase();
            var content = content(message.path("content"));
            if ("ASSISTANT".equals(role) && message.path("tool_calls").isArray()) {
                content += "\n" + message.path("tool_calls").toString();
            }
            if (!content.isBlank()) blocks.add("[" + role + "]\n" + content);
        }
        return String.join("\n\n", blocks);
    }

    private String content(JsonNode value) {
        if (value.isTextual()) return value.asText();
        if (!value.isArray()) return "";
        var parts = new ArrayList<String>();
        for (var part : value) {
            if (part.isTextual()) parts.add(part.asText());
            else if (part.isObject()) parts.add(part.path("text").asText(
                part.path("content").asText("")));
        }
        return String.join("\n", parts);
    }

    private boolean reasoning(CanonicalRequest request, boolean fallback) {
        var effort = String.valueOf(request.reasoning().getOrDefault("effort",
            request.rawRequest().path("reasoning_effort").asText(""))).toLowerCase();
        return effort.isBlank() ? fallback : !List.of("none", "minimal").contains(effort);
    }

    private boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean booleanValue ? booleanValue : fallback;
    }

    private String string(Object value, String fallback) {
        var result = value == null ? "" : String.valueOf(value).trim();
        return result.isBlank() ? fallback : result;
    }

    private record ModelMode(String agentId, boolean reason, boolean search) {}
}
