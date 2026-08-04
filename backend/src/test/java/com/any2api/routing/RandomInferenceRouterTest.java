package com.any2api.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.any2api.account.AccountSelectionService;
import com.any2api.auth.ApiKeyGrant;
import com.any2api.auth.ApiKeyProviderScope;
import com.any2api.auth.ApiKeyProtocol;
import com.any2api.account.AccountUnavailableException;
import com.any2api.account.LeasedProviderAccount;
import com.any2api.coordination.AccountLease;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.protocol.CanonicalRequestParser;
import com.any2api.provider.InferenceProvider;
import com.any2api.provider.ModelRuntimeGuard;
import com.any2api.provider.ProviderAccountProfile;
import com.any2api.provider.ProviderCapability;
import com.any2api.provider.ProviderExecutionContext;
import com.any2api.provider.ProviderFailure;
import com.any2api.provider.ProviderManifest;
import com.any2api.provider.ProviderProtocolContract;
import com.any2api.provider.ProviderRegistry;
import com.any2api.provider.RandomModelRole;
import com.any2api.provider.SupportLevel;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

class RandomInferenceRouterTest {
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @AfterEach
    void closeExecutor() {
        executor.shutdownNow();
    }

    @Test
    void selectsOnlyAValidatedModelWithAnEligibleProviderAccount() {
        var catalog = mock(RandomRouteCatalog.class);
        when(catalog.installedModels(RandomModelRole.TOP_TEXT)).thenReturn(List.of(
            new RandomRouteCatalog.ModelRoute("alpha", "bad-model"),
            new RandomRouteCatalog.ModelRoute("alpha", "good-model"),
            new RandomRouteCatalog.ModelRoute("beta", "other-model")));
        var accounts = mock(AccountSelectionService.class);
        when(accounts.acquire(eq("alpha"), eq("good-model"), any()))
            .thenReturn(Mono.just(leased("alpha")));
        when(accounts.acquire(eq("beta"), eq("other-model"), any()))
            .thenReturn(Mono.error(new AccountUnavailableException("beta")));
        var router = new RandomInferenceRouter(
            catalog,
            new ProviderRegistry(List.of(provider("alpha"), provider("beta"))),
            accounts,
            new CanonicalRequestParser(new ObjectMapper()),
            executor,
            runtimeGuard());
        var request = new ObjectMapper().createObjectNode();
        request.putArray("messages").addObject().put("role", "user").put("content", "hello");
        request.putObject("provider_options").putObject("alpha").put("flag", true);

        var selected = router.select(
            CanonicalRequest.Protocol.CHAT_COMPLETIONS, request,
            RandomModelRole.TOP_TEXT).block();

        assertThat(selected).isNotNull();
        assertThat(selected.request().providerId()).isEqualTo("alpha");
        assertThat(selected.request().model()).isEqualTo("good-model");
        assertThat(selected.request().rawRequest().path("model").asText())
            .isEqualTo("good-model");
        assertThat(selected.request().providerOptions()).containsEntry("flag", true);
        assertThat(selected.account().providerId()).isEqualTo("alpha");
        assertThat(request.has("model")).isFalse();
    }

    @Test
    void rejectsAConcreteModelOnTheRandomEndpoint() {
        var router = new RandomInferenceRouter(
            mock(RandomRouteCatalog.class),
            new ProviderRegistry(List.of(provider("alpha"))),
            mock(AccountSelectionService.class),
            new CanonicalRequestParser(new ObjectMapper()),
            executor,
            runtimeGuard());
        var request = new ObjectMapper().createObjectNode().put("model", "alpha/model");

        assertThatThrownBy(() -> router.select(
                CanonicalRequest.Protocol.RESPONSES, request,
                RandomModelRole.TOP_TEXT).block())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("model=random");
    }

