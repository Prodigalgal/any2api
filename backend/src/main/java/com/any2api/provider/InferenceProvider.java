package com.any2api.provider;

import tools.jackson.databind.node.ObjectNode;

/** Provider protocol SPI. Implementations are isolated and auto-discovered by Spring. */
public interface InferenceProvider {

    ProviderManifest manifest();

    PreparedProviderRequest prepare(
        ProviderOperation operation,
        ObjectNode request,
        String upstreamModel
    );

    ProviderFailure classify(Throwable error);
}
