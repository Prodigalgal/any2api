package com.any2api.lifecycle;

import com.any2api.account.AccountRepository;
import com.any2api.account.LeasedProviderAccount;
import com.any2api.provider.ProviderCapability;
import com.any2api.provider.ProviderRegistry;
import com.any2api.provider.SupportLevel;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountRecoveryService {
    private final AccountRepository accounts;
    private final ProviderRegistry providers;
    private final LifecycleScheduleService schedules;
    private final List<AccountRecoveryPolicy> policies;

    public AccountRecoveryService(
        AccountRepository accounts,
        ProviderRegistry providers,
        LifecycleScheduleService schedules,
        List<AccountRecoveryPolicy> policies
    ) {
        this.accounts = accounts;
        this.providers = providers;
        this.schedules = schedules;
        this.policies = List.copyOf(policies);
    }

    @Transactional
    public boolean schedule(LeasedProviderAccount failedAccount) {
        return schedule(failedAccount, "credential_rejected", true);
    }

    @Transactional
    public boolean schedulePolicyRecovery(
        LeasedProviderAccount failedAccount,
        String failureType
    ) {
        return schedule(failedAccount, failureType, false);
    }

    private boolean schedule(
        LeasedProviderAccount failedAccount,
        String failureType,
        boolean allowDirectReauthentication
    ) {
        var provider = providers.require(failedAccount.providerId());
        var reauthentication = provider.manifest().capabilities().getOrDefault(
            ProviderCapability.REAUTHENTICATION, SupportLevel.UNSUPPORTED);
        if (allowDirectReauthentication && reauthentication != SupportLevel.UNSUPPORTED) {
            schedules.scheduleReauthentication(
                failedAccount.accountId(), failedAccount.providerId());
            return true;
        }
        var target = policies.stream()
            .map(policy -> policy.resolve(failedAccount, failureType))
            .flatMap(java.util.Optional::stream)
            .findFirst();
        if (target.isEmpty()) return false;
        var recovery = target.get();
        var account = accounts.findById(recovery.accountId())
            .orElseThrow(() -> new IllegalStateException(
                "credential recovery target no longer exists"));
        if (!account.getProviderId().equals(recovery.providerId())) {
            throw new IllegalStateException("credential recovery target provider mismatch");
        }
        if (!recovery.metadataPatch().isEmpty()) {
            account.mergeMetadata(recovery.metadataPatch());
            accounts.save(account);
        }
        schedules.scheduleReauthentication(account.getId(), account.getProviderId());
        return true;
    }
}
