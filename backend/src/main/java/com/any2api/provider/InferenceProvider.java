package com.any2api.provider;

import com.any2api.account.LeasedProviderAccount;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
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
}
