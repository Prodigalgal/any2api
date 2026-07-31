package com.any2api.account;

import com.any2api.coordination.AccountCapacityException;
import com.any2api.coordination.AccountLeaseService;
import com.any2api.credential.CredentialVault;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Predicate;
import com.any2api.provider.ProviderAccountProfile;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class AccountSelectionService {

    private static final Duration DEFAULT_LEASE_TTL = Duration.ofMinutes(5);

    private final AccountRepository accounts;
    private final CredentialVault credentials;
    private final AccountLeaseService leases;
    private final AccountModelCooldownStore modelCooldowns;
    private final ExecutorService databaseExecutor;

    public AccountSelectionService(
        AccountRepository accounts,
        CredentialVault credentials,
        AccountLeaseService leases,
        AccountModelCooldownStore modelCooldowns,
        ExecutorService databaseExecutor
    ) {
        this.accounts = accounts;
        this.credentials = credentials;
        this.leases = leases;
        this.modelCooldowns = modelCooldowns;
        this.databaseExecutor = databaseExecutor;
    }

    public Mono<LeasedProviderAccount> acquire(String providerId) {
        return acquire(providerId, ignored -> true);
    }

    public Mono<LeasedProviderAccount> acquire(
        String providerId,
        Predicate<ProviderAccountProfile> eligibility
    ) {
        return acquire(providerId, "", eligibility);
    }

    public Mono<LeasedProviderAccount> acquire(
        String providerId,
        String modelId,
        Predicate<ProviderAccountProfile> eligibility
    ) {
        return Mono.fromCallable(() -> {
                var cooling = modelId == null || modelId.isBlank()
                    ? java.util.Set.<java.util.UUID>of()
                    : modelCooldowns.coolingAccounts(providerId, modelId);
                return accounts.findEligible(
                        providerId,
                        List.of(AccountStatus.ACTIVE, AccountStatus.DEGRADED),
                        Instant.now()).stream()
                    .filter(account -> !cooling.contains(account.getId()))
                    .toList();
            })
            .subscribeOn(Schedulers.fromExecutor(databaseExecutor))
            .flatMapMany(Flux::fromIterable)
            .filter(account -> eligibility.test(new ProviderAccountProfile(
                account.getId(), account.getMetadata())))
            .concatMap(account -> acquire(account, providerId)
                .onErrorResume(AccountCapacityException.class, ignored -> Mono.empty()))
            .next()
            .switchIfEmpty(Mono.error(new AccountUnavailableException(providerId)));
    }

    public Mono<Boolean> release(LeasedProviderAccount account) {
        return leases.release(account.lease());
    }

    public Mono<Boolean> renew(LeasedProviderAccount account) {
        return leases.renew(account.lease(), DEFAULT_LEASE_TTL);
    }

    public Mono<Void> reportSuccess(LeasedProviderAccount account) {
        return Mono.fromRunnable(() -> accounts.markSuccess(account.accountId(), Instant.now()))
            .subscribeOn(Schedulers.fromExecutor(databaseExecutor))
            .then();
    }

    public Mono<Void> reportSuccess(LeasedProviderAccount account, String modelId) {
        return reportSuccess(account).then(Mono.fromRunnable(() ->
                modelCooldowns.clear(account.accountId(), account.providerId(), modelId))
            .subscribeOn(Schedulers.fromExecutor(databaseExecutor)).then());
    }

    public Mono<Void> reportModelCooldown(
        LeasedProviderAccount account,
        String modelId,
        String error,
        Duration cooldown
    ) {
        return Mono.fromRunnable(() -> modelCooldowns.cooldown(
                account.accountId(), account.providerId(), modelId, error, cooldown))
            .subscribeOn(Schedulers.fromExecutor(databaseExecutor))
            .then();
    }

    public Mono<Boolean> mergeCredentialPatch(
        LeasedProviderAccount account,
        JsonNode patch
    ) {
        if (patch == null || !patch.isObject() || patch.isEmpty()) {
            return Mono.just(false);
        }
        return Mono.fromCallable(() -> mergeCredentialPatchBlocking(account, patch))
            .subscribeOn(Schedulers.fromExecutor(databaseExecutor));
    }

    public Mono<Void> reportFailure(
        LeasedProviderAccount account,
        String error,
        Duration cooldown
    ) {
        var summary = error == null ? "provider request failed" : error.substring(0, Math.min(4000, error.length()));
        var now = Instant.now();
        return Mono.fromRunnable(() -> accounts.markFailure(
                account.accountId(),
                now,
                summary,
                now.plus(cooldown)))
            .subscribeOn(Schedulers.fromExecutor(databaseExecutor))
            .then();
    }

    private Mono<LeasedProviderAccount> acquire(AccountEntity account, String providerId) {
        return leases.acquire(providerId, account.getId(), account.getMaxConcurrency(), DEFAULT_LEASE_TTL)
            .flatMap(lease -> Mono.fromCallable(() -> {
                    var credential = credentials.read(account, providerId);
                    accounts.markUsed(account.getId(), Instant.now());
                    return new LeasedProviderAccount(
                        account.getId(),
                        providerId,
                        account.getExternalId(),
                        account.getEmail(),
                        credential.version(),
                        credential.expiresAt(),
                        credential.payload(),
                        account.getMetadata(),
                        lease);
                })
                .subscribeOn(Schedulers.fromExecutor(databaseExecutor))
                .onErrorResume(error -> leases.release(lease).then(Mono.error(error))));
    }

    private boolean mergeCredentialPatchBlocking(
        LeasedProviderAccount leased,
        JsonNode patch
    ) {
        for (var attempt = 0; attempt < 3; attempt++) {
            var account = accounts.findById(leased.accountId())
                .orElseThrow(() -> new IllegalStateException(
                    "provider account no longer exists"));
            var current = credentials.read(account, leased.providerId());
            if (!current.payload().isObject()) {
                throw new IllegalStateException(
                    "provider credential payload must be an object");
            }
            var merged = (ObjectNode) current.payload().deepCopy();
            patch.properties().forEach(entry ->
                merged.set(entry.getKey(), entry.getValue().deepCopy()));
            try {
                credentials.storeIfVersion(
                    account, leased.providerId(), current.version(),
                    merged, current.expiresAt());
                return true;
            } catch (IllegalStateException error) {
                if (!String.valueOf(error.getMessage()).contains("credential changed")
                    || attempt == 2) {
                    throw error;
                }
            }
        }
        return false;
    }
}
