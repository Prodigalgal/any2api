package com.any2api.provider;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    @Test
    void doesNotReportSuccessWhenAProviderEmitsCanonicalFailure() {
        var accounts = mock(AccountSelectionService.class);
        var leased = leased("alpha");
        when(accounts.release(leased)).thenReturn(Mono.just(true));
        when(accounts.mergeCredentialPatch(eq(leased), any(tools.jackson.databind.JsonNode.class)))
            .thenReturn(Mono.just(true));
        var provider = new TestProvider(false, new CanonicalEvent.Failed(
            1, "request-id", 1, "tool_call_generation_failed", "missing tool", Map.of()));
        var coordinator = new InferenceCoordinator(
            new ProviderRegistry(List.of(provider)), accounts,
            new ProviderFailureDisposition(accounts));

        StepVerifier.create(coordinator.execute(request("alpha"), leased))
            .expectNextMatches(CanonicalEvent.Failed.class::isInstance)
            .verifyComplete();

        verify(accounts, never()).reportSuccess(leased, "model");
        verify(accounts).release(leased);
    }

    private CanonicalRequest request(String providerId) {
        var message = JsonNodeFactory.instance.objectNode()
            .put("role", "user").put("content", "hello");
        return new CanonicalRequest(
            "request-id",
            CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            providerId,
            "model",
            false,
            List.of(message),
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
        private final CanonicalEvent event;

        private TestProvider(boolean reject) {
            this(reject, null);
        }

        private TestProvider(boolean reject, CanonicalEvent event) {
            this.reject = reject;
            this.event = event;
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
            return event == null ? Flux.empty() : Flux.just(event);
        }

        @Override
        public ProviderFailure classify(Throwable error) {
            return new ProviderFailure("test", error.getMessage(), false, Map.of());
        }
    }
}
