package com.any2api.provider.grok;

import static org.assertj.core.api.Assertions.assertThat;

import com.any2api.protocol.CanonicalRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class GrokRequestMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GrokRequestMapper requestMapper = new GrokRequestMapper(objectMapper);

    @Test
    void chatMappingOwnsUpstreamModelAndDoesNotMutateRawRequest() {
        var raw = objectMapper.createObjectNode()
            .put("model", "grok/grok-4.5")
            .put("stream", true);
        raw.putArray("messages").add(objectMapper.createObjectNode()
            .put("role", "user")
            .put("content", "hello"));
        var canonical = new CanonicalRequest(
            "request-id",
            CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "grok",
            "grok-4.5",
            true,
            List.of(raw.path("messages").get(0)),
            Map.of(),
            Map.of(),
            List.of(),
            Map.of(),
            raw);

        var prepared = requestMapper.prepare(canonical);

        assertThat(prepared.body().path("model").asText()).isEqualTo("grok-4.5");
        assertThat(prepared.body().path("input").get(0).path("role").asText()).isEqualTo("user");
        assertThat(prepared.body().path("tools").get(0).path("type").asText()).isEqualTo("x_search");
        assertThat(raw.path("model").asText()).isEqualTo("grok/grok-4.5");
    }

}
