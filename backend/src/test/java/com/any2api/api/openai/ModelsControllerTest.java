package com.any2api.api.openai;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.any2api.api.ApiExceptionHandler;
import com.any2api.auth.ApiKeyAuthorization;
import com.any2api.auth.ApiKeyGrant;
import com.any2api.auth.ApiKeyProviderScope;
import com.any2api.provider.ModelCatalogCache;
import com.any2api.provider.ModelRuntimeGuard;
import com.any2api.provider.ProviderManifest;
import com.any2api.provider.ProviderRegistry;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

class ModelsControllerTest {
    private final ApiKeyAuthorization authorization = mock(ApiKeyAuthorization.class);
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        var registry = mock(ProviderRegistry.class);
        var manifest = mock(ProviderManifest.class);
        when(manifest.id()).thenReturn("mimo");
        when(registry.list()).thenReturn(List.of(manifest));
        var mapper = new ObjectMapper();
        var capabilities = mapper.readTree("{\"tools\":{\"function_calling\":true,\"strict\":true},\"streaming\":true,"
            + "\"parameter_adaptation\":{\"top_p\":{\"mode\":\"mapped\",\"target\":\"modelConfig.topP\"}}}");
        var entry = new ModelCatalogCache.Entry("group/model", "Fixture", "mimo", "MiMo", capabilities,
            capabilities, null, null, null, "fixture", mapper.createObjectNode(), List.of(), 1, true, "READY",
            1, 1, 0, 0, 0, 1.0, 0, 0, null, null, "PASSED", null, null);
        var catalog = mock(ModelCatalogCache.class);
        when(catalog.list()).thenReturn(Mono.just(List.of(entry)));
        var runtime = mock(ModelRuntimeGuard.class);
        when(runtime.callable("mimo", "group/model")).thenReturn(true);
        when(runtime.snapshot("mimo", "group/model")).thenReturn(new ModelRuntimeGuard.Snapshot(0, 0, "CLOSED", 0, 0));
        var grant = ApiKeyGrant.unrestricted();
        when(authorization.grant(any())).thenReturn(grant);
        when(authorization.current(any())).thenReturn(Optional.of(grant));
        client = WebTestClient.bindToController(new ModelsController(registry, catalog, authorization, runtime))
            .controllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void retrievesNamespacedAndEncodedModelIdsWithTheCatalogContract() {
        for (var uri : List.of("/v1/models/mimo/group/model", "/v1/models/mimo%2Fgroup%2Fmodel")) {
            client.get().uri(URI.create(uri)).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.object").isEqualTo("model").jsonPath("$.id").isEqualTo("mimo/group/model")
                .jsonPath("$.owned_by").isEqualTo("mimo").jsonPath("$.capabilities.tools.strict").isEqualTo(true)
                .jsonPath("$.available").isEqualTo(true).jsonPath("$.runtime.status").isEqualTo("READY")
                .jsonPath("$.parameter_adaptation.top_p.target").isEqualTo("modelConfig.topP");
        }
        client.get().uri(URI.create("/mimo/v1/models/group%2Fmodel")).exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.id").isEqualTo("group/model");
    }

    @Test
    void listsKeepExistingCapabilitiesWithoutRepeatingDetailedAdapterMappings() {
        for (var uri : List.of("/v1/models", "/mimo/v1/models")) {
            client.get().uri(uri).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data[0].capabilities.tools.strict").isEqualTo(true)
                .jsonPath("$.data[0].parameter_adaptation").doesNotExist()
                .jsonPath("$.data[0].capabilities.parameter_adaptation").doesNotExist();
        }
        client.get().uri("/v1/models/mimo/group/model").exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.capabilities.parameter_adaptation.top_p.target").isEqualTo("modelConfig.topP");
    }

    @Test
    void returnsTheSameNotFoundErrorForMissingAndForeignModels() {
        var grant = new ApiKeyGrant(UUID.randomUUID(), "other", Map.of("deepseek", ApiKeyProviderScope.allModels("deepseek")),
            Set.of(), Set.of(), null, false);
        when(authorization.grant(any())).thenReturn(grant);
        for (var uri : List.of("/v1/models/mimo/group/model", "/mimo/v1/models/group/model",
            "/v1/models/mimo/missing", "/unknown/v1/models/group/model")) {
            client.get().uri(uri).exchange().expectStatus().isNotFound().expectBody()
                .jsonPath("$.error.type").isEqualTo("invalid_request_error")
                .jsonPath("$.error.code").isEqualTo("model_not_found").jsonPath("$.error.param").isEqualTo("model");
        }
    }

    @Test
    void selectedModelScopesAndListPathsKeepTheirExistingBehavior() {
        var grant = new ApiKeyGrant(UUID.randomUUID(), "selected", Map.of("mimo",
            ApiKeyProviderScope.selectedModels("mimo", Set.of("other"))), Set.of(), Set.of(), null, false);
        when(authorization.grant(any())).thenReturn(grant);
        client.get().uri("/mimo/v1/models/group/model").exchange().expectStatus().isNotFound();
        client.get().uri("/v1/models").exchange().expectStatus().isOk().expectBody().jsonPath("$.object").isEqualTo("list");
    }
}
