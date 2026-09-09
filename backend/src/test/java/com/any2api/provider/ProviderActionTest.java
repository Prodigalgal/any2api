package com.any2api.provider;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProviderActionTest {
    @Test
    void mapsLegacyTransportOperationsToStableActions() {
        assertThat(ProviderAction.fromLegacyOperation("models"))
            .isEqualTo(ProviderAction.MODEL_DISCOVERY);
        assertThat(ProviderAction.fromLegacyOperation("chat"))
            .isEqualTo(ProviderAction.CHAT);
        assertThat(ProviderAction.fromLegacyOperation("files_policy"))
            .isEqualTo(ProviderAction.MEDIA_POLICY);
        assertThat(ProviderAction.fromLegacyOperation("unclassified"))
            .isEqualTo(ProviderAction.RAW_REQUEST);
    }

    @Test
    void keepsLegacyOperationForCompatibilityWhileExposingActionName() {
        assertThat(ProviderAction.MODEL_DISCOVERY.externalName()).isEqualTo("model_discovery");
        assertThat(ProviderAction.MODEL_DISCOVERY.legacyOperation()).isEqualTo("models");
        assertThat(ProviderAction.RAW_REQUEST.legacyOperation()).isNull();
    }
}
