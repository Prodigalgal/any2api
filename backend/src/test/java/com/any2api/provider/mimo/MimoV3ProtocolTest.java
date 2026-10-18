package com.any2api.provider.mimo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.any2api.account.LeasedProviderAccount;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalEventStream;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.protocol.OpenAiResponseWriter;
import com.any2api.provider.ProviderExecutionContext;
import com.any2api.provider.ProviderTransportMode;
import com.any2api.proxy.ProxyPoolService;
import com.any2api.transport.OfficialBrowserSemanticCommandFactory;
import com.any2api.transport.OfficialBrowserTransportClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class MimoV3ProtocolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void keepsControlFramesOutOfAnswersAndPreservesReasoningAndUsage() {
        var decoder = decoder();
        var events = new ArrayList<CanonicalEvent>();
        for (var control : List.of("dialogId", "tip_ratio", "tip_truncate", "web_search", "doc", "unknown")) {
            events.addAll(decoder.decode(control, "{\"content\":\"12345678\"}"));
        }
        events.addAll(decoder.decode("message", "{\"reasoningContent\":\"plan\"}"));
        events.addAll(decoder.decode("message", "{\"content\":\"{\\\"ok\\\":true}\"}"));
        events.addAll(decoder.decode("usage", "{\"promptTokens\":3,\"completionTokens\":5}"));
        events.addAll(decoder.decode("finish", "{\"content\":\"[DONE]\"}"));

        assertThat(answer(events)).isEqualTo("{\"ok\":true}");
        assertThat(events).anyMatch(event -> event instanceof CanonicalEvent.ReasoningDelta reasoning
            && reasoning.delta().equals("plan"));
        assertThat(events).anyMatch(event -> event instanceof CanonicalEvent.Usage usage
            && usage.inputTokens() == 3 && usage.outputTokens() == 5);
        assertThat(events.getLast()).isInstanceOf(CanonicalEvent.Completed.class);
        assertThat(decoder.finish()).isEmpty();
        assertThat(decoder.decode("message", "{\"content\":\"late data\"}")).isEmpty();
    }

    @Test
    void rejectsControlAndReasoningOnlyResponsesInsteadOfCountingThemAsSuccess() {
        var decoder = decoder();
        var events = new ArrayList<>(decoder.decode("dialogId", "{\"content\":\"12345678\"}"));
        events.addAll(decoder.decode("message", "{\"reasoningContent\":\"plan\"}"));
        events.addAll(decoder.decode("finish", "{\"content\":\"[DONE]\"}"));

        assertThat(answer(events)).isEmpty();
        assertThat(events).anyMatch(event -> event instanceof CanonicalEvent.Failed failed
            && failed.errorType().equals("empty_model_response"));
        assertThat(events).noneMatch(CanonicalEvent.Completed.class::isInstance);
    }

    @Test
    void mapsSseErrorsAndMessageTypeErrorsToFailure() {
        for (var frame : List.of(
            Map.of("event", "error", "data", "{\"content\":\"upstream rejected\"}"),
            Map.of("event", "message", "data", "{\"messageType\":\"error\",\"content\":\"upstream rejected\"}"),
            Map.of("event", "message", "data", "{\"error\":{\"message\":\"upstream rejected\"}}"))) {
            var decoder = decoder();
            var events = decoder.decode(frame.get("event"), frame.get("data"));
            assertThat(answer(events)).isEmpty();
            assertThat(events).anyMatch(event -> event instanceof CanonicalEvent.Failed failed
                && failed.errorType().equals("upstream_error")
                && failed.message().equals("upstream rejected"));
            assertThat(decoder.finish()).isEmpty();
        }
    }

    @Test
    void rejectsSensitiveInputWithoutLeakingItIntoTheAnswer() {
        for (var kind : List.of("sensitive_query", "sensitive_title")) {
            var events = decoder().decode(kind, "{\"content\":\"sensitive details\"}");
            assertThat(answer(events)).isEmpty();
            assertThat(events).anyMatch(event -> event instanceof CanonicalEvent.Failed failed
                && failed.errorType().equals("content_filter")
                && failed.detail().get("retryable").equals(false));
        }
    }

    @Test
    void recognizesBothOfficialMaintenanceMessages() {
        for (var message : List.of("MiMo Studio updated. Refresh your page for better performance!",
            "平台升级，请刷新页面后重试")) {
            var events = decoder().decode("message", mapper.writeValueAsString(Map.of("content", message)));
            assertThat(answer(events)).isEmpty();
            assertThat(events).anyMatch(event -> event instanceof CanonicalEvent.Failed failed
                && failed.errorType().equals("upstream_maintenance"));
        }
    }

    @Test
    void preservesNumericAnswersAndLegacyTextFrames() {
        var decoder = decoder();
        var events = new ArrayList<>(decoder.decode("{\"type\":\"text\",\"content\":\"12345678\"}"));
        events.addAll(decoder.decode("[DONE]"));
        assertThat(answer(events)).isEqualTo("12345678");
        assertThat(events.getLast()).isInstanceOf(CanonicalEvent.Completed.class);
    }

    @Test
    void preservesMessageContentWhenAnAdditionalSubtypeIsPresent() {
        var decoder = decoder();
        var events = new ArrayList<>(decoder.decode("message",
            "{\"messageType\":\"answer\",\"content\":\"answer\",\"error\":\"\"}"));
        events.addAll(decoder.decode("finish", "{}"));
        assertThat(answer(events)).isEqualTo("answer");
        assertThat(events.getLast()).isInstanceOf(CanonicalEvent.Completed.class);
    }

    @ParameterizedTest
    @EnumSource(value = CanonicalRequest.Protocol.class, names = {"CHAT_COMPLETIONS", "RESPONSES"})
    void rendersOnlyTheFinalAnswerForCollectedAndStreamingClients(CanonicalRequest.Protocol protocol) {
        for (var stream : List.of(false, true)) {
            var request = request(protocol, stream);
            var decoder = decoder();
            var events = new ArrayList<>(decoder.decode("dialogId", "{\"content\":\"12345678\"}"));
            events.addAll(decoder.decode("message", "{\"reasoningContent\":\"plan\"}"));
            events.addAll(decoder.decode("message", "{\"content\":\"answer\"}"));
            events.addAll(decoder.decode("finish", "{\"content\":\"[DONE]\"}"));
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/v1/responses").build());
            new OpenAiResponseWriter(mapper).write(request,
                CanonicalEventStream.enforce(request, Flux.fromIterable(events)), exchange).block();
            var body = exchange.getResponse().getBodyAsString().block();

            assertThat(body).contains("answer").doesNotContain("12345678", "\"content\":\"[DONE]\"");
            if (!stream && protocol == CanonicalRequest.Protocol.CHAT_COMPLETIONS) {
                assertThat(mapper.readTree(body).path("choices").get(0).path("message").path("content").asText())
                    .isEqualTo("answer");
            }
            if (!stream && protocol == CanonicalRequest.Protocol.RESPONSES) {
                assertThat(mapper.readTree(body).path("status").asText()).isEqualTo("completed");
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = ProviderTransportMode.class, names = {"API", "RUNTIME"})
    void finishesAndReleasesUpstreamWithoutWaitingForConnectionClose(ProviderTransportMode mode) {
        var transport = mock(OfficialBrowserTransportClient.class);
        var cancelled = new AtomicBoolean();
        var upstream = Flux.concat(Flux.just(
            mapper.readTree("{\"type\":\"status\",\"status\":200}"),
            frame("dialogId", Map.of("content", "12345678")),
            frame("message", Map.of("content", "answer")),
            frame("finish", Map.of("content", "[DONE]"))), Flux.<JsonNode>never())
            .doOnCancel(() -> cancelled.set(true));
        if (mode == ProviderTransportMode.API) {
            when(transport.stream(anyString(), anyString(), any(), any(), any(), anyString(), any(), any()))
                .thenReturn(upstream);
        } else {
            when(transport.stream(anyString(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(upstream);
        }
        var provider = new MimoProvider(transport, new OfficialBrowserSemanticCommandFactory(mapper),
            mock(ProxyPoolService.class), new MimoProperties(), new MimoRequestMapper(mapper), mapper);
        var credential = mapper.createObjectNode().put("service_token", "test-token")
            .put("user_id", "test-user").put("xiaomichatbot_ph", "test-phase");
        var accountId = UUID.randomUUID();
        var account = new LeasedProviderAccount(accountId, "mimo", "test", "", 1, null,
            credential, Map.of(), null);
        var request = request(CanonicalRequest.Protocol.CHAT_COMPLETIONS, true);
        var context = new ProviderExecutionContext(request.requestId(), accountId, "1", "owner", 1,
            Instant.now().plusSeconds(30), mode);
        var events = provider.generate(request, context, account).collectList().block(Duration.ofSeconds(2));

        assertThat(answer(events)).isEqualTo("answer");
        assertThat(events.getLast()).isInstanceOf(CanonicalEvent.Completed.class);
        assertThat(cancelled).isTrue();
    }

    private JsonNode frame(String event, Map<String, String> payload) {
        return mapper.createObjectNode().put("type", "data").put("event", event)
            .put("data", mapper.writeValueAsString(payload));
    }

    private CanonicalRequest request(CanonicalRequest.Protocol protocol, boolean stream) {
        return new CanonicalRequest("mimo-v3", protocol, "mimo", "mimo-v2.6-pro", stream,
            List.of(), Map.of(), Map.of(), List.of(), Map.of(), mapper.createObjectNode());
    }

    private MimoEventDecoder decoder() {
        return new MimoEventDecoder("mimo-v3", List.of(), false, true);
    }

    private String answer(List<CanonicalEvent> events) {
        return events.stream().filter(CanonicalEvent.OutputTextDelta.class::isInstance)
            .map(CanonicalEvent.OutputTextDelta.class::cast).map(CanonicalEvent.OutputTextDelta::delta)
            .collect(java.util.stream.Collectors.joining());
    }
}
