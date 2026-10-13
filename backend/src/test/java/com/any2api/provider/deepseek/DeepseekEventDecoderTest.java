package com.any2api.provider.deepseek;

import static org.assertj.core.api.Assertions.assertThat;

import com.any2api.protocol.CanonicalEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DeepseekEventDecoderTest {
    @Test
    void upstreamMuteIsOneExplicitFailureAndDoesNotReflectResponseBody() {
        var decoder = new DeepseekEventDecoder("muted-request", new ObjectMapper());
        var events = decoder.decode("""
            {"code":0,"data":{"biz_code":5,"biz_msg":"private upstream diagnostic",
             "biz_data":{"is_muted":1,"mute_until":1792111230.63}}}
            """);

        assertThat(events).filteredOn(CanonicalEvent.Failed.class::isInstance).hasSize(1);
        var failed = (CanonicalEvent.Failed) events.getLast();
        assertThat(failed.errorType()).isEqualTo("account_blocked");
        assertThat(failed.message()).doesNotContain("private upstream diagnostic");
        assertThat(failed.detail()).containsEntry("upstream_biz_code", 5)
            .containsEntry("upstream_muted_until_epoch_seconds", 1792111230L)
            .containsEntry("retryable", false);
        assertThat(events).noneMatch(CanonicalEvent.OutputTextDelta.class::isInstance);
        assertThat(decoder.finish()).isEmpty();
        assertThat(decoder.decode("{\"v\":\"late output\"}")).isEmpty();
    }

    @Test
    void knownAuthenticationEnvelopeIsNotAnEmptyModelResponse() {
        var decoder = new DeepseekEventDecoder("authentication-request", new ObjectMapper());
        var failed = (CanonicalEvent.Failed) decoder.decode("""
            {"code":40003,"msg":"secret upstream diagnostic"}
            """).getLast();

        assertThat(failed.errorType()).isEqualTo("credential_rejected");
        assertThat(failed.message()).doesNotContain("secret upstream diagnostic");
        assertThat(failed.detail()).containsEntry("upstream_code", 40003)
            .containsEntry("retryable", false);
        assertThat(decoder.finish()).isEmpty();
    }

    @Test
    void unrecognizedBusinessErrorsDoNotGuessThatCredentialsAreExpired() {
        for (var input : List.of(
            "{\"code\":0,\"data\":{\"biz_code\":5,\"biz_data\":{\"is_muted\":0}}}",
            "{\"code\":0,\"data\":{\"biz_code\":3,\"biz_msg\":\"invalid token private text\"}}",
            "{\"code\":18,\"msg\":\"private text\"}"
        )) {
            var decoder = new DeepseekEventDecoder("business-request", new ObjectMapper());
            var failed = (CanonicalEvent.Failed) decoder.decode(input).getLast();
            assertThat(failed.errorType()).isEqualTo("provider_upstream_error");
            assertThat(failed.message()).doesNotContain("private text");
            assertThat(failed.detail()).containsEntry("retryable", false);
            assertThat(decoder.finish()).isEmpty();
        }
    }

    @Test
    void decodesReasoningResponsePatchesWithoutTreatingAccumulatedUsageAsOutput() {
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
        assertThat(events).noneMatch(CanonicalEvent.Usage.class::isInstance);
        assertThat(events).anyMatch(CanonicalEvent.Completed.class::isInstance);
    }
}
