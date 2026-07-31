package com.any2api.provider.grok_console;

import static org.assertj.core.api.Assertions.assertThat;

import com.any2api.protocol.CanonicalRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class GrokConsoleRequestMapperTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final GrokConsoleRequestMapper requestMapper = new GrokConsoleRequestMapper(mapper);

    @Test
    void responsesAreForcedStateless() {
        var raw = mapper.createObjectNode().put("model", "grok_console/grok-4.3")
            .put("store", true).put("previous_response_id", "resp_old");
        raw.put("input", "hello");
        var request = new CanonicalRequest("id", CanonicalRequest.Protocol.RESPONSES,
            "grok_console", "grok-4.3", false, List.of(), Map.of(), Map.of(),
            List.of(), Map.of(), raw);

        var payload = requestMapper.prepare(request);

        assertThat(payload.path("model").asText()).isEqualTo("grok-4.3");
        assertThat(payload.path("store").asBoolean()).isFalse();
        assertThat(payload.has("previous_response_id")).isFalse();
        assertThat(payload.path("reasoning").path("effort").asText()).isEqualTo("medium");
    }

}
