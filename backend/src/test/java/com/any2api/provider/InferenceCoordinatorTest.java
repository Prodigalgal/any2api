package com.any2api.provider;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.any2api.account.AccountSelectionService;
import com.any2api.account.LeasedProviderAccount;
import com.any2api.coordination.AccountLease;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.node.JsonNodeFactory;

class InferenceCoordinatorTest {

    @Test
    void releasesPreleasedRandomAccountWhenProviderValidationFails() {
        var accounts = mock(AccountSelectionService.class);
        var leased = leased("alpha");
        when(accounts.release(leased)).thenReturn(Mono.just(true));
        var provider = new TestProvider(true);
        var coordinator = new InferenceCoordinator(
            new ProviderRegistry(List.of(provider)),
            accounts,
            new ProviderFailureDisposition(accounts));

        StepVerifier.create(coordinator.execute(request("alpha"), leased))
            .expectErrorMatches(error -> error instanceof IllegalArgumentException
                && error.getMessage().contains("rejected"))
            .verify();

        verify(accounts).release(leased);
    }

    private CanonicalRequest request(String providerId) {
        return new CanonicalRequest(
            "request-id",
            CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            providerId,
            "model",
            false,
            List.of(),
            Map.of(),
            Map.of(),
            List.of(),
            Map.of(),
            JsonNodeFactory.instance.objectNode());
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

    private static final class TestProvider implements InferenceProvider {
        private final boolean reject;

        private TestProvider(boolean reject) {
            this.reject = reject;
        }

        @Override
        public ProviderManifest manifest() {
            return new ProviderManifest(
                "alpha",
                "Alpha",
                "test",
                "1",
                List.of(),
                Map.of(
                    ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE,
                    ProviderCapability.RESPONSES, SupportLevel.NATIVE),
                true);
        }

        @Override
        public void validate(CanonicalRequest request) {
            if (reject) {
                throw new IllegalArgumentException("request rejected for test");
            }
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
            return new ProviderFailure("test", error.getMessage(), false, Map.of());
        }
    }
}
