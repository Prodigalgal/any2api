package com.any2api.provider.deepseek;

import static org.assertj.core.api.Assertions.assertThat;

import com.any2api.protocol.CanonicalRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DeepseekRequestMapperTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DeepseekRequestMapper requestMapper = new DeepseekRequestMapper(mapper);

    @Test
    void foldsCanonicalHistoryAndMapsProviderOptions() {
        var system = mapper.createObjectNode().put("role", "system").put("content", "Be concise");
        var user = mapper.createObjectNode().put("role", "user").put("content", "Hello");
        var request = new CanonicalRequest(
            "req-1", CanonicalRequest.Protocol.RESPONSES, "deepseek", "default", true,
            List.of(system, user), Map.of(), Map.of(), List.of(),
            Map.of("thinking_enabled", true, "search_enabled", true),
            mapper.createObjectNode());

        var body = requestMapper.prepare(request, "session-1");

        assertThat(body.path("chat_session_id").asText()).isEqualTo("session-1");
        assertThat(body.path("model_type").asText()).isEqualTo("default");
        assertThat(body.path("thinking_enabled").asBoolean()).isTrue();
        assertThat(body.path("search_enabled").asBoolean()).isTrue();
        assertThat(body.path("prompt").asText())
            .isEqualTo("[system]\nBe concise\n\n[user]\nHello");
    }

    @Test
    void expertDefaultsToThinking() {
        var request = new CanonicalRequest(
            "req-2", CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "deepseek", "expert", true,
            List.of(mapper.createObjectNode().put("role", "user").put("content", "Hello")),
            Map.of(), Map.of(), List.of(), Map.of(), mapper.createObjectNode());

        assertThat(requestMapper.thinking(request)).isTrue();
    }
}
