package com.any2api.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderRuntimeRuleDocumentTest {
    @Test
    void normalizesBoundedDeclarativeFields() {
        var rule = new ProviderRuntimeRuleService.RuleDocument(
            1, 900, 60, List.of(" /assets/index- ", "/assets/index-"),
            Map.of("newChat", List.of(" /chats/new ")),
            Map.of("chat", " completions "),
            Map.of("chat", " /api/v2/chat/completions "));

        var normalized = rule.normalized();

        assertThat(normalized.buildAssetMarkers()).containsExactly("/assets/index-");
        assertThat(normalized.discoveryMarkers()).containsEntry(
            "newChat", List.of("/chats/new"));
        assertThat(normalized.endpointPaths()).containsEntry(
            "chat", "/api/v2/chat/completions");
    }

    @Test
    void rejectsExecutableOrCrossOriginEndpointValues() {
        var rule = new ProviderRuntimeRuleService.RuleDocument(
            1, 900, 60, List.of("asset"),
            Map.of("module", List.of("marker")), Map.of(),
            Map.of("chat", "https://attacker.example/chat"));

        assertThatThrownBy(rule::normalized)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("same-origin paths");
    }

    @Test
    void rejectsControlCharactersInDiscoveryMarkers() {
        var rule = new ProviderRuntimeRuleService.RuleDocument(
            1, 900, 60, List.of("asset"),
            Map.of("module", List.of("marker\nscript")), Map.of(),
            Map.of("chat", "/chat"));

        assertThatThrownBy(rule::normalized)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invalid literal");
    }
}
