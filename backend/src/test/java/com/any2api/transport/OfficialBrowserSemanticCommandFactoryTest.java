package com.any2api.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.any2api.protocol.CanonicalRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OfficialBrowserSemanticCommandFactoryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final OfficialBrowserSemanticCommandFactory factory =
        new OfficialBrowserSemanticCommandFactory(mapper);

    @Test
    void responsesRetainClientSemanticFieldsForThePythonProviderMapper() {
        var raw = mapper.createObjectNode()
            .put("input", "hello")
            .put("previous_response_id", "response-old");
        var request = new CanonicalRequest(
            "request-1", CanonicalRequest.Protocol.RESPONSES, "grok_console", "grok-4.3",
            true, List.of(), Map.of(), Map.of(), List.of(), Map.of(), raw);

        var command = factory.chat(request);

        assertThat(command.path("rawRequest").path("input").asText()).isEqualTo("hello");
        assertThat(command.path("rawRequest").path("previous_response_id").asText())
            .isEqualTo("response-old");
    }
}
