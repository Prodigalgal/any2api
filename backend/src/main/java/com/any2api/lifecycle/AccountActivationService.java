package com.any2api.lifecycle;

import com.any2api.account.AccountRepository;
import com.any2api.account.AccountStatus;
import com.any2api.provider.ProviderCapability;
import com.any2api.provider.ProviderRegistry;
import com.any2api.provider.SupportLevel;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountActivationService {
    public static final Duration DEFAULT_SPREAD = Duration.ofMinutes(5);
    private static final Duration MIN_SPREAD = Duration.ofSeconds(1);
    private static final Duration MAX_SPREAD = Duration.ofDays(7);

    private final AccountRepository accounts;
    private final ProviderRegistry providers;
    private final LifecycleScheduleService schedules;

    public AccountActivationService(
        AccountRepository accounts,
        ProviderRegistry providers,
        LifecycleScheduleService schedules
    ) {
        this.accounts = accounts;
        this.providers = providers;
        this.schedules = schedules;
    }

    @Transactional
    public Result activate(UUID accountId, Duration spread) {
        validateSpread(spread);
        var account = accounts.findById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("unknown account: " + accountId));
        if (account.getStatus() == AccountStatus.BANNED) {
            throw new IllegalArgumentException("banned account cannot be activated");
        }
        var provider = providers.require(account.getProviderId());
        if (!provider.manifest().configured()) {
            throw new IllegalArgumentException(
                "provider is not configured: " + account.getProviderId());
        }
        var capabilities = provider.manifest().capabilities();
        var reauthentication = capabilities.getOrDefault(
            ProviderCapability.REAUTHENTICATION, SupportLevel.UNSUPPORTED);
        var keepalive = capabilities.getOrDefault(
            ProviderCapability.ACCOUNT_KEEPALIVE, SupportLevel.UNSUPPORTED);

        if (requiresReauthentication(account.getStatus(), account.isEnabled())
            && reauthentication != SupportLevel.UNSUPPORTED) {
            schedules.scheduleReauthentication(accountId, account.getProviderId(), spread);
            return new Result(accountId, account.getProviderId(), Action.REAUTHENTICATE,
                spread.toSeconds());
        }
        if (keepalive != SupportLevel.UNSUPPORTED) {
            schedules.scheduleInitialProbe(accountId, account.getProviderId(), spread);
            return new Result(accountId, account.getProviderId(), Action.PROBE,
                spread.toSeconds());
        }
        if (reauthentication != SupportLevel.UNSUPPORTED) {
            schedules.scheduleReauthentication(accountId, account.getProviderId(), spread);
            return new Result(accountId, account.getProviderId(), Action.REAUTHENTICATE,
                spread.toSeconds());
        }
        throw new IllegalArgumentException(
            "provider does not support account activation: " + account.getProviderId());
    }

    private static boolean requiresReauthentication(AccountStatus status, boolean enabled) {
        return !enabled || status == AccountStatus.PENDING || status == AccountStatus.EXPIRED;
    }

    private static void validateSpread(Duration spread) {
        if (spread == null || spread.compareTo(MIN_SPREAD) < 0
            || spread.compareTo(MAX_SPREAD) > 0) {
            throw new IllegalArgumentException(
                "activation spread must be between 1 second and 7 days");
        }
    }

    public enum Action {
        PROBE,
        REAUTHENTICATE
    }

    public record Result(
        UUID accountId,
        String providerId,
        Action action,
        long spreadSeconds
    ) {}
}
