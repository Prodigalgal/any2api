package com.any2api.provider.deepseek;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.any2api.proxy.ProxyPoolService;
import com.any2api.transport.OfficialBrowserSemanticCommandFactory;
import com.any2api.transport.OfficialBrowserTransportClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DeepseekProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void allowsBrowserInitializationAndPowDuringRuntimeProbes() {
        var properties = new DeepseekProperties();
        var provider = new DeepseekProvider(
            mock(OfficialBrowserTransportClient.class),
            new OfficialBrowserSemanticCommandFactory(mapper),
            mock(ProxyPoolService.class), properties, mapper);

        assertThat(provider.modelProbeTimeout()).isEqualTo(Duration.ofSeconds(240));
        assertThat(provider.accountProbeTimeout()).isEqualTo(Duration.ofSeconds(240));
        assertThatThrownBy(() -> properties.setModelProbeTimeout(Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be positive");
    }

    @Test
    void parsesEnabledOfficialModelConfigurations() {
        var root = mapper.readTree("""
            {"data":{"biz_data":{"settings":{"model_configs":{"value":[
              {"model_type":"default","name":"Fast","enabled":true,"switchable":true,
               "is_default":true,"think_feature":{},"search_feature":{},
               "file_feature":{"vision":false},"input_character_limit":1000},
              {"model_type":"vision","name":"Vision","enabled":true,"switchable":true,
               "is_default":false,"think_feature":{},"search_feature":null,
               "file_feature":{"vision":true},"input_character_limit":2000},
              {"model_type":"retired","enabled":false,"switchable":true}
            ]}}}}}
            """);

        var models = DeepseekProvider.parseModels(root);

        assertThat(models).extracting(model -> model.id()).containsExactly("default", "vision");
        assertThat(models.get(1).metadata()).containsEntry("vision", true);
    }

    @Test
    void extractsClientVersionFromOfficialBundle() {
        assertThat(DeepseekOfficialProfileRefresher.parseVersion(
            "let profile={appVersion:\"2.3.0\",clientPlatform:\"web\"}"))
            .isEqualTo("2.3.0");
    }
}
