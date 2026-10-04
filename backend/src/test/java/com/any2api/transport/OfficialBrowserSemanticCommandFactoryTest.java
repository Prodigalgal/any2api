package com.any2api.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.any2api.protocol.CanonicalRequest;
import com.any2api.protocol.CanonicalRequestParser;
import com.any2api.routing.ResolvedRoute;
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
            "request-1", CanonicalRequest.Protocol.RESPONSES, "grok_web", "grok-chat-fast",
            true, List.of(message), Map.of(), Map.of(), List.of(), Map.of(), raw);

        var command = factory.chat(request);

        assertThat(command.has("rawRequest")).isFalse();
        assertThat(command.path("controls").path("previous_response_id").asText())
            .isEqualTo("response-old");
        assertThat(command.path("controls").path("enable_thinking").asBoolean()).isTrue();
        assertThat(command.path("messages").get(0).path("content").asText())
            .isEqualTo("hello");
    }

    @Test
    void omitsNullControlsBeforeTheProviderBoundaryAndPreservesExplicitFalse() {
        var raw = mapper.createObjectNode().put("model", "arena/claude-sonnet-5")
            .putNull("reasoning_effort").putNull("previous_response_id")
            .putNull("metadata").putNull("tool_choice").putNull("max_tokens")
            .put("store", false).put("web_search", false);
        raw.putArray("messages").addObject().put("role", "user").put("content", "hello");
        var request = new CanonicalRequestParser(mapper).parse(
            CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            new ResolvedRoute("arena", "claude-sonnet-5"), raw);

        var command = factory.chat(request);

        assertThat(command.path("controls").propertyNames()).containsExactlyInAnyOrder("store", "web_search");
        assertThat(command.path("controls").path("store").asBoolean()).isFalse();
        assertThat(command.path("controls").path("web_search").asBoolean()).isFalse();
        assertThat(command.path("generation").isEmpty()).isTrue();
        assertThat(request.rawRequest().has("reasoning_effort")).isTrue();
    }
}
