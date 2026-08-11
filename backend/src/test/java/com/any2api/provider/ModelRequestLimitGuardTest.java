package com.any2api.provider;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.any2api.protocol.CanonicalRequest;
import com.any2api.protocol.OpenAiRequestException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ModelRequestLimitGuardTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ModelRequestLimitGuard guard = new ModelRequestLimitGuard();

    @Test
    void rejectsRequestedOutputAboveTheEffectiveModelLimit() {
        var raw = mapper.createObjectNode()
            .put("model", "alpha")
            .put("max_tokens", 513);
        raw.putArray("messages")
            .addObject().put("role", "user").put("content", "hello");

        assertThatThrownBy(() -> guard.requireWithinLimits(
            request(raw), mapper.createObjectNode().put("max_output_tokens", 512)))
            .isInstanceOf(OpenAiRequestException.class)
            .hasMessageContaining("exceeds the configured model output limit 512");
    }

    @Test
    void rejectsCombinedBudgetAboveTheContextLimit() {
        var raw = mapper.createObjectNode()
            .put("model", "alpha")
            .put("max_output_tokens", 90)
            .put("input", "x".repeat(120));

        assertThatThrownBy(() -> guard.requireWithinLimits(
            request(raw), mapper.createObjectNode().put("max_context_tokens", 100)))
            .isInstanceOf(OpenAiRequestException.class)
            .hasMessageContaining("exceeds the configured model context limit 100");
    }

    @Test
    void acceptsRequestsWithinAllConfiguredLimits() {
        var raw = mapper.createObjectNode()
            .put("model", "alpha")
            .put("max_tokens", 32)
            .put("input", "short");
        var capabilities = mapper.createObjectNode()
            .put("max_context_tokens", 1024)
            .put("max_input_tokens", 512)
            .put("max_output_tokens", 64);

        guard.requireWithinLimits(request(raw), capabilities);
    }

    private CanonicalRequest request(tools.jackson.databind.JsonNode raw) {
        return new CanonicalRequest(
            "req", CanonicalRequest.Protocol.RESPONSES, "alpha", "alpha-model", false,
            List.of(), Map.of(), Map.of(), List.of(), Map.of(), raw);
    }
}
