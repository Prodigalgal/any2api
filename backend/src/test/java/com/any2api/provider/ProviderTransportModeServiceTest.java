package com.any2api.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.any2api.account.LeasedProviderAccount;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import reactor.core.publisher.Flux;

class ProviderTransportModeServiceTest {

    @Test
    void defaultRemainsRuntimeEvenWhenApiIsSupported() {
        var provider = new TestProvider(Set.of(
            ProviderTransportMode.API, ProviderTransportMode.RUNTIME));
        var plan = new ProviderTransportModeService(mock(JdbcClient.class))
            .plan(provider);

        assertThat(plan.requested()).isEqualTo(ProviderTransportMode.RUNTIME);
        assertThat(plan.primary()).isEqualTo(ProviderTransportMode.RUNTIME);
        assertThat(plan.fallback()).isNull();
    }

    @Test
    void autoPrefersApiAndKeepsRuntimeAsTheSafeFallback() {
        var provider = new TestProvider(Set.of(
            ProviderTransportMode.API, ProviderTransportMode.RUNTIME));
        var jdbc = mock(JdbcClient.class);
        var statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.update()).thenReturn(1);
        var service = new ProviderTransportModeService(jdbc);

        service.set(provider, ProviderTransportMode.AUTO);
        var plan = service.plan(provider);
        assertThat(plan.requested()).isEqualTo(ProviderTransportMode.AUTO);
        assertThat(plan.primary()).isEqualTo(ProviderTransportMode.API);
        assertThat(plan.fallback()).isEqualTo(ProviderTransportMode.RUNTIME);
        assertThat(ProviderTransportMode.parse("camoufox_browser_runtime"))
            .isEqualTo(ProviderTransportMode.RUNTIME);
    }

    private static final class TestProvider implements InferenceProvider {
        private final Set<ProviderTransportMode> modes;

        private TestProvider(Set<ProviderTransportMode> modes) {
            this.modes = modes;
        }

        @Override public ProviderManifest manifest() {
            return new ProviderManifest("test", "Test", "test", "1", List.of("model"),
                Map.of(ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE,
                    ProviderCapability.RESPONSES, SupportLevel.NATIVE), true);
        }

        @Override public Set<ProviderTransportMode> supportedTransportModes() { return modes; }

        @Override
        public Flux<CanonicalEvent> generate(
            CanonicalRequest request,
            ProviderExecutionContext context,
            LeasedProviderAccount account
        ) {
            return Flux.empty();
        }

        @Override
        public ProviderFailure classify(Throwable error) {
            return new ProviderFailure("test", "test", false, Map.of());
        }
    }
}
