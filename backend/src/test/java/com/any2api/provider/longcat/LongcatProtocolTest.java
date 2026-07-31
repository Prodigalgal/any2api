package com.any2api.provider.longcat;

import static org.assertj.core.api.Assertions.assertThat;

import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class LongcatProtocolTest {
    @Test
    void seedsProviderCookiesIntoTheOpaqueTransportJar() {
        assertThat(new LongcatCredential(
            "passport_token_key=session; _lxsdk_cuid=device; ignored").cookies())
            .containsEntry("passport_token_key", "session")
            .containsEntry("_lxsdk_cuid", "device")
            .doesNotContainKey("ignored");
    }

    @Test
    void mapsProviderDialectAndDecodesReasoningAndFinish() {
        var mapper = new ObjectMapper();
        var raw = mapper.createObjectNode().put("model", "longcat/longcat-flash")
            .put("reason_enabled", true).put("search_enabled", true);
        var message = mapper.createObjectNode().put("role", "user").put("content", "hello");
        var request = new CanonicalRequest("r2", CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "longcat", "longcat-flash", true, List.of(message), Map.of(), Map.of(),
            List.of(), Map.of(), raw);
        var prepared = new LongcatRequestMapper(mapper).prepare(request);
        var decoder = new LongcatEventDecoder("r2", true);
        var events = new java.util.ArrayList<CanonicalEvent>();
        events.addAll(decoder.decode("{\"event\":{\"type\":\"think\",\"content\":\"why\"}}"));
        events.addAll(decoder.decode("{\"event\":{\"type\":\"content\",\"content\":\"answer\"}}"));
        events.addAll(decoder.decode("{\"event\":{\"type\":\"finish\",\"usage\":{\"inputTokens\":2,\"outputTokens\":1}},\"lastOne\":true}"));

        assertThat(prepared.reasonEnabled()).isTrue();
        assertThat(prepared.searchEnabled()).isTrue();
        assertThat(events).anyMatch(CanonicalEvent.ReasoningDelta.class::isInstance)
            .anyMatch(CanonicalEvent.OutputTextDelta.class::isInstance)
            .anyMatch(CanonicalEvent.Completed.class::isInstance);
    }
}
