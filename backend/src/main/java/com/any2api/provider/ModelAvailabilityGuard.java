package com.any2api.provider;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class ModelAvailabilityGuard {
    private final ModelCatalogCache catalog;

    public ModelAvailabilityGuard(ModelCatalogCache catalog) {
        this.catalog = catalog;
    }

    public Mono<Void> requireCallable(String providerId, String modelId) {
        return catalog.list().flatMap(entries -> entries.stream()
            .filter(entry -> entry.providerId().equals(providerId) && entry.id().equals(modelId))
            .findFirst()
            .filter(entry -> !entry.available())
            .<Mono<Void>>map(entry -> Mono.error(new ModelUnavailableException(
                providerId, modelId, entry.runtimeStatus(), entry.probeError())))
            .orElseGet(Mono::empty));
    }

    public static final class ModelUnavailableException extends RuntimeException {
        private final String providerId;
        private final String modelId;
        private final String runtimeStatus;
        private final String probeError;

        public ModelUnavailableException(
            String providerId,
            String modelId,
            String runtimeStatus,
            String probeError
        ) {
            super("model is not currently callable");
            this.providerId = providerId;
            this.modelId = modelId;
            this.runtimeStatus = runtimeStatus;
            this.probeError = probeError;
        }

        public String providerId() { return providerId; }
        public String modelId() { return modelId; }
        public String runtimeStatus() { return runtimeStatus; }
        public String probeError() { return probeError; }
    }
}
