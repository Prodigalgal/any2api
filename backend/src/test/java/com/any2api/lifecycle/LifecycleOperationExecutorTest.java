package com.any2api.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.any2api.observability.OperationContext;
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
        var credential = mapper.createObjectNode().put("service_token", "secret");
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
        new LifecycleOperationExecutor(registry, automation, settings)
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
            .containsEntry("proxy_pool", proxyPool);
    }
}
