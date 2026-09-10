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
    void responsesExposeAllowlistedControlsWithoutForwardingRawRequest() {
        var raw = mapper.createObjectNode()
            .put("previous_response_id", "response-old")
            .put("enable_thinking", true);
        var message = mapper.createObjectNode().put("role", "user").put("content", "hello");
        var request = new CanonicalRequest(
            "request-1", CanonicalRequest.Protocol.RESPONSES, "grok_console", "grok-4.3",
            true, List.of(message), Map.of(), Map.of(), List.of(), Map.of(), raw);

        var command = factory.chat(request);

        assertThat(command.has("rawRequest")).isFalse();
        assertThat(command.path("controls").path("previous_response_id").asText())
            .isEqualTo("response-old");
        assertThat(command.path("controls").path("enable_thinking").asBoolean()).isTrue();
        assertThat(command.path("messages").get(0).path("content").asText())
            .isEqualTo("hello");
    }
}
