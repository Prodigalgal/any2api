package com.any2api.provider.xai_identity;

import com.any2api.account.AccountRepository;
import com.any2api.account.LeasedProviderAccount;
import com.any2api.lifecycle.AccountRecoveryPolicy;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class XaiAccountRecoveryPolicy implements AccountRecoveryPolicy {
    private static final String SOURCE_PROVIDER = "grok";
    private static final Set<String> DERIVED_PROVIDERS = Set.of("grok_web", "grok_console");
    private static final Set<String> RECOVERABLE_FAILURES = Set.of(
        "credential_rejected", "permission_or_egress_denied");

    private final AccountRepository accounts;

    public XaiAccountRecoveryPolicy(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public Optional<RecoveryTarget> resolve(
        LeasedProviderAccount failedAccount,
        String failureType
    ) {
        if (!DERIVED_PROVIDERS.contains(failedAccount.providerId())) return Optional.empty();
        if (!RECOVERABLE_FAILURES.contains(failureType)) return Optional.empty();
        var identityGroup = String.valueOf(
            failedAccount.metadata().getOrDefault("identity_group_id", "")).trim();
        if (identityGroup.isBlank()) return Optional.empty();
        return accounts.findByProviderIdAndIdentityGroup(SOURCE_PROVIDER, identityGroup)
            .map(source -> new RecoveryTarget(
                source.getId(), SOURCE_PROVIDER, Map.of(
                    "xai_force_sso_refresh", true,
                    "xai_recovery_source", failedAccount.providerId())));
    }
}