    @Test
    void distributesEachProviderOnceBeforeRefillingTheShuffleBag() {
        var catalog = mock(RandomRouteCatalog.class);
        when(catalog.installedModels(RandomModelRole.TOP_MULTIMODAL)).thenReturn(List.of(
            new RandomRouteCatalog.ModelRoute("alpha", "alpha-model"),
            new RandomRouteCatalog.ModelRoute("beta", "beta-model"),
            new RandomRouteCatalog.ModelRoute("gamma", "gamma-model")));
        var accounts = mock(AccountSelectionService.class);
        when(accounts.acquire(anyString(), anyString(), any())).thenAnswer(invocation ->
            Mono.just(leased(invocation.getArgument(0))));
        var router = new RandomInferenceRouter(
            catalog,
            new ProviderRegistry(List.of(
                provider("alpha"), provider("beta"), provider("gamma"))),
            accounts,
            new CanonicalRequestParser(new ObjectMapper()),
            executor,
            runtimeGuard());
        var request = new ObjectMapper().createObjectNode();
        request.putArray("messages").addObject()
            .put("role", "user").put("content", "hello");

        var selected = Flux.range(0, 3)
            .flatMap(ignored -> router.select(
                CanonicalRequest.Protocol.CHAT_COMPLETIONS,
                request,
                RandomModelRole.TOP_MULTIMODAL))
            .map(selection -> selection.request().providerId())
            .collectList()
            .block();

        assertThat(selected).doesNotHaveDuplicates();
        assertThat(selected).containsExactlyInAnyOrder("alpha", "beta", "gamma");
    }

    @Test
    void restrictedKeyRemovesDisallowedRandomCandidatesBeforeAccountSelection() {
        var catalog = mock(RandomRouteCatalog.class);
        when(catalog.installedModels(RandomModelRole.TOP_TEXT)).thenReturn(List.of(
            new RandomRouteCatalog.ModelRoute("alpha", "allowed-model"),
            new RandomRouteCatalog.ModelRoute("beta", "blocked-model")));
        var accounts = mock(AccountSelectionService.class);
        when(accounts.acquire(eq("alpha"), eq("allowed-model"), any()))
            .thenReturn(Mono.just(leased("alpha")));
        var router = new RandomInferenceRouter(
            catalog,
            new ProviderRegistry(List.of(provider("alpha"), provider("beta"))),
            accounts,
            new CanonicalRequestParser(new ObjectMapper()),
            executor,
            runtimeGuard());
        var request = new ObjectMapper().createObjectNode();
        request.putArray("messages").addObject().put("role", "user").put("content", "hello");
        var grant = new ApiKeyGrant(
            UUID.randomUUID(), "client", Map.of(
                "alpha", ApiKeyProviderScope.selectedModels(
                    "alpha", java.util.Set.of("allowed-model"))),
            java.util.Set.of(ApiKeyProtocol.CHAT_COMPLETIONS), java.util.Set.of(), null, false);

        var selected = router.select(
            CanonicalRequest.Protocol.CHAT_COMPLETIONS, request,
            RandomModelRole.TOP_TEXT, grant).block();

        assertThat(selected).isNotNull();
        assertThat(selected.request().providerId()).isEqualTo("alpha");
        verify(accounts, never())
            .acquire(eq("beta"), eq("blocked-model"), any());
    }

    private LeasedProviderAccount leased(String providerId) {
        var accountId = UUID.randomUUID();
        return new LeasedProviderAccount(
            accountId,
            providerId,
            "external",
            null,
            1,
            null,
            JsonNodeFactory.instance.objectNode(),
            Map.of(),
            new AccountLease(
                providerId,
                accountId,
                UUID.randomUUID().toString(),
                1,
                Instant.now().plusSeconds(300)));
    }

    private ModelRuntimeGuard runtimeGuard() {
        var guard = mock(ModelRuntimeGuard.class);
        when(guard.callable(anyString(), anyString())).thenReturn(true);
        return guard;
    }

    private InferenceProvider provider(String id) {
        return new InferenceProvider() {
            @Override
            public ProviderManifest manifest() {
                return new ProviderManifest(
                    id, id, "test-v1", "1", List.of(), Map.of(
                        ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE,
                        ProviderCapability.RESPONSES, SupportLevel.NATIVE), true);
            }

            @Override
            public ProviderProtocolContract protocolContract() {
                return new ProviderProtocolContract(
                    java.util.Set.of("flag"), java.util.Set.of(), java.util.Set.of(),
                    java.util.Set.of());
            }

            @Override
            public void validate(CanonicalRequest request) {
                if ("bad-model".equals(request.model())) {
                    throw new IllegalArgumentException("unsupported test model");
                }
            }

            @Override
            public boolean supportsAccount(
                CanonicalRequest request,
                ProviderAccountProfile account
            ) {
                return true;
            }

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
        };
    }
}
