package com.any2api.provider.qwen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.provider.RandomModelRole;
import com.any2api.proxy.ProxyPoolService;
import com.any2api.transport.BrowserTransportClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

class QwenProtocolTest {
    @Test
    void excludesUnacceptedMultimodalModelFromRandomRouting() {
        var provider = new QwenProvider(
            mock(BrowserTransportClient.class), mock(ProxyPoolService.class),
            new QwenProperties(), mock(QwenRequestMapper.class),
            mock(QwenTransportRequests.class), mock(QwenMediaUploader.class),
            new ObjectMapper());

        assertThat(provider.manifest().randomModelPreferences())
            .containsKey(RandomModelRole.TOP_TEXT)
            .doesNotContainKey(RandomModelRole.TOP_MULTIMODAL);
    }

    @Test
    void transportSendsTheExactBodyUsedForDynamicRiskHeaders() {
        var risk = mock(QwenRiskHeaderClient.class);
        var properties = new QwenProperties();
        var body = "{\"ordered\":true,\"value\":42}";
        when(risk.generate(properties.getBaseUrl() + "/api/v2/test", "POST", body))
            .thenReturn(Mono.just(Map.of("bx-v", "dynamic", "version", "current")));

        StepVerifier.create(new QwenTransportRequests(properties, risk)
                .create("POST", "/api/v2/test", body, 90))
            .assertNext(request -> {
                assertThat(new String(request.rawBody(), java.nio.charset.StandardCharsets.UTF_8))
                    .isEqualTo(body);
                assertThat(request.headers())
                    .containsEntry("bx-v", "dynamic")
                    .containsEntry("version", "current");
            })
            .verifyComplete();
    }

    @Test
    void normalizesAliOssRegionForV4Signing() {
        assertThat(QwenMediaUploader.normalizeSigningRegion("oss-ap-southeast-1"))
            .isEqualTo("ap-southeast-1");
        assertThat(QwenMediaUploader.normalizeSigningRegion("ap-southeast-1"))
            .isEqualTo("ap-southeast-1");
    }

    @Test
    void discoversNestedOfficialModelsAndSkipsInactiveEntries() {
        var mapper = new ObjectMapper();
        var root = mapper.readTree("""
            {"data":{"data":[
              {"id":"qwen-active","name":"Qwen Active","info":{"is_active":true,
                "meta":{"max_context_length":1000000,"capabilities":{"thinking":true}}}},
              {"id":"qwen-disabled","info":{"is_active":false}}
            ]}}
            """);

        var models = QwenProvider.parseModels(root);

        assertThat(models).extracting(model -> model.id()).containsExactly("qwen-active");
        assertThat(models.getFirst().displayName()).isEqualTo("Qwen Active");
        assertThat(models.getFirst().metadata()).containsKey("qwen");
    }

    @Test
    void mapsResponsesDialectAndDecodesPhasedStream() {
        var mapper = new ObjectMapper();
        var raw = mapper.createObjectNode().put("model", "qwen/qwen3.7-plus")
            .put("thinking_mode", "Thinking").put("web_search", true);
        var message = mapper.createObjectNode().put("role", "user").put("content", "hello");
        var request = new CanonicalRequest("r3", CanonicalRequest.Protocol.RESPONSES,
            "qwen", "qwen3.7-plus", true, List.of(message), Map.of(), Map.of(),
            List.of(), Map.of(), raw);
        var body = new QwenRequestMapper(mapper, new QwenProperties()).prepare(request, "chat-1");
        var decoder = new QwenEventDecoder("r3");
        var events = new java.util.ArrayList<CanonicalEvent>();
        events.addAll(decoder.decode("{\"choices\":[{\"delta\":{\"phase\":\"thinking\",\"content\":\"why\"}}]}"));
        events.addAll(decoder.decode("{\"choices\":[{\"delta\":{\"phase\":\"answer\",\"content\":\"answer\"},\"finish_reason\":\"stop\"}]}"));

        var feature = body.path("messages").get(0).path("feature_config");
        assertThat(feature.path("thinking_enabled").asBoolean()).isTrue();
        assertThat(feature.path("auto_search").asBoolean()).isTrue();
        assertThat(events).anyMatch(CanonicalEvent.ReasoningDelta.class::isInstance)
            .anyMatch(CanonicalEvent.OutputTextDelta.class::isInstance)
            .anyMatch(CanonicalEvent.Completed.class::isInstance);
    }

