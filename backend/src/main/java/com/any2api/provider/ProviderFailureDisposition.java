package com.any2api.provider;

import com.any2api.account.AccountSelectionService;
import com.any2api.account.LeasedProviderAccount;
import com.any2api.lifecycle.AccountRecoveryService;
import java.time.Duration;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public final class ProviderFailureDisposition {
    private final AccountSelectionService accounts;
    private final AccountRecoveryService recoveries;

    public ProviderFailureDisposition(
        AccountSelectionService accounts,
        AccountRecoveryService recoveries
    ) {
        this.accounts = accounts;
        this.recoveries = recoveries;
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
            case "credential_rejected" -> accounts.reportAuthenticationFailure(
                    account, failure.message())
                .then(Mono.<Void>fromRunnable(() -> recoveries.schedule(account))
                    .subscribeOn(Schedulers.boundedElastic()));
            case "account_blocked" -> accounts.reportFailure(
                account, failure.message(), Duration.ofHours(6));
            case "anti_bot_rejected" -> accounts.reportFailure(
                account, failure.message(), Duration.ofMinutes(5));
            default -> Mono.empty();
        };
    }
}
