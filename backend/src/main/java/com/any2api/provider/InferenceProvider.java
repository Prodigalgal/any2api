package com.any2api.provider;

import com.any2api.account.LeasedProviderAccount;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import java.time.Duration;
import java.util.Set;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

public interface InferenceProvider {

    ProviderManifest manifest();

    default ProviderProtocolContract protocolContract() {
        return ProviderProtocolContract.strict();
    }

    default void validate(CanonicalRequest request) {
    }

    default boolean supportsAccount(CanonicalRequest request, ProviderAccountProfile account) {
        return true;
    }

    default void validateCredential(JsonNode credential) {
    }

    default ProviderRetryPolicy retryPolicy() {
        return ProviderRetryPolicy.standard();
    }

    default Set<ProviderTransportMode> supportedTransportModes() {
        return Set.of(ProviderTransportMode.RUNTIME);
    }

    default ProviderTransportMode defaultTransportMode() {
        return ProviderTransportMode.RUNTIME;
    }

    default Duration modelProbeTimeout() {
        return Duration.ofSeconds(45);
    }

    /**
     * Whether the framework may issue broad scheduled probes for this provider's catalog.
     * Provider-native anti-abuse or billing constraints may opt out; explicit admin probes,
     * lifecycle readiness probes, and real request evidence remain available.
     */
    default boolean scheduledModelProbeEnabled() {
        return true;
    }

    default Duration accountProbeTimeout() {
        return Duration.ofSeconds(30);
    }

    default ModelCapabilityContract modelContract(DiscoveredModel model) {
        return ModelCapabilityContract.from(manifest(), protocolContract(), model);
    }

    Flux<CanonicalEvent> generate(
        CanonicalRequest request,
        ProviderExecutionContext context,
        LeasedProviderAccount account
    );

    ProviderFailure classify(Throwable error);

    default Mono<java.util.List<DiscoveredModel>> discoverModels(LeasedProviderAccount account) {
        return Mono.error(new UnsupportedOperationException(
            "provider does not implement official model discovery: " + manifest().id()));
    }

    /**
     * Discover models through the selected inference channel. Providers that support API and
     * Runtime must override this overload so catalog health reflects the configured channel.
     */
    default Mono<java.util.List<DiscoveredModel>> discoverModels(
        LeasedProviderAccount account,
        ProviderTransportMode transportMode
    ) {
        return discoverModels(account);
    }
}
