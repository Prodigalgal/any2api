package com.any2api.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.any2api.protocol.CanonicalRequest;
import com.any2api.provider.arena.ArenaProperties;
import com.any2api.provider.arena.ArenaProvider;
import com.any2api.provider.deepseek.DeepseekProperties;
import com.any2api.provider.deepseek.DeepseekProvider;
import com.any2api.provider.glm.GlmProperties;
import com.any2api.provider.glm.GlmProvider;
import com.any2api.provider.minmax.MinmaxProvider;
import com.any2api.provider.qwen.QwenProperties;
import com.any2api.provider.qwen.QwenProvider;
import com.any2api.proxy.ProxyPoolService;
import com.any2api.transport.OfficialBrowserSemanticCommandFactory;
import com.any2api.transport.OfficialBrowserTransportClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.ObjectMapper;

class WebFunctionBridgeTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @EnumSource(CanonicalRequest.Protocol.class)
    void allFiveProvidersAcceptOrdinaryFunctionContractsAndRejectUndeclaredChoices(
        CanonicalRequest.Protocol protocol
    ) {
        var transport = mock(OfficialBrowserTransportClient.class);
        var commands = new OfficialBrowserSemanticCommandFactory(mapper);
        var proxies = mock(ProxyPoolService.class);
        var engine = new ToolEmulationEngine(mapper);
        var providers = List.<InferenceProvider>of(
            new DeepseekProvider(transport, commands, proxies, new DeepseekProperties(), mapper, engine),
            new QwenProvider(transport, commands, proxies, new QwenProperties(), mapper, engine),
            new GlmProvider(new GlmProperties(), proxies, mapper, transport, commands, engine),
            new MinmaxProvider(transport, commands, proxies, mapper, engine),
            new ArenaProvider(new ArenaProperties(), proxies, mapper, transport, commands, engine));
        for (var provider : providers) {
            var tool = mapper.createObjectNode().put("type", "function").put("name", "inspect");
            tool.putObject("parameters").put("type", "object");
            var raw = mapper.createObjectNode().put("parallel_tool_calls", false);
            raw.putObject("tool_choice").put("type", "function").put("name", "inspect");
            var request = new CanonicalRequest("bridge", protocol, provider.manifest().id(),
                provider.manifest().defaultModels().getFirst(), true,
                List.of(mapper.createObjectNode().put("role", "user").put("content", "Inspect")),
                Map.of(), Map.of(), List.of(tool), Map.of(), raw);

            provider.validate(request);
            assertThat(provider.manifest().capabilities())
                .containsEntry(ProviderCapability.FUNCTION_TOOLS, SupportLevel.EMULATED);
            assertThat(provider.protocolContract().toolTypes()).contains("function");
            assertThat(provider.protocolContract().chatParameters())
                .contains("tools", "tool_choice", "parallel_tool_calls");
            assertThat(provider.protocolContract().responsesParameters())
                .contains("tools", "tool_choice", "parallel_tool_calls");

            raw.putObject("tool_choice").put("type", "function").put("name", "undeclared");
            assertThatThrownBy(() -> provider.validate(request)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
