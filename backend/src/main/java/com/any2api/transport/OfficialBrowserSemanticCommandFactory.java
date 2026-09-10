package com.any2api.transport;

import com.any2api.protocol.CanonicalRequest;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public final class OfficialBrowserSemanticCommandFactory {
    /**
     * Fields that are not part of the canonical maps but are needed by a provider mapper.
     * This is an explicit semantic allowlist; the public request must never cross the
     * Action boundary as an opaque raw object.
     */
    private static final List<String> CONTROL_FIELDS = List.of(
        "tool_choice", "parallel_tool_calls", "thinking", "reasoning_effort",
        "web_search", "web_search_status", "agent_id", "reason_enabled", "search_enabled",
        "enable_thinking", "enable_search", "search", "thinking_mode", "thinking_budget",
        "preview_mode", "previous_response_id", "include", "metadata",
        "prompt_cache_key", "service_tier", "background", "truncation", "store",
        "conversation_id", "conversation", "thread_id", "session_id", "user",
        "stream_tool_calls");

    private final ObjectMapper mapper;

    public OfficialBrowserSemanticCommandFactory(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ObjectNode models() {
        return mapper.createObjectNode().put("schemaVersion", 1);
    }

    public ObjectNode chat(CanonicalRequest request) {
        var command = mapper.createObjectNode()
            .put("schemaVersion", 1)
            .put("requestId", request.requestId())
            .put("protocol", request.protocol().name())
            .put("model", request.model())
            .put("stream", request.stream());
        command.set("messages", mapper.valueToTree(request.messages()));
        command.set("generation", mapper.valueToTree(request.generation()));
        command.set("reasoning", mapper.valueToTree(request.reasoning()));
        command.set("tools", mapper.valueToTree(request.tools()));
        command.set("providerOptions", mapper.valueToTree(request.providerOptions()));
        var controls = command.putObject("controls");
        CONTROL_FIELDS.forEach(field -> copy(request, controls, field));
        return command;
    }

    private static void copy(CanonicalRequest request, ObjectNode target, String field) {
        if (request.rawRequest() != null && request.rawRequest().has(field)) {
            target.set(field, request.rawRequest().path(field).deepCopy());
        }
    }
}
