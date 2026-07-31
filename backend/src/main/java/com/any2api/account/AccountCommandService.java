package com.any2api.account;

import com.any2api.credential.CredentialVault;
import com.any2api.provider.ProviderAccountCommandHandler;
import com.any2api.provider.ProviderAccountCommandRegistry;
import com.any2api.provider.ProviderRegistry;
import com.any2api.proxy.ProxyPoolService;
import com.any2api.proxy.ProxyTrafficScope;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public final class AccountCommandService {
    private static final TypeReference<Map<String, Object>> METADATA_TYPE =
        new TypeReference<>() {};
    private final AccountRepository accounts;
    private final CredentialVault credentials;
    private final ProviderAccountCommandRegistry commands;
    private final ProviderRegistry providers;
    private final ProxyPoolService proxyPools;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public AccountCommandService(
        AccountRepository accounts,
        CredentialVault credentials,
        ProviderAccountCommandRegistry commands,
        ProviderRegistry providers,
        ProxyPoolService proxyPools,
        ObjectMapper mapper,
        PlatformTransactionManager transactionManager
    ) {
        this.accounts = accounts;
        this.credentials = credentials;
        this.commands = commands;
        this.providers = providers;
        this.proxyPools = proxyPools;
        this.mapper = mapper;
        transactions = new TransactionTemplate(transactionManager);
    }

    public List<ProviderAccountCommandHandler.CommandDescriptor> commands(UUID accountId) {
        var account = accounts.findById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("unknown account: " + accountId));
        providers.require(account.getProviderId());
        return commands.commandsFor(account.getProviderId());
    }

    public Mono<ExecutionResult> execute(UUID accountId, String command) {
        var snapshot = Objects.requireNonNull(transactions.execute(ignored -> snapshot(accountId)));
        var handler = commands.require(snapshot.providerId(), command);
        var proxyPool = proxyPools.runtimeForProvider(
            snapshot.providerId(), ProxyTrafficScope.LIFECYCLE).orElse(Map.of());
        return handler.execute(command, new ProviderAccountCommandHandler.CommandContext(
                accountId, snapshot.metadata(), snapshot.credential(), proxyPool))
            .map(result -> Objects.requireNonNull(transactions.execute(ignored ->
                persist(snapshot, result))));
    }

    private Snapshot snapshot(UUID accountId) {
        var account = accounts.findById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("unknown account: " + accountId));
        providers.require(account.getProviderId());
        var credential = credentials.read(account, account.getProviderId());
        return new Snapshot(account.getId(), account.getProviderId(), account.getMetadata(),
            account.getVersion(), credential.payload(), credential.version(), credential.expiresAt());
    }

    private ExecutionResult persist(
        Snapshot snapshot,
        ProviderAccountCommandHandler.CommandResult result
    ) {
        var account = accounts.findById(snapshot.accountId())
            .orElseThrow(() -> new IllegalArgumentException(
                "unknown account: " + snapshot.accountId()));
        if (account.getVersion() != snapshot.accountVersion()) {
            throw new IllegalStateException(
                "account changed while the provider command was running");
        }
        if (result.metadataPatch() != null && result.metadataPatch().isObject()) {
            account.mergeMetadata(mapper.convertValue(result.metadataPatch(), METADATA_TYPE));
        }
        if (result.credentialPatch() != null && result.credentialPatch().isObject()
            && !result.credentialPatch().isEmpty()) {
            var merged = snapshot.credential().deepCopy();
            result.credentialPatch().properties().forEach(entry ->
                ((tools.jackson.databind.node.ObjectNode) merged).set(
                    entry.getKey(), entry.getValue()));
            credentials.storeIfVersion(account, snapshot.providerId(), snapshot.credentialVersion(),
                merged, snapshot.credentialExpiresAt());
        }
        return new ExecutionResult(AccountView.from(accounts.save(account)), result.metadataPatch());
    }

    public record ExecutionResult(AccountView account, JsonNode result) {}

    private record Snapshot(
        UUID accountId,
        String providerId,
        Map<String, Object> metadata,
        long accountVersion,
        JsonNode credential,
        long credentialVersion,
        java.time.Instant credentialExpiresAt
    ) {}
}
