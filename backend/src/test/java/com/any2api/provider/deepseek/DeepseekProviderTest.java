package com.any2api.provider.deepseek;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DeepseekProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();

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
