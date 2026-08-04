package com.any2api.provider;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.node.JsonNodeFactory;

class ModelAvailabilityGuardTest {
    @Test
    void rejectsCatalogedUnavailableModelBeforeExecution() {
        var catalog = mock(ModelCatalogCache.class);
        var entry = new ModelCatalogCache.Entry(
            "qwen-plus", "Qwen Plus", "qwen", "Qwen",
            JsonNodeFactory.instance.objectNode(), "OFFICIAL",
            JsonNodeFactory.instance.objectNode(), List.of(), 1, false, "UNAVAILABLE",
            1, 0, 0, 3, 3, 0, 0, 0,
            null, null, "FAILED", "credential_rejected", null);
        when(catalog.list()).thenReturn(Mono.just(List.of(entry)));

        StepVerifier.create(new ModelAvailabilityGuard(catalog)
                .requireCallable("qwen", "qwen-plus"))
            .expectError(ModelAvailabilityGuard.ModelUnavailableException.class)
            .verify();
    }
}