    @Test
    void mapsResponsesTokenLimitAndEmitsUpstreamUsageOnlyOnce() {
        var mapper = new ObjectMapper();
        var raw = mapper.createObjectNode().put("model", "qwen/qwen3.7-plus")
            .put("max_output_tokens", 128);
        var request = new CanonicalRequest("usage", CanonicalRequest.Protocol.RESPONSES,
            "qwen", "qwen3.7-plus", true, List.of(),
            Map.of("max_output_tokens", 128), Map.of(), List.of(), Map.of(), raw);

        var body = new QwenRequestMapper(mapper, new QwenProperties()).prepare(request, "chat");
        var events = new QwenEventDecoder("usage").decode(
            "{\"choices\":[{\"delta\":{\"content\":\"ok\"}}],"
                + "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1}}");

        assertThat(body.path("max_tokens").asInt()).isEqualTo(128);
        assertThat(events.stream().filter(CanonicalEvent.Usage.class::isInstance)).hasSize(1);
    }

    @Test
    void rejectsAnEmptySuccessfulStreamInsteadOfCompletingIt() {
        var events = new QwenEventDecoder("empty").finish();

        assertThat(events).anyMatch(event -> event instanceof CanonicalEvent.Failed failed
            && failed.errorType().equals("empty_model_response"));
        assertThat(events).noneMatch(CanonicalEvent.ResponseStarted.class::isInstance);
        assertThat(events).noneMatch(CanonicalEvent.Completed.class::isInstance);
    }

    @Test
    void mapsUploadedVisionFilesWithoutFlatteningThemIntoPromptText() {
        var mapper = new ObjectMapper();
        var raw = mapper.createObjectNode().put("model", "qwen/qwen3.7-plus");
        var message = mapper.createObjectNode().put("role", "user").put("content", "describe");
        var request = new CanonicalRequest("r-vision", CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "qwen", "qwen3.7-plus", false, List.of(message), Map.of(), Map.of(),
            List.of(), Map.of(), raw);
        var file = mapper.createObjectNode()
            .put("type", "image")
            .put("id", "file-1")
            .put("file_class", "vision");

        var body = new QwenRequestMapper(mapper, new QwenProperties()).prepare(
            request, "chat-vision",
            List.of(new QwenPreparedMessage("user", "describe", List.of(file))));

        var upstream = body.path("messages").get(0);
        assertThat(upstream.path("content").asText()).isEqualTo("describe");
        assertThat(upstream.path("files").get(0).path("id").asText()).isEqualTo("file-1");
        assertThat(upstream.path("files").get(0).path("file_class").asText())
            .isEqualTo("vision");
    }

    @Test
    void foldsUnsupportedSystemRoleIntoTheFirstUserMessage() {
        var mapper = new ObjectMapper();
        var raw = mapper.createObjectNode().put("model", "qwen/qwen3.7-plus");
        var system = mapper.createObjectNode().put("role", "system")
            .put("content", "Reply exactly");
        var user = mapper.createObjectNode().put("role", "user").put("content", "PROTO_OK");
        var request = new CanonicalRequest("system", CanonicalRequest.Protocol.RESPONSES,
            "qwen", "qwen3.7-plus", false, List.of(system, user), Map.of(), Map.of(),
            List.of(), Map.of(), raw);

        var body = new QwenRequestMapper(mapper, new QwenProperties()).prepare(request, "chat");

        assertThat(body.path("messages")).hasSize(1);
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("user");
        assertThat(body.path("messages").get(0).path("content").asText())
            .contains("[System instructions]")
            .contains("Reply exactly")
            .contains("PROTO_OK");
    }
}
