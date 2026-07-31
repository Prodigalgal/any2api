package com.any2api.lifecycle;

import com.any2api.account.AccountEntity;
import com.any2api.account.LeasedProviderAccount;
import com.any2api.coordination.AccountLease;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.provider.ProviderExecutionContext;
import com.any2api.provider.ProviderRegistry;
import com.any2api.provider.RandomModelRole;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
final class InferenceReadinessProbe {
    private static final Duration TIMEOUT = Duration.ofMinutes(2);
    private static final String MARKER = "ANY2API_PROBE_OK";

    private final ProviderRegistry providers;
    private final ObjectMapper mapper;

    InferenceReadinessProbe(ProviderRegistry providers, ObjectMapper mapper) {
        this.providers = providers;
        this.mapper = mapper;
    }

    Mono<Result> probe(
        AccountEntity account,
        JsonNode credential,
        long credentialVersion,
        Instant credentialExpiresAt
    ) {
        var provider = providers.require(account.getProviderId());
        var model = probeModel(provider.manifest());
        var requestId = "probe-" + UUID.randomUUID();
        var message = mapper.createObjectNode()
            .put("role", "user")
            .put("content", "Reply with exactly " + MARKER);
        var raw = mapper.createObjectNode()
            .put("model", model)
            .put("stream", false)
            .put("max_tokens", 32);
        var request = new CanonicalRequest(
            requestId, CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            account.getProviderId(), model, false, List.of(message),
            Map.of("max_tokens", 32), Map.of(), List.of(), Map.of(), raw);
        var lease = new AccountLease(
            account.getProviderId(), account.getId(), "readiness-probe", 0,
            Instant.now().plus(TIMEOUT));
        var leased = new LeasedProviderAccount(
            account.getId(), account.getProviderId(), account.getExternalId(), account.getEmail(),
            credentialVersion, credentialExpiresAt, credential, account.getMetadata(), lease);
        var context = new ProviderExecutionContext(
            requestId, account.getId(), Long.toString(credentialVersion),
            lease.ownerToken(), lease.fencingToken(), lease.expiresAt());
        return Flux.defer(() -> {
                provider.validate(request);
                return provider.generate(request, context, leased);
            })
            .timeout(TIMEOUT)
            .collectList()
            .map(events -> result(model, events))
            .onErrorResume(error -> {
                var failure = provider.classify(error);
                return Mono.just(Result.failed(model, failure.type()));
            });
    }

    private Result result(String model, List<CanonicalEvent> events) {
        var failed = events.stream().filter(CanonicalEvent.Failed.class::isInstance)
            .map(CanonicalEvent.Failed.class::cast).findFirst();
        if (failed.isPresent()) return Result.failed(model, failed.get().errorType());
        var completed = events.stream().anyMatch(CanonicalEvent.Completed.class::isInstance);
        var output = events.stream().filter(CanonicalEvent.OutputTextDelta.class::isInstance)
            .map(CanonicalEvent.OutputTextDelta.class::cast)
            .map(CanonicalEvent.OutputTextDelta::delta)
            .reduce("", String::concat);
        return completed && output.toUpperCase(java.util.Locale.ROOT).contains(MARKER)
            ? Result.ready(model) : Result.failed(model, "InferenceProbeIncomplete");
    }

    private static String probeModel(com.any2api.provider.ProviderManifest manifest) {
        var preferred = manifest.randomModelPreferences()
            .getOrDefault(RandomModelRole.TOP_TEXT, List.of());
        if (!preferred.isEmpty()) return preferred.getFirst();
        if (!manifest.defaultModels().isEmpty()) return manifest.defaultModels().getFirst();
        throw new IllegalStateException("provider has no model for inference readiness probe");
    }

    record Result(boolean ready, String model, String errorClass) {
        static Result ready(String model) { return new Result(true, model, ""); }
        static Result failed(String model, String errorClass) {
            var normalized = errorClass == null || errorClass.isBlank()
                ? "InferenceProbeFailed" : errorClass;
            return new Result(false, model, normalized);
        }
        static Result notRequired() { return new Result(true, "", ""); }
    }
}
