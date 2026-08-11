package com.any2api.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.any2api.account.AccountEntity;
import com.any2api.account.AccountStatus;
import com.any2api.account.LeasedProviderAccount;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.provider.InferenceProvider;
import com.any2api.provider.ProviderCapability;
import com.any2api.provider.ProviderExecutionContext;
import com.any2api.provider.ProviderFailure;
import com.any2api.provider.ProviderManifest;
import com.any2api.provider.ProviderRegistry;
import com.any2api.provider.RandomModelRole;
import com.any2api.provider.SupportLevel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

class InferenceReadinessProbeTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void requiresMarkerOutputAndCompletionFromTheSpecificAccount() {
        var probe = new InferenceReadinessProbe(
            ProviderRegistry.allEnabled(List.of(provider(true))), mapper);

        var result = probe.probe(account(), mapper.createObjectNode(), 1, null).block();

        assertThat(result).isNotNull();
        assertThat(result.ready()).isTrue();
        assertThat(result.model()).isEqualTo("alpha-top");
        assertThat(result.output()).isEqualTo("ANY2API_PROBE_OK");
    }

    @Test
    void preservesProviderFailureTypeWhenTheProbeCannotInfer() {
        var probe = new InferenceReadinessProbe(
            ProviderRegistry.allEnabled(List.of(provider(false))), mapper);

        var result = probe.probe(account(), mapper.createObjectNode(), 1, null).block();

        assertThat(result).isNotNull();
        assertThat(result.ready()).isFalse();
        assertThat(result.errorClass()).isEqualTo("credential_rejected");
    }

    @Test
    void acceptsAnyNonBlankCompletedResponseForRealtimeAvailability() {
        var probe = new InferenceReadinessProbe(
            ProviderRegistry.allEnabled(List.of(provider(true, "pong"))), mapper);

        var result = probe.probe(account(), mapper.createObjectNode(), 1, null).block();

        assertThat(result).isNotNull();
        assertThat(result.ready()).isTrue();
    }

    @Test
    void probesTheExplicitlySelectedModel() {
        var probe = new InferenceReadinessProbe(
            ProviderRegistry.allEnabled(List.of(provider(true))), mapper);

        var result = probe.probe(
            new LeasedProviderAccount(
                account().getId(), "alpha", "external", null, 1, null,
                mapper.createObjectNode(), Map.of(),
                new com.any2api.coordination.AccountLease(
                    "alpha", account().getId(), "owner", 1,
                    java.time.Instant.now().plusSeconds(60))),
            java.time.Duration.ofSeconds(10),
            "alpha-explicit").block();

        assertThat(result).isNotNull();
        assertThat(result.model()).isEqualTo("alpha-explicit");
    }

    private AccountEntity account() {
        var account = AccountEntity.create("alpha", "external", null, null, Map.of());
        account.updateState(AccountStatus.PENDING, false);
        return account;
    }

    private InferenceProvider provider(boolean ready) {
        return provider(ready, "ANY2API_PROBE_OK");
    }

    private InferenceProvider provider(boolean ready, String output) {
        return new InferenceProvider() {
            @Override
            public ProviderManifest manifest() {
                return new ProviderManifest(
                    "alpha", "Alpha", "test", "1", List.of("alpha-fallback"),
                    Map.of(
                        ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE,
                        ProviderCapability.RESPONSES, SupportLevel.NATIVE),
                    Map.of(RandomModelRole.TOP_TEXT, List.of("alpha-top")), true);
            }

            @Override
            public Flux<CanonicalEvent> generate(
                CanonicalRequest request,
                ProviderExecutionContext context,
                LeasedProviderAccount account
            ) {
                if (!ready) {
                    return Flux.just(new CanonicalEvent.Failed(
                        1, request.requestId(), 1, "credential_rejected", "rejected", Map.of()));
                }
                return Flux.just(
                    new CanonicalEvent.ResponseStarted(
                        1, request.requestId(), 0, "resp-probe"),
                    new CanonicalEvent.OutputTextDelta(
                        1, request.requestId(), 1, output),
                    new CanonicalEvent.Completed(1, request.requestId(), 2, "stop"));
            }

            @Override
            public ProviderFailure classify(Throwable error) {
                return new ProviderFailure(
                    "provider_transport_error", error.getClass().getSimpleName(), true, Map.of());
            }
        };
    }
}
