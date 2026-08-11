package com.any2api.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderKeepaliveSettingsTest {
    @Test
    void normalizesProviderPoliciesAndPreservesTypedParameters() {
        var settings = new RuntimeSettingsService.ProviderKeepaliveSettings(Map.of(
            "qwen", new RuntimeSettingsService.ProviderKeepalivePolicy(
                180, 15, Map.of("model", "qwen3-max", "lightweight", true))));

        var normalized = settings.normalized();

        assertThat(normalized.providers().get("qwen").intervalMinutes()).isEqualTo(180);
        assertThat(normalized.providers().get("qwen").parameters())
            .containsEntry("model", "qwen3-max")
            .containsEntry("lightweight", true);
    }

    @Test
    void rejectsReservedPayloadFields() {
        var settings = new RuntimeSettingsService.ProviderKeepaliveSettings(Map.of(
            "qwen", new RuntimeSettingsService.ProviderKeepalivePolicy(
                180, 15, Map.of("credential", "override"))));

        assertThatThrownBy(settings::normalized)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reserved");
    }

    @Test
    void rejectsJitterLargerThanTheKeepaliveInterval() {
        var policy = new RuntimeSettingsService.ProviderKeepalivePolicy(
            30, 31, Map.of());

        assertThatThrownBy(policy::normalized)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("jitter");
    }
}
