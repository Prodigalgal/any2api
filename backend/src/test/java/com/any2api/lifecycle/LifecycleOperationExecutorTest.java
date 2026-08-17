package com.any2api.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.any2api.observability.OperationContext;
import com.any2api.provider.xai_identity.XaiLifecyclePayloadPolicy;
import com.any2api.runtime.ProviderRuntimeRuleService;
import com.any2api.settings.RuntimeSettingsService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

class LifecycleOperationExecutorTest {

    @Test
    void forwardsAccountMetadataToRemoteProviderWorkers() {
        var registry = mock(ProviderLifecycleRegistry.class);
        var automation = mock(LifecycleAutomationClient.class);
        var mapper = new ObjectMapper();
        var credential = mapper.createObjectNode()
            .put("service_token", "secret")
            .put("proxy_affinity_key", "persisted-affinity")
            .put("proxy_node_offset", 2);
        var metadata = Map.<String, Object>of(
            "inference_probe_status", "FAILED",
            "inference_probe_error", "credential_rejected");
        var proxyPool = Map.<String, Object>of("mode", "NODE_LIST");
        when(registry.handler("mimo", AutomationOperation.REAUTHENTICATE))
            .thenReturn(Optional.empty());
        when(automation.execute(
            eq("mimo"), eq("reauthenticate"), anyMap(), any(OperationContext.class)))
            .thenReturn(Mono.just(mapper.createObjectNode().put("healthy", true)));

        var settings = org.mockito.Mockito.mock(
            com.any2api.settings.RuntimeSettingsService.class);
        var runtimeRules = mock(com.any2api.runtime.ProviderRuntimeRuleService.class);
        new LifecycleOperationExecutor(registry, automation, settings, runtimeRules, List.of())
            .execute("mimo", "reauthenticate", credential, metadata, proxyPool)
            .block();

        @SuppressWarnings({"unchecked", "rawtypes"})
        var payload = (ArgumentCaptor<Map<String, Object>>) (ArgumentCaptor)
            ArgumentCaptor.forClass(Map.class);
        verify(automation).execute(
            eq("mimo"), eq("reauthenticate"), payload.capture(), any(OperationContext.class));
        assertThat(payload.getValue())
            .containsEntry("credential", credential)
            .containsEntry("metadata", metadata)
            .containsEntry("proxy_pool", proxyPool)
            .containsEntry("proxy_affinity_key", "persisted-affinity")
            .containsEntry("proxy_node_offset", 2)
            .containsEntry("strict_proxy_affinity", true);
    }

    @Test
    void forwardsCurrentRuntimePlanToOfficialBrowserKeepalive() {
        var registry = mock(ProviderLifecycleRegistry.class);
        var automation = mock(LifecycleAutomationClient.class);
        var settings = mock(RuntimeSettingsService.class);
        var runtimeRules = mock(ProviderRuntimeRuleService.class);
        var mapper = new ObjectMapper();
        var document = new ProviderRuntimeRuleService.RuleDocument(
            1, 900, 60, List.of("asset"),
            Map.of("requestModule", List.of("marker")),
            Map.of("models", "getConfig"),
            Map.of("models", "/config"));
        var selection = new ProviderRuntimeRuleService.RuleSelection(
            "mimo", 7, document);
        var plan = new ProviderRuntimeRuleService.RuntimePlan(selection, null, null, null);
        when(registry.handler("mimo", AutomationOperation.KEEPALIVE))
            .thenReturn(Optional.empty());
        when(runtimeRules.findPlan("mimo")).thenReturn(Optional.of(plan));
        when(automation.execute(
            eq("mimo"), eq("keepalive"), anyMap(), any(OperationContext.class)))
            .thenReturn(Mono.just(mapper.createObjectNode().put("healthy", true)));

        new LifecycleOperationExecutor(registry, automation, settings, runtimeRules, List.of())
            .execute("mimo", "keepalive", mapper.createObjectNode(), Map.of(), Map.of())
            .block();

        @SuppressWarnings({"unchecked", "rawtypes"})
        var payload = (ArgumentCaptor<Map<String, Object>>) (ArgumentCaptor)
            ArgumentCaptor.forClass(Map.class);
        verify(automation).execute(
            eq("mimo"), eq("keepalive"), payload.capture(), any(OperationContext.class));
        assertThat(payload.getValue()).containsEntry("runtime_plan", plan);
    }

    @Test
    void forcesGrokSsoRefreshOnlyWhenRecoveryMetadataRequestsIt() {
        var registry = mock(ProviderLifecycleRegistry.class);
        var automation = mock(LifecycleAutomationClient.class);
        var settings = mock(RuntimeSettingsService.class);
        var runtimeRules = mock(ProviderRuntimeRuleService.class);
        var mapper = new ObjectMapper();
        when(registry.handler("grok", AutomationOperation.REAUTHENTICATE))
            .thenReturn(Optional.empty());
        when(automation.execute(
            eq("grok"), eq("reauthenticate"), anyMap(), any(OperationContext.class)))
            .thenReturn(Mono.just(mapper.createObjectNode().put("healthy", true)));

        new LifecycleOperationExecutor(
            registry, automation, settings, runtimeRules,
            List.of(new XaiLifecyclePayloadPolicy()))
            .execute("grok", "reauthenticate", mapper.createObjectNode(),
                Map.of("xai_force_sso_refresh", true), Map.of())
            .block();

        @SuppressWarnings({"unchecked", "rawtypes"})
        var payload = (ArgumentCaptor<Map<String, Object>>) (ArgumentCaptor)
            ArgumentCaptor.forClass(Map.class);
        verify(automation).execute(
            eq("grok"), eq("reauthenticate"), payload.capture(), any(OperationContext.class));
        assertThat(payload.getValue()).containsEntry("force_sso_refresh", true);
    }
}
