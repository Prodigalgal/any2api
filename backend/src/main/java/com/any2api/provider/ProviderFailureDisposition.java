package com.any2api.provider;

import com.any2api.account.AccountSelectionService;
import com.any2api.account.LeasedProviderAccount;
import java.time.Duration;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public final class ProviderFailureDisposition {
    private final AccountSelectionService accounts;

    public ProviderFailureDisposition(AccountSelectionService accounts) {
        this.accounts = accounts;
    }

    public Mono<Void> report(
        LeasedProviderAccount account,
        String modelId,
        ProviderFailure failure
    ) {
        return switch (failure.type()) {
            case "rate_limited", "quota_exhausted" -> accounts.reportModelCooldown(
                account, modelId, failure.message(), Duration.ofMinutes(5));
            case "empty_model_response" -> accounts.reportModelCooldown(
                account, modelId, failure.message(), Duration.ofMinutes(5));
            case "credential_rejected", "account_blocked" -> accounts.reportFailure(
                account, failure.message(), Duration.ofHours(6));
            default -> Mono.empty();
        };
    }
}
