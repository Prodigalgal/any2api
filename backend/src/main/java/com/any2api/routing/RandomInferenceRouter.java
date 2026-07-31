package com.any2api.routing;

import com.any2api.account.AccountSelectionService;
import com.any2api.account.AccountUnavailableException;
import com.any2api.account.LeasedProviderAccount;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.protocol.CanonicalRequestParser;
import com.any2api.provider.ProviderCapability;
import com.any2api.provider.ProviderRegistry;
import com.any2api.provider.ProviderRequestValidation;
import com.any2api.provider.RandomModelRole;
import com.any2api.provider.SupportLevel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.node.ObjectNode;

@Component
public class RandomInferenceRouter {
    private final RandomRouteCatalog catalog;
    private final ProviderRegistry providers;
    private final AccountSelectionService accounts;
    private final CanonicalRequestParser parser;
    private final ExecutorService databaseExecutor;
    private final Object shuffleLock = new Object();
    private final Map<String, ArrayDeque<String>> providerBags = new HashMap<>();

    public RandomInferenceRouter(
        RandomRouteCatalog catalog,
        ProviderRegistry providers,
        AccountSelectionService accounts,
        CanonicalRequestParser parser,
        ExecutorService databaseExecutor
    ) {
        this.catalog = catalog;
        this.providers = providers;
        this.accounts = accounts;
        this.parser = parser;
        this.databaseExecutor = databaseExecutor;
    }

    public Mono<RandomSelection> select(
        CanonicalRequest.Protocol protocol,
        ObjectNode request,
        RandomModelRole role
    ) {
        return Mono.fromCallable(() -> candidatesBlocking(protocol, request, role))
            .subscribeOn(Schedulers.fromExecutor(databaseExecutor))
            .flatMapMany(Flux::fromIterable)
            .concatMap(candidate -> {
                var provider = providers.require(candidate.providerId());
                return accounts.acquire(
                        candidate.providerId(),
                        candidate.model(),
                        account -> provider.supportsAccount(candidate, account))
                    .map(account -> new RandomSelection(candidate, account))
                    .onErrorResume(AccountUnavailableException.class, ignored -> Mono.empty());
            })
            .next()
            .switchIfEmpty(Mono.error(new AccountUnavailableException("random")));
    }

    private List<CanonicalRequest> candidatesBlocking(
        CanonicalRequest.Protocol protocol,
        ObjectNode request,
        RandomModelRole role
    ) {
        requireRandomModel(request);
        var capability = protocol == CanonicalRequest.Protocol.CHAT_COMPLETIONS
            ? ProviderCapability.CHAT_COMPLETIONS
            : ProviderCapability.RESPONSES;
        var byProvider = new LinkedHashMap<String, List<CanonicalRequest>>();
        for (var route : catalog.installedModels(role)) {
            var provider = providers.require(route.providerId());
            if (provider.manifest().capabilities()
                .getOrDefault(capability, SupportLevel.UNSUPPORTED)
                == SupportLevel.UNSUPPORTED) {
                continue;
            }
            var raw = request.deepCopy();
            raw.put("model", route.modelId());
            var canonical = parser.parse(
                protocol, new ResolvedRoute(route.providerId(), route.modelId()), raw);
            try {
                ProviderRequestValidation.requireSupportedRequest(
                    canonical, provider.manifest());
                provider.validate(canonical);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            byProvider.computeIfAbsent(route.providerId(), ignored -> new ArrayList<>())
                .add(canonical);
        }
        if (byProvider.isEmpty()) {
            throw new IllegalStateException(
                "no random provider/model can serve this request");
        }
        var candidates = new ArrayList<CanonicalRequest>();
        var providerIds = byProvider.keySet().stream().sorted().toList();
        for (var providerId : providerOrder(role, providerIds)) {
            var models = new ArrayList<>(byProvider.get(providerId));
            Collections.shuffle(models);
            candidates.addAll(models);
        }
        return candidates;
    }

    private List<String> providerOrder(RandomModelRole role, List<String> providerIds) {
        var key = role.catalogValue() + ":" + String.join(",", providerIds);
        String preferred;
        synchronized (shuffleLock) {
            var bag = providerBags.computeIfAbsent(key, ignored -> new ArrayDeque<>());
            if (bag.isEmpty()) {
                var shuffled = new ArrayList<>(providerIds);
                Collections.shuffle(shuffled);
                bag.addAll(shuffled);
            }
            preferred = bag.removeFirst();
        }
        var remaining = new ArrayList<>(providerIds);
        remaining.remove(preferred);
        Collections.shuffle(remaining);
        var ordered = new ArrayList<String>();
        ordered.add(preferred);
        ordered.addAll(remaining);
        return ordered;
    }

    private void requireRandomModel(ObjectNode request) {
        var value = request.path("model").asText("").trim();
        if (!value.isBlank() && !"random".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(
                "random endpoints accept only model=random or an omitted model");
        }
    }

    public record RandomSelection(
        CanonicalRequest request,
        LeasedProviderAccount account
    ) {}
}
