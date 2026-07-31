package com.any2api.provider;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.any2api.account.AccountSelectionService;
import com.any2api.account.LeasedProviderAccount;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class ProviderFailureDispositionTest {
    @Test
    void rateLimitCoolsOnlyTheRequestedModel() {
        var accounts = mock(AccountSelectionService.class);
        var account = mock(LeasedProviderAccount.class);
        when(accounts.reportModelCooldown(
            account, "model-a", "limited", Duration.ofMinutes(5)))
            .thenReturn(Mono.empty());

        new ProviderFailureDisposition(accounts).report(
            account, "model-a",
            new ProviderFailure("rate_limited", "limited", true, Map.of())).block();

        verify(accounts).reportModelCooldown(
            account, "model-a", "limited", Duration.ofMinutes(5));
        verify(accounts, never()).reportFailure(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    }

    @Test
    void ambiguousAntiBotFailureDoesNotPenalizeTheAccount() {
        var accounts = mock(AccountSelectionService.class);
        var account = mock(LeasedProviderAccount.class);

        new ProviderFailureDisposition(accounts).report(
            account, "model-a",
            new ProviderFailure("anti_bot_rejected", "code 7", true, Map.of())).block();

        verify(accounts, never()).reportFailure(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
        verify(accounts, never()).reportModelCooldown(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void emptyModelOutputTemporarilyCoolsTheAffectedModel() {
        var accounts = mock(AccountSelectionService.class);
        var account = mock(LeasedProviderAccount.class);
        when(accounts.reportModelCooldown(account, "model-a", "empty", Duration.ofMinutes(5)))
            .thenReturn(Mono.empty());

        new ProviderFailureDisposition(accounts).report(
            account, "model-a",
            new ProviderFailure("empty_model_response", "empty", false, Map.of())).block();

        verify(accounts).reportModelCooldown(
            account, "model-a", "empty", Duration.ofMinutes(5));
        verify(accounts, never()).reportFailure(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    }
}
