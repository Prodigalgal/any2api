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
import com.any2api.provider.ProviderTransportMode;
import com.any2api.provider.ProviderTransportModeService;
import com.any2api.provider.RandomModelRole;
import com.any2api.provider.SupportLevel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

class InferenceReadinessProbeTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void requiresMarkerOutputAndCompletionFromTheSpecificAccount() {
        var probe = new InferenceReadinessProbe(
            ProviderRegistry.allEnabled(List.of(provider(true))), mapper,
            transportModes(), probeModels());

        var result = probe.probe(account(), mapper.createObjectNode(), 1, null).block();

        assertThat(result).isNotNull();
        assertThat(result.ready()).isTrue();
        assertThat(result.model()).isEqualTo("alpha-top");
        assertThat(result.output()).isEqualTo("ANY2API_PROBE_OK");
    }

    @Test
    void preservesProviderFailureTypeWhenTheProbeCannotInfer() {
        var probe = new InferenceReadinessProbe(
            ProviderRegistry.allEnabled(List.of(provider(false))), mapper,
            transportModes(), probeModels());

        var result = probe.probe(account(), mapper.createObjectNode(), 1, null).block();

        assertThat(result).isNotNull();
        assertThat(result.ready()).isFalse();
        assertThat(result.errorClass()).isEqualTo("credential_rejected");
    }

    @Test
    void acceptsAnyNonBlankCompletedResponseForRealtimeAvailability() {
        var probe = new InferenceReadinessProbe(
            ProviderRegistry.allEnabled(List.of(provider(true, "pong"))), mapper,
            transportModes(), probeModels());

        var result = probe.probe(account(), mapper.createObjectNode(), 1, null).block();

        assertThat(result).isNotNull();
        assertThat(result.ready()).isTrue();
    }

    @Test
    void probesTheExplicitlySelectedModel() {
        var probe = new InferenceReadinessProbe(
            ProviderRegistry.allEnabled(List.of(provider(true))), mapper,
            transportModes(), probeModels());

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

    @Test
    void initialVerificationDoesNotRetryThroughTheFallbackTransport() {
        var transportModes = org.mockito.Mockito.mock(ProviderTransportModeService.class);
        org.mockito.Mockito.when(transportModes.plan(org.mockito.ArgumentMatchers.any()))
            .thenReturn(new ProviderTransportModeService.TransportPlan(
                ProviderTransportMode.AUTO, ProviderTransportMode.API, ProviderTransportMode.RUNTIME));
        var probe = new InferenceReadinessProbe(
            ProviderRegistry.allEnabled(List.of(provider(true, "pong", true))), mapper,
            transportModes, probeModels());

        var result = probe.probe(account(), mapper.createObjectNode(), 1, null).block();

        assertThat(result).isNotNull();
        assertThat(result.ready()).isFalse();
        assertThat(result.errorClass()).isEqualTo("provider_upstream_error");
    }

    @Test
    void initialVerificationDoesNotTrySecondaryModelsAfterFailure() {
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var fallbackProvider = new InferenceProvider() {
            @Override
            public ProviderManifest manifest() {
                return new ProviderManifest(
                    "alpha", "Alpha", "test", "1", List.of("primary-model", "secondary-model"),
                    Map.of(
                        ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE,
                        ProviderCapability.RESPONSES, SupportLevel.NATIVE),
                    Map.of(RandomModelRole.TOP_TEXT, List.of("primary-model")), true);
            }

            @Override
            public Flux<CanonicalEvent> generate(
                CanonicalRequest request,
                ProviderExecutionContext context,
                LeasedProviderAccount account
            ) {
                attempts.incrementAndGet();
                assertThat(request.messages().getFirst().path("content").asText()).isEqualTo("Reply with OK only.");
                if ("primary-model".equals(request.model())) {
                    return Flux.just(new CanonicalEvent.Failed(
                        1, request.requestId(), 1, "anti_bot_rejected", "blocked by cloudflare", Map.of()));
                }
                return Flux.just(
                    new CanonicalEvent.ResponseStarted(1, request.requestId(), 0, "resp-probe"),
                    new CanonicalEvent.OutputTextDelta(1, request.requestId(), 1, "fallback ok"),
                    new CanonicalEvent.Completed(1, request.requestId(), 2, "stop"));
            }

            @Override
            public ProviderFailure classify(Throwable error) {
                return new ProviderFailure("anti_bot_rejected", "blocked", false, Map.of());
            }
        };

        var probe = new InferenceReadinessProbe(
            ProviderRegistry.allEnabled(List.of(fallbackProvider)), mapper, transportModes(), probeModels());

        var result = probe.probe(account(), mapper.createObjectNode(), 1, null).block();

        assertThat(result).isNotNull();
        assertThat(result.ready()).isFalse();
        assertThat(result.model()).isEqualTo("primary-model");
        assertThat(result.errorClass()).isEqualTo("anti_bot_rejected");
        assertThat(attempts).hasValue(1);
    }

    private com.any2api.provider.ProviderProbeModelService probeModels() {
        return new com.any2api.provider.ProviderProbeModelService(org.mockito.Mockito.mock(JdbcClient.class));
    }

    @Test
    void frequentPartialEventsCannotExtendTheTotalVerificationDeadline() {
        var slow = org.mockito.Mockito.mock(InferenceProvider.class);
        org.mockito.Mockito.when(slow.manifest()).thenReturn(provider(true).manifest());
        org.mockito.Mockito.when(slow.protocolContract()).thenCallRealMethod();
        org.mockito.Mockito.when(slow.classify(org.mockito.ArgumentMatchers.any()))
            .thenReturn(new ProviderFailure("upstream_timeout", "timeout", false, Map.of()));
        org.mockito.Mockito.when(slow.generate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenAnswer(call -> {
                CanonicalRequest request = call.getArgument(0);
                return Flux.concat(Flux.just(new CanonicalEvent.ResponseStarted(1, request.requestId(), 0, "fixture")),
                    Flux.interval(java.time.Duration.ofMillis(20)).map(index -> new CanonicalEvent.OutputTextDelta(
                        1, request.requestId(), index + 1, "a")));
            });
        var modes = org.mockito.Mockito.mock(ProviderTransportModeService.class);
        org.mockito.Mockito.when(modes.plan(slow)).thenReturn(new ProviderTransportModeService.TransportPlan(
            ProviderTransportMode.RUNTIME, ProviderTransportMode.RUNTIME, null));
        var probe = new InferenceReadinessProbe(ProviderRegistry.allEnabled(List.of(slow)), mapper,
            modes, probeModels());
        var source = account();
        var leased = new LeasedProviderAccount(source.getId(), "alpha", "external", null, 1, null,
            mapper.createObjectNode(), Map.of(), new com.any2api.coordination.AccountLease(
                "alpha", source.getId(), "owner", 1, java.time.Instant.now().plusSeconds(10)));

        reactor.test.StepVerifier.withVirtualTime(() -> probe.probe(leased, java.time.Duration.ofMillis(100)))
            .thenAwait(java.time.Duration.ofMillis(101))
            .assertNext(result -> {
                assertThat(result.ready()).isFalse();
                assertThat(result.errorClass()).isEqualTo("upstream_timeout");
            }).verifyComplete();
    }

    private AccountEntity account() {
        var account = AccountEntity.create("alpha", "external", null, null, Map.of());
        account.updateState(AccountStatus.PENDING, false);
        return account;
    }

    private ProviderTransportModeService transportModes() {
        return new ProviderTransportModeService(org.mockito.Mockito.mock(JdbcClient.class));
    }

    private InferenceProvider provider(boolean ready) {
        return provider(ready, "ANY2API_PROBE_OK", false);
    }

    private InferenceProvider provider(boolean ready, String output) {
        return provider(ready, output, false);
    }

    private InferenceProvider provider(boolean ready, String output, boolean failApi) {
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
                if (!ready || (failApi && context.transportMode() == ProviderTransportMode.API)) {
                    return Flux.just(new CanonicalEvent.Failed(
                        1, request.requestId(), 1,
                        failApi ? "provider_upstream_error" : "credential_rejected",
                        "rejected", Map.of()));
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
