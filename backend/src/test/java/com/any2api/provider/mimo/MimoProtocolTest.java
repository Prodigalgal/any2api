package com.any2api.provider.mimo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalEventStream;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.protocol.OpenAiRequestException;
import com.any2api.proxy.ProxyPoolService;
import com.any2api.transport.OfficialBrowserTransportClient;
import com.any2api.transport.OfficialBrowserSemanticCommandFactory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

class MimoProtocolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void turnsTheExactWebLengthRejectionIntoOneRequestFailureAcrossChunkBoundaries() {
        var rejection = "Sorry, the text you sent is too long! I suggest you simplify the content "
            + "appropriately or send it in parts. Thank you for your understanding.";
        for (var chunkSize : List.of(1, 7, 37, rejection.length())) {
            for (var required : List.of(false, true)) {
                var decoder = new MimoEventDecoder("length", List.of(), required, true, "messages");
                var events = new java.util.ArrayList<CanonicalEvent>();
                for (var offset = 0; offset < rejection.length(); offset += chunkSize) {
                    var chunk = rejection.substring(offset, Math.min(rejection.length(), offset + chunkSize));
                    events.addAll(decoder.decode(mapper.writeValueAsString(
                        Map.of("type", "text", "content", chunk))));
                }
                events.addAll(decoder.finish());
                assertThat(events).noneMatch(event -> event instanceof CanonicalEvent.OutputTextDelta
                    || event instanceof CanonicalEvent.Completed);
                var failures = events.stream().filter(CanonicalEvent.Failed.class::isInstance)
                    .map(CanonicalEvent.Failed.class::cast).toList();
                assertThat(failures).hasSize(1);
                assertThat(failures.getFirst().errorType()).isEqualTo("context_length_exceeded");
                assertThat(failures.getFirst().detail()).containsEntry("param", "messages")
                    .containsEntry("retryable", false);
                assertThat(decoder.finish()).isEmpty();
                assertThat(decoder.decode("[DONE]")).isEmpty();
            }
        }
    }

    @Test
    void doesNotMisclassifyAnAnswerQuotingOrExtendingTheRejection() {
        for (var answer : List.of("Sorry, the text you sent is too long! This is an example sentence.",
            "The service can return: Sorry, the text you sent is too long!", "Sorry, today I cannot help.")) {
            var decoder = new MimoEventDecoder("answer", List.of(), false, true);
            var events = new java.util.ArrayList<>(decoder.decode(mapper.writeValueAsString(
                Map.of("type", "text", "content", answer))));
            events.addAll(decoder.finish());
            assertThat(events).noneMatch(CanonicalEvent.Failed.class::isInstance);
            assertThat(events.stream().filter(CanonicalEvent.OutputTextDelta.class::isInstance)
                .map(CanonicalEvent.OutputTextDelta.class::cast).map(CanonicalEvent.OutputTextDelta::delta)
                .collect(java.util.stream.Collectors.joining())).isEqualTo(answer);
        }
    }

    @Test
    void mapsResponsesInputAndDecodesReasoningTextAndUsage() {
        var raw = mapper.createObjectNode().put("model", "mimo/mimo-v2.5-pro");
        raw.putArray("input").add(mapper.createObjectNode().put("role", "user")
            .put("content", "hello"));
        var request = new CanonicalRequest("r1", CanonicalRequest.Protocol.RESPONSES,
            "mimo", "mimo-v2.5-pro", true,
            List.of(raw.path("input").get(0)), Map.of(), Map.of("effort", "high"),
            List.of(), Map.of(), raw);

        var prepared = new MimoRequestMapper(mapper).prepare(request);
        var decoder = new MimoEventDecoder("r1", List.of(), false, true);
        var events = new java.util.ArrayList<CanonicalEvent>();
        events.addAll(decoder.decode("{\"type\":\"text\",\"content\":\"<think>why</think>answer\"}"));
        events.addAll(decoder.decode("{\"promptTokens\":3,\"completionTokens\":2,\"totalTokens\":5}"));
        events.addAll(decoder.finish());

        var command = new OfficialBrowserSemanticCommandFactory(mapper).chat(request);
        assertThat(command.path("messages").get(0).path("content").asText())
            .isEqualTo("hello");
        assertThat(command.path("reasoning").path("effort").asText()).isEqualTo("high");
        assertThat(events).anyMatch(CanonicalEvent.ReasoningDelta.class::isInstance)
            .anyMatch(CanonicalEvent.OutputTextDelta.class::isInstance)
            .anyMatch(CanonicalEvent.Usage.class::isInstance)
            .anyMatch(CanonicalEvent.Completed.class::isInstance);
    }

    @Test
    void preservesInlineImageForTheProviderUploadPipeline() {
        var raw = mapper.createObjectNode().put("model", "mimo/mimo-v2.5");
        var content = raw.putArray("messages").addObject()
            .put("role", "user").putArray("content");
        content.addObject().put("type", "text").put("text", "inspect");
        content.addObject().put("type", "image_url")
            .putObject("image_url").put("url", "data:image/png;base64,iVBORw0KGgo=");
        var request = new CanonicalRequest("r2", CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "mimo", "mimo-v2.5", false,
            List.of(raw.path("messages").get(0)), Map.of(), Map.of(), List.of(), Map.of(), raw);

        var prepared = new MimoRequestMapper(mapper).prepare(request);

        assertThat(prepared.media()).singleElement().satisfies(media -> {
            assertThat(media.kind()).isEqualTo("image");
            assertThat(media.dataUrl()).startsWith("data:image/png;base64,");
        });
    }

    @Test
    void decodesRequiredMimoMlToolCallsAndRejectsMissingCalls() {
        var raw = mapper.createObjectNode().put("tool_choice", "required");
        var function = raw.putArray("tools").addObject().put("type", "function")
            .putObject("function");
        function.put("name", "get_weather").putObject("parameters").put("type", "object");
        var request = new CanonicalRequest("tools", CanonicalRequest.Protocol.RESPONSES,
            "mimo", "mimo-v2.5-pro", true, List.of(), Map.of(), Map.of(),
            List.of(raw.path("tools").get(0)), Map.of(), raw);
        var prepared = new MimoRequestMapper(mapper).prepare(request);
        var decoder = new MimoEventDecoder("tools", prepared.tools(), prepared.toolRequired(),
            prepared.parallelToolCalls());

        assertThat(prepared.toolRequired()).isTrue();

        decoder.decode("{\"type\":\"text\",\"content\":\"<|MiMoML|tool_calls>"
            + "<|MiMoML|invoke name='get_weather'><|MiMoML|parameter name='city'>"
            + "\\\"Xiamen\\\"</|MiMoML|parameter></|MiMoML|invoke>"
            + "</|MiMoML|tool_calls>\"}");
        var events = decoder.finish();

        assertThat(events).anyMatch(CanonicalEvent.ToolCallStarted.class::isInstance)
            .anyMatch(event -> event instanceof CanonicalEvent.Completed completed
                && completed.finishReason().equals("tool_calls"));

        var missing = new MimoEventDecoder("missing", prepared.tools(), true, true);
        missing.decode("{\"type\":\"text\",\"content\":\"plain answer\"}");
        assertThat(missing.finish()).anyMatch(event ->
            event instanceof CanonicalEvent.Failed failed
                && failed.errorType().equals("tool_call_generation_failed"));
    }

    @Test
    void decodesBareOpenAiJsonToolCallsFromLiveModelVariants() {
        var tool = new MimoTool("get_weather", "", mapper.createObjectNode());
        var decoder = new MimoEventDecoder("json-tools", List.of(tool), true, true);

        decoder.decode("{\"type\":\"text\",\"content\":\"{\\\"tool_calls\\\":[{"
            + "\\\"function\\\":{\\\"name\\\":\\\"get_weather\\\","
            + "\\\"arguments\\\":\\\"{\\\\\\\"city\\\\\\\":\\\\\\\"Tokyo\\\\\\\"}\\\"}}]}\"}");
        var events = decoder.finish();

        assertThat(events).anyMatch(event -> event instanceof CanonicalEvent.ToolCallCompleted call
            && call.arguments().equals("{\"city\":\"Tokyo\"}"))
            .anyMatch(event -> event instanceof CanonicalEvent.Completed completed
                && completed.finishReason().equals("tool_calls"));
    }

    @Test
    void preservesSchemaStringTypesInUntypedWebParameters() {
        for (var raw : List.of("11733", "true", "null", "00123", "\" 交付资料 \"")) {
            var expected = raw.startsWith("\"") ? " 交付资料 " : raw;
            for (var source : parameterSources("batch_number", raw)) {
                var events = strictToolEvents(source, "{\"type\":\"string\"}");
                assertThat(events).noneMatch(CanonicalEvent.Failed.class::isInstance);
                var arguments = completedArguments(events);
                assertThat(arguments.path("batch_number").isTextual()).isTrue();
                assertThat(arguments.path("batch_number").asText()).isEqualTo(expected);
            }
        }
    }

    @Test
    void preservesDeclaredJsonTypesAndNullableStringsInWebParameters() {
        var cases = List.of(
            List.of("{\"type\":\"integer\"}", "11733"),
            List.of("{\"type\":\"boolean\"}", "true"),
            List.of("{\"type\":[\"string\",\"null\"]}", "null"),
            List.of("{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"null\"}]}", "null"),
            List.of("{\"type\":[\"string\",\"integer\"]}", "11733"));
        for (var entry : cases) {
            for (var source : parameterSources("batch_number", entry.get(1))) {
                var events = strictToolEvents(source, entry.get(0));
                assertThat(events).noneMatch(CanonicalEvent.Failed.class::isInstance);
                assertThat(completedArguments(events).path("batch_number"))
                    .isEqualTo(mapper.readTree(entry.get(1)));
            }
        }
        for (var schema : List.of("{\"type\":[\"string\",\"null\"]}",
            "{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"null\"}]}")) {
            var events = strictToolEvents(parameterSources("batch_number", "11733").getFirst(), schema);
            assertThat(completedArguments(events).path("batch_number").asText()).isEqualTo("11733");
            assertThat(completedArguments(events).path("batch_number").isTextual()).isTrue();
        }
    }

    @Test
    void resolvesLocalReferencedStringParameterTypes() {
        var events = strictToolEvents(parameterSources("batch_number", "11733").getFirst(),
            "{\"$ref\":\"#/$defs/batch\"}");
        assertThat(events).noneMatch(CanonicalEvent.Failed.class::isInstance);
        assertThat(completedArguments(events).path("batch_number").isTextual()).isTrue();
    }

    @Test
    void rejectsExplicitJsonTypeMismatchesWithoutLeakingToolArguments() {
        for (var source : List.of(
            "{\"name\":\"review_document\",\"arguments\":{\"batch_number\":11733}}",
            "<function_call>{\"name\":\"review_document\",\"arguments\":{\"batch_number\":11733}}</function_call>",
            "TOOL_CALL: review_document({\"batch_number\":11733})")) {
            var events = strictToolEvents(source, "{\"type\":\"string\"}");
            assertThat(events).anyMatch(event -> event instanceof CanonicalEvent.Failed failed
                && failed.errorType().equals("tool_call_generation_failed"));
            assertThat(events).noneMatch(event -> event instanceof CanonicalEvent.ToolCallStarted
                || event instanceof CanonicalEvent.ToolArgumentsDelta
                || event instanceof CanonicalEvent.ToolCallCompleted
                || event instanceof CanonicalEvent.Completed);
        }
    }

    private List<String> parameterSources(String name, String value) {
        return List.of(
            "<|MiMoML|tool_calls><|MiMoML|invoke name='review_document'>"
                + "<|MiMoML|parameter name='" + name + "'>" + value
                + "</|MiMoML|parameter></|MiMoML|invoke></|MiMoML|tool_calls>",
            "<tool_call><function=review_document><parameter=" + name + ">"
                + value + "</parameter></function></tool_call>",
            "TOOL_CALL: review_document(" + name + "=" + value + ")");
    }

    private List<CanonicalEvent> strictToolEvents(String source, String parameterSchema) {
        var raw = mapper.createObjectNode().put("tool_choice", "required");
        var function = raw.putArray("tools").addObject().put("type", "function").putObject("function");
        function.put("name", "review_document").put("strict", true);
        var parameters = function.putObject("parameters").put("type", "object")
            .put("additionalProperties", false);
        parameters.putObject("properties").set("batch_number", mapper.readTree(parameterSchema));
        parameters.putArray("required").add("batch_number");
        parameters.putObject("$defs").putObject("batch").put("type", "string");
        var request = new CanonicalRequest("typed-web", CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "mimo", "mimo-v2.6-pro", true, List.of(), Map.of(), Map.of(),
            List.of(raw.path("tools").get(0)), Map.of(), raw);
        var prepared = new MimoRequestMapper(mapper).prepare(request);
        var decoder = new MimoEventDecoder(request.requestId(), prepared.tools(), true, false);
        var events = new java.util.ArrayList<>(decoder.decode(mapper.writeValueAsString(
            Map.of("type", "text", "content", source))));
        events.addAll(decoder.finish());
        return CanonicalEventStream.enforce(request, Flux.fromIterable(events)).collectList().block();
    }

    private tools.jackson.databind.JsonNode completedArguments(List<CanonicalEvent> events) {
        var calls = events.stream().filter(CanonicalEvent.ToolCallCompleted.class::isInstance)
            .map(CanonicalEvent.ToolCallCompleted.class::cast).toList();
        assertThat(calls).hasSize(1);
        return mapper.readTree(calls.getFirst().arguments());
    }

    @Test
    void rejectsAnEmptyMimoStream() {
        var events = new MimoEventDecoder("empty", List.of(), false, true).finish();

        assertThat(events).anyMatch(event -> event instanceof CanonicalEvent.Failed failed
            && failed.errorType().equals("empty_model_response"));
        assertThat(events).noneMatch(CanonicalEvent.Completed.class::isInstance);
    }

    @Test
    void acceptsOnlyOutputLimitsThatCannotConstrainTheOfficialWebModel() {
        var provider = new MimoProvider(
            mock(OfficialBrowserTransportClient.class),
            new OfficialBrowserSemanticCommandFactory(mapper),
            mock(ProxyPoolService.class),
            new MimoProperties(), mock(MimoRequestMapper.class), mapper);
        var raw = mapper.createObjectNode().put("model", "mimo/mimo-v2.5-pro");
        var nonBinding = new CanonicalRequest(
            "mimo-code", CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "mimo", "mimo-v2.5-pro", true, List.of(), Map.of("max_tokens", 128_000),
            Map.of(), List.of(), Map.of(), raw);
        var binding = new CanonicalRequest(
            "binding", CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "mimo", "mimo-v2.5-pro", true, List.of(), Map.of("max_tokens", 1_024),
            Map.of(), List.of(), Map.of(), raw);

        assertThat(provider.protocolContract().chatParameters()).contains("max_tokens");
        assertThatCode(() -> provider.validate(nonBinding)).doesNotThrowAnyException();
        assertThatThrownBy(() -> provider.validate(binding))
            .isInstanceOf(OpenAiRequestException.class)
            .hasMessageContaining("below its 65536 token output ceiling");
    }

    @Test
    void doesNotQuarantineAnAccountForPresignedObjectStorageSignatureFailure() {
        var provider = new MimoProvider(
            mock(OfficialBrowserTransportClient.class),
            new OfficialBrowserSemanticCommandFactory(mapper),
            mock(ProxyPoolService.class),
            new MimoProperties(), mock(MimoRequestMapper.class), mapper);

        var failure = provider.classify(new MimoUpstreamException(
            403,
            "MiMo upstream returned HTTP 403: MiMo object upload returned HTTP 403: "
                + "Galaxy FDS Error: Signature Does Not Match"));

        assertThat(failure.type()).isEqualTo("provider_upstream_error");
        assertThat(failure.retryable()).isTrue();
        assertThat(failure.detail()).containsEntry("stage", "object_upload");

        var authenticationFailure = provider.classify(
            new MimoUpstreamException(403, "MiMo upstream returned HTTP 403: forbidden"));

        assertThat(authenticationFailure.type()).isEqualTo("credential_rejected");
        assertThat(authenticationFailure.retryable()).isFalse();
    }
}
