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
 * Applies caller-requested truncation without silently dropping agent history.
 * Declared upstream message limits are rejected when truncation is disabled.
 * Explicit auto truncation preserves instructions and complete tool call pairs.
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
        if (!"auto".equals(request.rawRequest().path("truncation").asText())) {
            throw OpenAiRequestException.invalid("input", "context message limit exceeded with truncation disabled");
        }

        var systemMessages = new ArrayList<JsonNode>();
        int firstNonSystemIdx = 0;
        for (int i = 0; i < messages.size(); i++) {
            var msg = messages.get(i);
            if (List.of("system", "developer").contains(msg.path("role").asText(""))) {
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

        tailStart = retainToolCallBoundary(messages, tailStart, firstNonSystemIdx);

        var trimmed = new ArrayList<JsonNode>();
        trimmed.addAll(systemMessages);

        var noticeNode = mapper.createObjectNode()
            .put("role", "system")
            .put("content", "[Notice: Earlier conversation history was truncated by Any2API gateway]");
        trimmed.add(noticeNode);

        for (int i = tailStart; i < messages.size(); i++) {
            trimmed.add(messages.get(i));
        }

        log.info(
            "context_window_truncated correlation_id={} provider={} model={} original_count={} retained_count={}",
            request.requestId(), request.providerId(), request.model(), messages.size(), trimmed.size());

        var rawCopy = request.rawRequest() instanceof ObjectNode obj ? obj.deepCopy() : mapper.createObjectNode();
        if (request.protocol() == CanonicalRequest.Protocol.CHAT_COMPLETIONS) {
            var messagesArray = rawCopy.putArray("messages");
            trimmed.forEach(messagesArray::add);
        }

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
        return "auto".equals(request.rawRequest().path("truncation").asText())
            ? DEFAULT_MAX_MESSAGES : Integer.MAX_VALUE;
    }

    private int retainToolCallBoundary(List<JsonNode> messages, int tailStart, int firstConversationIndex) {
        var calls = new java.util.HashMap<String, Integer>();
        for (var index = firstConversationIndex; index < messages.size(); index++) {
            for (var call : messages.get(index).path("tool_calls")) {
                if (call.hasNonNull("id")) calls.putIfAbsent(call.path("id").asText(), index);
            }
        }
        // Commentary can occur between parallel calls and results. Keep every referenced call,
        // even when the complete group slightly exceeds the message-count truncation target.
        for (var index = messages.size() - 1; index >= tailStart; index--) {
            var message = messages.get(index);
            if ("tool".equals(message.path("role").asText())) {
                tailStart = Math.min(tailStart, calls.getOrDefault(message.path("tool_call_id").asText(), tailStart));
            }
        }
        return tailStart;
    }
}
