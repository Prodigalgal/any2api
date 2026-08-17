package com.any2api.transport;

import com.any2api.protocol.CanonicalRequest;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public final class OfficialBrowserSemanticCommandFactory {
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
        copy(request, controls, "tool_choice");
        copy(request, controls, "parallel_tool_calls");
        copy(request, controls, "thinking");
        copy(request, controls, "reasoning_effort");
        copy(request, controls, "web_search");
        copy(request, controls, "web_search_status");
        return command;
    }

    private static void copy(CanonicalRequest request, ObjectNode target, String field) {
        if (request.rawRequest() != null && request.rawRequest().has(field)) {
            target.set(field, request.rawRequest().path(field).deepCopy());
        }
    }
}
