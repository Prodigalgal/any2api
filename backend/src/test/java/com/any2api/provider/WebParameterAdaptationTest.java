package com.any2api.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.any2api.protocol.CanonicalRequest;
import com.any2api.protocol.CanonicalRequestParser;
import com.any2api.protocol.OpenAiRequestException;
import com.any2api.routing.ResolvedRoute;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.ObjectMapper;

class WebParameterAdaptationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @EnumSource(CanonicalRequest.Protocol.class)
    void bridgesCommonOpenAiControlsAcrossSevenWebProviders(CanonicalRequest.Protocol protocol) {
        var providers = List.<Class<? extends InferenceProvider>>of(
            com.any2api.provider.arena.ArenaProvider.class,
            com.any2api.provider.deepseek.DeepseekProvider.class,
            com.any2api.provider.glm.GlmProvider.class,
            com.any2api.provider.grok_web.GrokWebProvider.class,
            com.any2api.provider.longcat.LongcatProvider.class,
            com.any2api.provider.mimo.MimoProvider.class,
            com.any2api.provider.minmax.MinmaxProvider.class);
        for (var type : providers) {
            var provider = org.mockito.Mockito.mock(type, org.mockito.Mockito.CALLS_REAL_METHODS);
            var id = provider.manifest().id();
            var raw = mapper.createObjectNode().put("model", id + "/fixture")
                .put("temperature", 0.3).put("top_p", 0.8).put("seed", 123)
                .put("presence_penalty", 0.2).put("frequency_penalty", 0.1).put("max_output_tokens", 128_000);
            raw.putObject("reasoning").put("effort", "high").putNull("summary");
            raw.putArray("stop").add("END");
            if (protocol == CanonicalRequest.Protocol.RESPONSES) raw.put("input", "hello");
            else {
                raw.putArray("messages").addObject().put("role", "user").put("content", "hello");
                raw.put("n", 1).put("logprobs", true).put("top_logprobs", 2);
                raw.putArray("modalities").add("text");
            }
            raw.putArray("tools").addObject().put("type", "function").putObject("function")
                .put("name", "inspect").putObject("parameters").put("type", "object");
            var original = raw.deepCopy();
            var request = new CanonicalRequestParser(mapper).parse(protocol, new ResolvedRoute(id, "fixture"), raw);
            var adapted = WebParameterAdaptation.prepare(request, provider.protocolContract(),
                mapper.createObjectNode().put("max_output_tokens", 8192));
            assertThatCode(() -> ProviderRequestValidation.requireSupportedRequest(
                adapted, provider.manifest(), provider.protocolContract())).as(id).doesNotThrowAnyException();
            assertThat(adapted.messages()).isEqualTo(request.messages());
            assertThat(adapted.tools()).isEqualTo(request.tools());
            assertThat(adapted.rawRequest().path("max_output_tokens").asLong()).isLessThanOrEqualTo(8192);
            assertThat(request.rawRequest()).isEqualTo(original);
            assertThat(raw).isEqualTo(original);
        }
    }

    @Test
    void clampsMappedOutputBudgetsWithoutRemovingNativeSamplingControls() {
        var raw = mapper.createObjectNode().put("model", "guarded/model").put("input", "hello")
            .put("temperature", 0.6).put("max_output_tokens", 128_000);
        var request = new CanonicalRequestParser(mapper).parse(CanonicalRequest.Protocol.RESPONSES,
            new ResolvedRoute("guarded", "model"), raw);
        var contract = new ProviderProtocolContract(Map.of(), Set.of(),
            Set.of("temperature", "max_output_tokens"), Set.of());
        var adapted = WebParameterAdaptation.prepare(request, contract,
            mapper.createObjectNode().put("max_context_tokens", 256).put("max_output_tokens", 8192));
        assertThat(adapted.generation().get("temperature")).isEqualTo(0.6);
        assertThat(adapted.rawRequest().path("max_output_tokens").asLong()).isPositive().isLessThan(256);
        assertThatCode(() -> new ModelRequestLimitGuard().requireWithinLimits(adapted,
            mapper.createObjectNode().put("max_context_tokens", 256).put("max_output_tokens", 8192)))
            .doesNotThrowAnyException();
        assertThat(request.rawRequest().path("max_output_tokens").asLong()).isEqualTo(128_000);
    }

    @Test
    void doesNotDropUnknownReasoningOptionsOrMalformedOpenAiValues() {
        var raw = mapper.createObjectNode().put("model", "guarded/model").put("input", "hello");
        raw.putObject("reasoning").putNull("typo");
        var parser = new CanonicalRequestParser(mapper);
        var request = parser.parse(CanonicalRequest.Protocol.RESPONSES, new ResolvedRoute("guarded", "model"), raw);
        var adapted = WebParameterAdaptation.prepare(request, ProviderProtocolContract.strict(), null);
        assertThat(adapted.rawRequest().path("reasoning").has("typo")).isTrue();
        raw.putObject("reasoning").put("summary", 2);
        assertThatThrownBy(() -> parser.parse(CanonicalRequest.Protocol.RESPONSES,
            new ResolvedRoute("guarded", "model"), raw)).isInstanceOf(OpenAiRequestException.class);
        raw.remove("reasoning");
        raw.putObject("stop").put("invalid", true);
        assertThatThrownBy(() -> parser.parse(CanonicalRequest.Protocol.RESPONSES,
            new ResolvedRoute("guarded", "model"), raw)).isInstanceOf(OpenAiRequestException.class);
    }

    @Test
    void publishesProviderCharacterEvidenceWithoutInventingATokenContextLimit() {
        var result = mapper.valueToTree(WebParameterAdaptation.describe(ProviderProtocolContract.strict(),
            Map.of("input_character_limit", 2621440)));
        assertThat(result.path("input").path("limit").asLong()).isEqualTo(2621440L);
        assertThat(result.path("input").path("unit").asText()).isEqualTo("provider_characters");
        assertThat(result.path("input").path("token_limit_verified").asBoolean()).isFalse();
        var unknown = mapper.valueToTree(WebParameterAdaptation.describe(ProviderProtocolContract.strict()));
        assertThat(unknown.path("input").path("mode").asText()).isEqualTo("unknown");
        assertThat(unknown.path("input").has("limit")).isFalse();
    }

    @Test
    void distinguishesNativeTargetsFromBestEffortControls() {
        var contract = new ProviderProtocolContract(Map.of(), Set.of("temperature", "top_p", "max_output_tokens"),
            Set.of("temperature", "top_p", "max_output_tokens"), Set.of("function"));
        var mimo = mapper.valueToTree(WebParameterAdaptation.describe(contract.withParameterMappings(Map.of(
            "top_p", WebParameterAdaptation.mapped("modelConfig.topP"),
            "max_output_tokens", WebParameterAdaptation.ignoredOutputLimit()))));
        assertThat(mimo.path("top_p").path("target").asText()).isEqualTo("modelConfig.topP");
        assertThat(mimo.path("max_output_tokens").path("mode").asText()).isEqualTo("best_effort_ignore");
        assertThat(mimo.path("max_output_tokens").path("enforced").asBoolean()).isFalse();
        assertThat(mimo.path("max_tokens").path("mode").asText()).isEqualTo("best_effort_ignore");
        var glm = mapper.valueToTree(WebParameterAdaptation.describe(contract.withParameterMappings(Map.of(
            "max_output_tokens", WebParameterAdaptation.mapped("params.max_tokens")))));
        assertThat(glm.path("max_output_tokens").path("target").asText()).isEqualTo("params.max_tokens");
        var arena = mapper.valueToTree(WebParameterAdaptation.describe(ProviderProtocolContract.strict()
            .withParameterMappings(Map.of("max_output_tokens", WebParameterAdaptation.mapped("unaccepted")))));
        assertThat(arena.path("max_output_tokens").path("mode").asText()).isEqualTo("best_effort_ignore");
    }

    @Test
    void doesNotClaimExactReasoningLevelsForBooleanWebControls() {
        var contract = new ProviderProtocolContract(Map.of(), Set.of("reasoning"), Set.of("reasoning"), Set.of());
        for (var target : Set.of("thinking_enabled", "reason_enabled", "modelConfig.enableThinking", "model.variant")) {
            var result = mapper.valueToTree(WebParameterAdaptation.describe(contract.withParameterMappings(
                Map.of("reasoning", WebParameterAdaptation.toggle(target)))));
            assertThat(result.path("reasoning").path("mode").asText()).isEqualTo("toggle_mapping");
            assertThat(result.path("reasoning").path("exact_effort_levels").asBoolean()).isFalse();
        }
    }
}
