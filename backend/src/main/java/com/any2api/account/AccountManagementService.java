package com.any2api.account;

import com.any2api.credential.CredentialVault;
import com.any2api.provider.ProviderRegistry;
import com.any2api.provider.ProviderCapability;
import com.any2api.provider.SupportLevel;
import com.any2api.lifecycle.LifecycleScheduleService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import tools.jackson.databind.JsonNode;

@Service
public class AccountManagementService {
    private final AccountRepository accounts;
    private final CredentialVault credentials;
    private final ProviderRegistry providers;
    private final LifecycleScheduleService schedules;
    private final List<AccountDerivationPolicy> derivationPolicies;

    public AccountManagementService(
        AccountRepository accounts,
        CredentialVault credentials,
        ProviderRegistry providers,
        LifecycleScheduleService schedules,
        List<AccountDerivationPolicy> derivationPolicies
    ) {
        this.accounts = accounts;
        this.credentials = credentials;
        this.providers = providers;
        this.schedules = schedules;
        this.derivationPolicies = List.copyOf(derivationPolicies);
    }

    @Transactional(readOnly = true)
    public AccountPageView search(AccountSearchQuery query) {
        if (query.providerId() != null) providers.requirePlugin(query.providerId());
        var pageable = PageRequest.of(query.page(), query.size(),
            Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        return AccountPageView.from(accounts.findAll(
            AccountSpecifications.matching(query, Instant.now()), pageable));
    }

    @Transactional
    public ImportResult importAccount(ImportCommand command) {
        return importAccount(command, false);
    }

    @Transactional
    public ImportResult importNewAccount(ImportCommand command) {
        return importAccount(command, true);
    }

    private ImportResult importAccount(ImportCommand command, boolean requireNewSource) {
        if (requireNewSource && accounts.findByProviderIdAndExternalId(
            command.providerId(), command.externalId()).isPresent()) {
            throw new DuplicateAccountException(command.providerId(), command.externalId());
        }
        var sourceMetadata = new LinkedHashMap<>(command.metadata());
        var derivedAccounts = new ArrayList<AccountDerivationPolicy.DerivedAccount>();
        var seed = new AccountDerivationPolicy.AccountSeed(
            command.providerId(), command.externalId(), command.email(), command.expiresAt(),
            command.credentialExpiresAt(), command.metadata(), command.credential());
        for (var policy : derivationPolicies) {
            var plan = policy.derive(seed);
            sourceMetadata.putAll(plan.sourceMetadata());
            derivedAccounts.addAll(plan.accounts());
        }
        var source = importOne(command.withMetadata(sourceMetadata), false);
        for (var derived : derivedAccounts) {
            importOne(new ImportCommand(
                derived.providerId(), derived.externalId(), command.email(), command.expiresAt(),
                command.credentialExpiresAt(), derived.metadata(), null, null, null,
                AccountStatus.PENDING, false, derived.credential(),
                command.scheduleLifecycle()), true);
        }
        return source;
    }

    private ImportResult importOne(ImportCommand command, boolean preserveExistingState) {
        var provider = providers.require(command.providerId());
        provider.validateCredential(command.credential());
        var existing = accounts.findByProviderIdAndExternalId(
            command.providerId(), command.externalId());
        var account = existing.orElseGet(() -> AccountEntity.create(
                command.providerId(), command.externalId(), command.email(), command.expiresAt(),
                command.metadata()));
        var metadata = new LinkedHashMap<String, Object>();
        if (preserveExistingState && existing.isPresent()) metadata.putAll(account.getMetadata());
        metadata.putAll(command.metadata());
        account.updateProfile(command.email(), command.expiresAt(), metadata,
            command.priority(), command.weight(), command.maxConcurrency());
        if (!preserveExistingState || existing.isEmpty()) {
            account.updateState(command.status(), command.enabled());
        }
        account = accounts.save(account);
        var credential = credentials.store(
            account, command.providerId(), command.credential(), command.credentialExpiresAt());
        if (!command.scheduleLifecycle()) {
            return new ImportResult(
                AccountView.from(account), credential.version(), credential.expiresAt());
        }
        var capabilities = provider.manifest().capabilities();
        var inferenceReadinessPending = Boolean.TRUE.equals(
            command.metadata().get("inference_readiness_pending"));
        if (account.getStatus() == AccountStatus.PENDING && inferenceReadinessPending
            && capabilities.getOrDefault(
                ProviderCapability.ACCOUNT_KEEPALIVE, SupportLevel.UNSUPPORTED)
                != SupportLevel.UNSUPPORTED) {
            schedules.scheduleInitialProbe(account.getId(), command.providerId());
        } else if (account.getStatus() == AccountStatus.PENDING
            && capabilities.getOrDefault(
                ProviderCapability.REAUTHENTICATION, SupportLevel.UNSUPPORTED)
                != SupportLevel.UNSUPPORTED) {
            schedules.scheduleReauthentication(account.getId(), command.providerId());
        } else if (capabilities.getOrDefault(
            ProviderCapability.ACCOUNT_KEEPALIVE, SupportLevel.UNSUPPORTED)
            != SupportLevel.UNSUPPORTED) {
            schedules.scheduleInitialProbe(account.getId(), command.providerId());
        }
        return new ImportResult(AccountView.from(account), credential.version(), credential.expiresAt());
    }

    @Transactional
    public AccountView updateState(UUID accountId, StateCommand command) {
        var account = require(accountId);
        if (Boolean.TRUE.equals(command.enabled())) {
            providers.require(account.getProviderId());
        }
        account.updateState(command.status(), command.enabled());
        return AccountView.from(accounts.save(account));
    }

    @Transactional
    public void delete(UUID accountId) {
        accounts.delete(require(accountId));
    }

    @Transactional
    public AccountView reauthenticate(UUID accountId) {
        var account = require(accountId);
        var provider = providers.require(account.getProviderId());
        if (provider.manifest().capabilities().getOrDefault(
            ProviderCapability.REAUTHENTICATION, SupportLevel.UNSUPPORTED)
            == SupportLevel.UNSUPPORTED) {
            throw new IllegalArgumentException("provider does not support reauthentication");
        }
        schedules.scheduleReauthentication(accountId, account.getProviderId());
        return AccountView.from(account);
    }

    @Transactional
    public AccountView scheduleProbe(UUID accountId, java.time.Duration spread) {
        if (spread == null || spread.isNegative() || spread.isZero()
            || spread.compareTo(java.time.Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("probe spread must be between 1 second and 7 days");
        }
        var account = require(accountId);
        providers.require(account.getProviderId());
        account.updateState(AccountStatus.PENDING, false);
        account = accounts.save(account);
        schedules.rescheduleProbe(account.getId(), account.getProviderId(), spread);
        return AccountView.from(account);
    }

    private AccountEntity require(UUID accountId) {
        return accounts.findById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("unknown account: " + accountId));
    }

    public record ImportCommand(
        String providerId,
        String externalId,
        String email,
        Instant expiresAt,
        Instant credentialExpiresAt,
        Map<String, Object> metadata,
        Integer priority,
        Integer weight,
        Integer maxConcurrency,
        AccountStatus status,
        Boolean enabled,
        JsonNode credential,
        Boolean scheduleLifecycle
    ) {
        public ImportCommand {
            if (providerId == null || providerId.isBlank()) {
                throw new IllegalArgumentException("provider_id is required");
            }
            if (externalId == null || externalId.isBlank()) {
                throw new IllegalArgumentException("external_id is required");
            }
            if (credential == null || !credential.isObject()) {
                throw new IllegalArgumentException("credential must be a JSON object");
            }
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            scheduleLifecycle = scheduleLifecycle == null || scheduleLifecycle;
        }

        ImportCommand withMetadata(Map<String, Object> value) {
            return new ImportCommand(providerId, externalId, email, expiresAt,
                credentialExpiresAt, value, priority, weight, maxConcurrency,
                status, enabled, credential, scheduleLifecycle);
        }
    }

    public record StateCommand(AccountStatus status, Boolean enabled) {
    }

    public record ImportResult(AccountView account, long credentialVersion, Instant credentialExpiresAt) {
    }
}
