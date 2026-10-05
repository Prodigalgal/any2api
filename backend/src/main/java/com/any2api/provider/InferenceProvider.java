package com.any2api.provider;

import com.any2api.account.LeasedProviderAccount;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
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

    default Optional<ModelAccountPolicy> modelAccountPolicy() {
        return Optional.empty();
    }

    default boolean supportsAccount(CanonicalRequest request, ProviderAccountProfile account) {
        return modelAccountPolicy().map(policy -> policy.supports(request.model(), account)).orElse(true);
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
        return ProviderTransportMode.AUTO;
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

    /**
     * Primary representative model to probe for scheduled health checks.
     * When set, scheduled probe only tests this single model per provider to conserve quota and avoid anti-bot triggers.
     */
    default Optional<String> scheduledProbeModel() {
        var preferred = manifest().randomModelPreferences()
            .getOrDefault(RandomModelRole.TOP_TEXT, List.of());
        if (!preferred.isEmpty()) return Optional.of(preferred.getFirst());
        if (!manifest().defaultModels().isEmpty()) return Optional.of(manifest().defaultModels().getFirst());
        return Optional.empty();
    }

    /**
     * Optional secondary models to probe when the primary model triggers anti-bot or rate limiting during readiness checks.
     */
    default List<String> fallbackProbeModels() {
        return List.of();
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
