package com.any2api.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.any2api.config.Any2ApiProperties;
import com.any2api.provider.grok.GrokProvider;
import java.net.URI;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class ProviderPluginTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void providerOwnsRequestPreparationWithoutMutatingCanonicalInput() {
        var properties = new Any2ApiProperties();
        var connection = new Any2ApiProperties.Provider();
        connection.setBaseUrl(URI.create("https://provider.invalid"));
        connection.setApiKey("test-only-key");
        properties.getProviders().put("grok", connection);
        var provider = new GrokProvider(properties);
        ObjectNode original = objectMapper.createObjectNode()
            .put("model", "grok/grok-4.5")
            .put("stream", true);

        var prepared = provider.prepare(
            ProviderOperation.RESPONSES,
            original,
            "grok-4.5");

        assertThat(prepared.upstreamPath()).isEqualTo("/v1/responses");
        assertThat(prepared.body().path("model").asText()).isEqualTo("grok-4.5");
        assertThat(original.path("model").asText()).isEqualTo("grok/grok-4.5");
        assertThat(provider.manifest().configured()).isTrue();
    }
}
