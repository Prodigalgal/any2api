package com.any2api.protocol;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Automatically guards upstream reverse-engineered web interfaces against
 * context window blowup and turn limit rejections (e.g. 40-turn limits).
 * Preserves the initial system instructions and recent conversation turns,
 * ensuring tool call message pairs remain intact.
 */
@Component
public class SmartContextWindowManager {

    private static final Logger log = LoggerFactory.getLogger(SmartContextWindowManager.class);
    public static final int DEFAULT_MAX_MESSAGES = 32;

    private final ObjectMapper mapper;

    public SmartContextWindowManager(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public CanonicalRequest guard(CanonicalRequest request, JsonNode modelCapabilities) {
        if (request == null || request.messages() == null || request.messages().isEmpty()) {
            return request;
        }
        int maxMessages = resolveMaxMessages(request, modelCapabilities);
        var messages = request.messages();
        if (messages.size() <= maxMessages) {
            return request;
        }

        var systemMessages = new ArrayList<JsonNode>();
        int firstNonSystemIdx = 0;
        for (int i = 0; i < messages.size(); i++) {
            var msg = messages.get(i);
            if ("system".equalsIgnoreCase(msg.path("role").asText(""))) {
                systemMessages.add(msg);
                firstNonSystemIdx = i + 1;
            } else {
                break;
            }
        }

        int budgetForTail = maxMessages - systemMessages.size() - 1; // 1 for notice
        if (budgetForTail <= 1) {
            budgetForTail = Math.min(messages.size() - firstNonSystemIdx, 6);
        }

        int tailStart = Math.max(firstNonSystemIdx, messages.size() - budgetForTail);

        // Tool call pair protection: if the tail starts on a tool message, include its preceding assistant call
        if (tailStart > firstNonSystemIdx) {
            var startMsg = messages.get(tailStart);
            if ("tool".equalsIgnoreCase(startMsg.path("role").asText(""))) {
                var prev = messages.get(tailStart - 1);
                if ("assistant".equalsIgnoreCase(prev.path("role").asText(""))
                    && prev.has("tool_calls")) {
                    tailStart--;
                }
            }
        }

        var trimmed = new ArrayList<JsonNode>();
        trimmed.addAll(systemMessages);

        var noticeNode = mapper.createObjectNode()
            .put("role", "system")
            .put("content", "[Notice: Earlier conversation history was safely compacted by Any2API gateway]");
        trimmed.add(noticeNode);

        for (int i = tailStart; i < messages.size(); i++) {
            trimmed.add(messages.get(i));
        }

        log.info(
            "context_window_compacted correlation_id={} provider={} model={} original_count={} compacted_count={}",
            request.requestId(), request.providerId(), request.model(), messages.size(), trimmed.size());

        var rawCopy = request.rawRequest() instanceof ObjectNode obj ? obj.deepCopy() : mapper.createObjectNode();
        var messagesArray = rawCopy.putArray("messages");
        trimmed.forEach(messagesArray::add);

        return new CanonicalRequest(
            request.requestId(),
            request.protocol(),
            request.providerId(),
            request.model(),
            request.stream(),
            List.copyOf(trimmed),
            request.generation(),
            request.reasoning(),
            request.tools(),
            request.providerOptions(),
            rawCopy);
    }

    private int resolveMaxMessages(CanonicalRequest request, JsonNode modelCapabilities) {
        if (modelCapabilities != null && modelCapabilities.has("max_context_messages")) {
            int custom = modelCapabilities.path("max_context_messages").asInt(0);
            if (custom > 4) {
                return custom;
            }
        }
        return DEFAULT_MAX_MESSAGES;
    }
}
