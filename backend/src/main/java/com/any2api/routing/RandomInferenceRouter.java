package com.any2api.routing;

import com.any2api.auth.ApiKeyGrant;
import com.any2api.account.AccountSelectionService;
import com.any2api.account.AccountUnavailableException;
import com.any2api.account.LeasedProviderAccount;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.protocol.CanonicalRequestParser;
import com.any2api.protocol.OpenAiRequestException;
import com.any2api.provider.ProviderCapability;
import com.any2api.provider.ProviderRegistry;
import com.any2api.provider.ModelRuntimeGuard;
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
    private final ModelRuntimeGuard runtimeGuard;
    private final ModelHealthTracker healthTracker;
    private final Object shuffleLock = new Object();
    private final Map<String, ArrayDeque<String>> providerBags = new HashMap<>();

    public RandomInferenceRouter(
        RandomRouteCatalog catalog,
        ProviderRegistry providers,
        AccountSelectionService accounts,
        CanonicalRequestParser parser,
        ExecutorService databaseExecutor,
        ModelRuntimeGuard runtimeGuard,
        ModelHealthTracker healthTracker
    ) {
        this.catalog = catalog;
        this.providers = providers;
        this.accounts = accounts;
        this.parser = parser;
        this.databaseExecutor = databaseExecutor;
        this.runtimeGuard = runtimeGuard;
        this.healthTracker = healthTracker == null ? new ModelHealthTracker() : healthTracker;
    }

    public Mono<RandomSelection> select(
        CanonicalRequest.Protocol protocol,
        ObjectNode request,
        RandomModelRole role
    ) {
        return select(protocol, request, role, ApiKeyGrant.unrestricted(), null);
    }

    public Mono<RandomSelection> select(
        CanonicalRequest.Protocol protocol,
        ObjectNode request,
        RandomModelRole role,
        ApiKeyGrant grant
    ) {
        return select(protocol, request, role, grant, null);
    }

    public Mono<RandomSelection> select(
        CanonicalRequest.Protocol protocol,
        ObjectNode request,
        RandomModelRole role,
        ApiKeyGrant grant,
        String requestId
    ) {
        return selectCandidates(protocol, request, role, grant, requestId)
            .next()
            .switchIfEmpty(Mono.error(new AccountUnavailableException("random")));
    }

    public Flux<RandomSelection> selectCandidates(
        CanonicalRequest.Protocol protocol,
        ObjectNode request,
        RandomModelRole role,
        ApiKeyGrant grant,
        String requestId
    ) {
        return Mono.fromCallable(() -> candidatesBlocking(
                protocol, request, role, grant, requestId))
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
            });
    }

    private List<CanonicalRequest> candidatesBlocking(
        CanonicalRequest.Protocol protocol,
        ObjectNode request,
        RandomModelRole role,
        ApiKeyGrant grant,
        String requestId
    ) {
        requireRandomModel(request);
        var capability = protocol == CanonicalRequest.Protocol.CHAT_COMPLETIONS
            ? ProviderCapability.CHAT_COMPLETIONS
            : ProviderCapability.RESPONSES;
        var byProvider = new LinkedHashMap<String, List<CanonicalRequest>>();
        for (var route : catalog.installedModels(role)) {
            if (!grant.allowsModel(route.providerId(), route.modelId())) continue;
            if (!runtimeGuard.callable(route.providerId(), route.modelId())) continue;
            var provider = providers.require(route.providerId());
            if (provider.manifest().capabilities()
                .getOrDefault(capability, SupportLevel.UNSUPPORTED)
                == SupportLevel.UNSUPPORTED) {
                continue;
            }
            var raw = request.deepCopy();
            raw.put("model", route.modelId());
            var canonical = parser.parseCandidate(
                protocol, new ResolvedRoute(route.providerId(), route.modelId()), raw, requestId);
            try {
                ProviderRequestValidation.requireSupportedRequest(
                    canonical, provider.manifest(), provider.protocolContract());
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
        for (var providerId : providerOrder(role, providerIds, byProvider)) {
            var models = new ArrayList<>(byProvider.get(providerId));
            Collections.shuffle(models);
            // Health and latency aware prioritization: sort models by score descending
            models.sort((m1, m2) -> {
                double s1 = healthTracker.healthScore(m1.providerId(), m1.model());
                double s2 = healthTracker.healthScore(m2.providerId(), m2.model());
                if (Math.abs(s1 - s2) > 0.15) {
                    return Double.compare(s2, s1);
                }
                return 0;
            });
            candidates.addAll(models);
        }
        return candidates;
    }

    private List<String> providerOrder(
        RandomModelRole role,
        List<String> providerIds,
        Map<String, List<CanonicalRequest>> byProvider
    ) {
        var key = role.catalogValue() + ":" + String.join(",", providerIds);
        String preferred;
        synchronized (shuffleLock) {
            var bag = providerBags.computeIfAbsent(key, ignored -> new ArrayDeque<>());
            if (bag.isEmpty()) {
                var shuffled = new ArrayList<>(providerIds);
                Collections.shuffle(shuffled);
                // Deprioritize providers where all models are cooling
                shuffled.sort((p1, p2) -> {
                    boolean c1 = isProviderAllCooling(p1, byProvider.get(p1));
                    boolean c2 = isProviderAllCooling(p2, byProvider.get(p2));
                    return Boolean.compare(c1, c2);
                });
                bag.addAll(shuffled);
            }
            preferred = bag.removeFirst();
        }
        var remaining = new ArrayList<>(providerIds);
        remaining.remove(preferred);
        Collections.shuffle(remaining);
        remaining.sort((p1, p2) -> {
            boolean c1 = isProviderAllCooling(p1, byProvider.get(p1));
            boolean c2 = isProviderAllCooling(p2, byProvider.get(p2));
            return Boolean.compare(c1, c2);
        });
        var ordered = new ArrayList<String>();
        ordered.add(preferred);
        ordered.addAll(remaining);
        return ordered;
    }

    private boolean isProviderAllCooling(String providerId, List<CanonicalRequest> requests) {
        if (requests == null || requests.isEmpty()) return false;
        return requests.stream().allMatch(r -> healthTracker.isCooling(r.providerId(), r.model()));
    }

    private void requireRandomModel(ObjectNode request) {
        var value = request.path("model").asText("").trim();
        if (!value.isBlank() && !"random".equalsIgnoreCase(value)) {
            throw OpenAiRequestException.invalid(
                "model", "random endpoints accept only model=random or an omitted model");
        }
    }

    public record RandomSelection(
        CanonicalRequest request,
        LeasedProviderAccount account
    ) {}
}
