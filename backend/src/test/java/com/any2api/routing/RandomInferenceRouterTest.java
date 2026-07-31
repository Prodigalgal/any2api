package com.any2api.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.any2api.account.AccountEntity;
import com.any2api.account.AccountModelCooldownStore;
import com.any2api.account.AccountRepository;
import com.any2api.account.LeasedProviderAccount;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.protocol.CanonicalRequestParser;
import com.any2api.provider.InferenceProvider;
import com.any2api.provider.ProviderAccountProfile;
import com.any2api.provider.ProviderCapability;
import com.any2api.provider.ProviderExecutionContext;
import com.any2api.provider.ProviderFailure;
import com.any2api.provider.ProviderManifest;
import com.any2api.provider.ProviderRegistry;
import com.any2api.provider.RandomModelRole;
import com.any2api.provider.SupportLevel;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

class RandomInferenceRouterTest {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

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
        var accounts = mock(AccountRepository.class);
        var alphaAccount = AccountEntity.create(
            "alpha", "external", null, null, Map.of("tier", "basic"));
        when(accounts.findEligible(anyString(), any(), any())).thenAnswer(invocation ->
            "alpha".equals(invocation.getArgument(0))
                ? List.of(alphaAccount) : List.of());
        var cooldowns = mock(AccountModelCooldownStore.class);
        when(cooldowns.coolingAccounts(anyString(), anyString())).thenReturn(Set.of());
        var router = new RandomInferenceRouter(
            catalog,
            new ProviderRegistry(List.of(provider("alpha"), provider("beta"))),
            accounts,
            cooldowns,
            new CanonicalRequestParser(new ObjectMapper()),
            executor);
        var request = new ObjectMapper().createObjectNode();
        request.putArray("messages").addObject().put("role", "user").put("content", "hello");
        request.putObject("provider_options").putObject("alpha").put("flag", true);

        var selected = router.select(
            CanonicalRequest.Protocol.CHAT_COMPLETIONS, request,
            RandomModelRole.TOP_TEXT).block();

        assertThat(selected).isNotNull();
        assertThat(selected.providerId()).isEqualTo("alpha");
        assertThat(selected.model()).isEqualTo("good-model");
        assertThat(selected.rawRequest().path("model").asText()).isEqualTo("good-model");
        assertThat(selected.providerOptions()).containsEntry("flag", true);
        assertThat(request.has("model")).isFalse();
    }

    @Test
    void rejectsAConcreteModelOnTheRandomEndpoint() {
        var router = new RandomInferenceRouter(
            mock(RandomRouteCatalog.class),
            new ProviderRegistry(List.of(provider("alpha"))),
            mock(AccountRepository.class),
            mock(AccountModelCooldownStore.class),
            new CanonicalRequestParser(new ObjectMapper()),
            executor);
        var request = new ObjectMapper().createObjectNode().put("model", "alpha/model");

        assertThatThrownBy(() -> router.select(
                CanonicalRequest.Protocol.RESPONSES, request,
                RandomModelRole.TOP_TEXT).block())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("model=random");
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
