package com.any2api.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class SmartContextWindowManagerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SmartContextWindowManager manager = new SmartContextWindowManager(mapper);

    @Test
    void preservesRequestWhenMessagesWithinLimit() {
        var messages = List.of(
            msg("system", "You are a helpful assistant"),
            msg("user", "Hello"),
            msg("assistant", "Hi there!")
        );
        var request = createRequest(messages);
        var guarded = manager.guard(request, null);

        assertThat(guarded.messages()).hasSize(3);
        assertThat(guarded.messages().get(0).path("content").asText())
            .isEqualTo("You are a helpful assistant");
    }

    @Test
    void compactsExcessiveMessagesWhilePreservingSystemAndTail() {
        var messages = new ArrayList<JsonNode>();
        messages.add(msg("system", "Core system prompt"));
        for (int i = 1; i <= 40; i++) {
            messages.add(msg(i % 2 == 1 ? "user" : "assistant", "Turn " + i));
        }

        var request = createRequest(messages);
        var guarded = manager.guard(request, null);

        assertThat(guarded.messages().size()).isLessThanOrEqualTo(32);
        // System prompt preserved
        assertThat(guarded.messages().get(0).path("content").asText())
            .isEqualTo("Core system prompt");
        // Compaction notice inserted
        assertThat(guarded.messages().get(1).path("content").asText())
            .contains("compacted by Any2API");
        // Latest messages preserved
        assertThat(guarded.messages().get(guarded.messages().size() - 1).path("content").asText())
            .isEqualTo("Turn 40");
    }

    @Test
    void protectsToolCallPairsWhenCompacting() {
        var messages = new ArrayList<JsonNode>();
        messages.add(msg("system", "Tool agent system"));
        for (int i = 1; i <= 35; i++) {
            messages.add(msg("user", "Msg " + i));
        }
        // Add an assistant with tool_call followed by tool output
        var assistantTool = mapper.createObjectNode()
            .put("role", "assistant")
            .put("content", "");
        assistantTool.putArray("tool_calls").addObject().put("id", "call_123");
        messages.add(assistantTool);
        var toolResult = mapper.createObjectNode()
            .put("role", "tool")
            .put("content", "tool result")
            .put("tool_call_id", "call_123");
        messages.add(toolResult);
        messages.add(msg("assistant", "Final response based on tool"));

        var request = createRequest(messages);
        var guarded = manager.guard(request, null);

        var roles = guarded.messages().stream().map(m -> m.path("role").asText()).toList();
        if (roles.contains("tool")) {
            int toolIdx = roles.indexOf("tool");
            assertThat(roles.get(toolIdx - 1)).isEqualTo("assistant");
        }
    }

    private JsonNode msg(String role, String content) {
        return mapper.createObjectNode()
            .put("role", role)
            .put("content", content);
    }

    private CanonicalRequest createRequest(List<JsonNode> messages) {
        var raw = mapper.createObjectNode();
        var arr = raw.putArray("messages");
        messages.forEach(arr::add);
        return new CanonicalRequest(
            "req-1",
            CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "qwen",
            "qwen-max",
            false,
            messages,
            Map.of(),
            Map.of(),
            List.of(),
            Map.of(),
            raw);
    }
}
