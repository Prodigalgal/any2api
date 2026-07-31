package com.any2api.routing;

import com.any2api.account.AccountModelCooldownStore;
import com.any2api.account.AccountRepository;
import com.any2api.account.AccountStatus;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.protocol.CanonicalRequestParser;
import com.any2api.provider.ProviderAccountProfile;
import com.any2api.provider.ProviderCapability;
import com.any2api.provider.ProviderRegistry;
import com.any2api.provider.ProviderRequestValidation;
import com.any2api.provider.RandomModelRole;
import com.any2api.provider.SupportLevel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.node.ObjectNode;

@Component
public class RandomInferenceRouter {
    private final RandomRouteCatalog catalog;
    private final ProviderRegistry providers;
    private final AccountRepository accounts;
    private final AccountModelCooldownStore modelCooldowns;
    private final CanonicalRequestParser parser;
    private final ExecutorService databaseExecutor;

    public RandomInferenceRouter(
        RandomRouteCatalog catalog,
        ProviderRegistry providers,
        AccountRepository accounts,
        AccountModelCooldownStore modelCooldowns,
        CanonicalRequestParser parser,
        ExecutorService databaseExecutor
    ) {
        this.catalog = catalog;
        this.providers = providers;
        this.accounts = accounts;
        this.modelCooldowns = modelCooldowns;
        this.parser = parser;
        this.databaseExecutor = databaseExecutor;
    }

    public Mono<CanonicalRequest> select(
        CanonicalRequest.Protocol protocol,
        ObjectNode request,
        RandomModelRole role
    ) {
        return Mono.fromCallable(() -> selectBlocking(protocol, request, role))
            .subscribeOn(Schedulers.fromExecutor(databaseExecutor));
    }

    private CanonicalRequest selectBlocking(
        CanonicalRequest.Protocol protocol,
        ObjectNode request,
        RandomModelRole role
    ) {
        requireRandomModel(request);
        var capability = protocol == CanonicalRequest.Protocol.CHAT_COMPLETIONS
            ? ProviderCapability.CHAT_COMPLETIONS
            : ProviderCapability.RESPONSES;
        var byProvider = new LinkedHashMap<String, List<CanonicalRequest>>();
        var eligibleAccounts = new LinkedHashMap<String, List<com.any2api.account.AccountEntity>>();
        for (var route : catalog.installedModels(role)) {
            var provider = providers.require(route.providerId());
            if (provider.manifest().capabilities()
                .getOrDefault(capability, SupportLevel.UNSUPPORTED)
                == SupportLevel.UNSUPPORTED) {
                continue;
            }
            var providerAccounts = eligibleAccounts.computeIfAbsent(
                route.providerId(), ignored -> accounts.findEligible(
                    route.providerId(),
                    List.of(AccountStatus.ACTIVE, AccountStatus.DEGRADED),
                    Instant.now()));
            if (providerAccounts.isEmpty()) continue;

            var raw = request.deepCopy();
            raw.put("model", route.modelId());
            var canonical = parser.parse(
                protocol, new ResolvedRoute(route.providerId(), route.modelId()), raw);
            try {
                ProviderRequestValidation.requireSupportedContent(
                    canonical, provider.manifest());
                provider.validate(canonical);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            var cooling = modelCooldowns.coolingAccounts(
                route.providerId(), route.modelId());
            var supported = providerAccounts.stream()
                .filter(account -> !cooling.contains(account.getId()))
                .map(account -> new ProviderAccountProfile(
                    account.getId(), account.getMetadata()))
                .anyMatch(account -> provider.supportsAccount(canonical, account));
            if (supported) {
                byProvider.computeIfAbsent(route.providerId(), ignored -> new ArrayList<>())
                    .add(canonical);
            }
        }
        if (byProvider.isEmpty()) {
            throw new IllegalStateException(
                "no random provider/model can serve this request");
        }
        var providerIds = new ArrayList<>(byProvider.keySet());
        Collections.shuffle(providerIds, ThreadLocalRandom.current());
        var models = new ArrayList<>(byProvider.get(providerIds.getFirst()));
        Collections.shuffle(models, ThreadLocalRandom.current());
        return models.getFirst();
    }

    private void requireRandomModel(ObjectNode request) {
        var value = request.path("model").asText("").trim();
        if (!value.isBlank() && !"random".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(
                "random endpoints accept only model=random or an omitted model");
        }
    }
}
