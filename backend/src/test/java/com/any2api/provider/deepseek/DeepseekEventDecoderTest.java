package com.any2api.provider.deepseek;

import static org.assertj.core.api.Assertions.assertThat;

import com.any2api.protocol.CanonicalEvent;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DeepseekEventDecoderTest {
    @Test
    void decodesReasoningResponsePatchesAndUsage() {
        var decoder = new DeepseekEventDecoder("req-1", new ObjectMapper());
        var events = new ArrayList<CanonicalEvent>();
        events.addAll(decoder.decode("""
            {"v":{"response":{"accumulated_token_usage":0,"fragments":[
              {"type":"THINK","content":"plan"}
            ]}}}
            """));
        events.addAll(decoder.decode("""
            {"p":"response/fragments","o":"APPEND","v":[
              {"type":"RESPONSE","content":"answer"}
            ]}
            """));
        events.addAll(decoder.decode("{\"v\":\"!\"}"));
        events.addAll(decoder.decode("""
            {"p":"response/accumulated_token_usage","o":"SET","v":42}
            """));
        events.addAll(decoder.finish());

        assertThat(events).anyMatch(CanonicalEvent.ResponseStarted.class::isInstance);
        assertThat(events.stream().filter(CanonicalEvent.ReasoningDelta.class::isInstance)
            .map(CanonicalEvent.ReasoningDelta.class::cast)
            .map(CanonicalEvent.ReasoningDelta::delta)).containsExactly("plan");
        assertThat(events.stream().filter(CanonicalEvent.OutputTextDelta.class::isInstance)
            .map(CanonicalEvent.OutputTextDelta.class::cast)
            .map(CanonicalEvent.OutputTextDelta::delta)).containsExactly("answer", "!");
        assertThat(events.stream().filter(CanonicalEvent.Usage.class::isInstance)
            .map(CanonicalEvent.Usage.class::cast)
            .map(CanonicalEvent.Usage::outputTokens)).containsExactly(42L);
        assertThat(events).anyMatch(CanonicalEvent.Completed.class::isInstance);
    }
}
