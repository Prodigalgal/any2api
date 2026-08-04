package com.any2api.lifecycle;

import com.any2api.account.AccountEntity;
import com.any2api.account.LeasedProviderAccount;
import com.any2api.coordination.AccountLease;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalEventStream;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.provider.ProviderExecutionContext;
import com.any2api.provider.ProviderRegistry;
import com.any2api.provider.ProviderRequestValidation;
import com.any2api.provider.RandomModelRole;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
final class InferenceReadinessProbe {
    private static final Duration TIMEOUT = Duration.ofMinutes(2);
    private static final String MARKER = "ANY2API_PROBE_OK";
    private static final Logger LOGGER = LoggerFactory.getLogger(InferenceReadinessProbe.class);

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
        var lease = new AccountLease(
            account.getProviderId(), account.getId(), "readiness-probe", 0,
            Instant.now().plus(TIMEOUT));
        return probe(new LeasedProviderAccount(
            account.getId(), account.getProviderId(), account.getExternalId(), account.getEmail(),
            credentialVersion, credentialExpiresAt, credential, account.getMetadata(), lease),
            TIMEOUT);
    }

    Mono<Result> probe(LeasedProviderAccount account, Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            return Mono.error(new IllegalArgumentException("probe timeout must be positive"));
        }
        var provider = providers.require(account.providerId());
        var model = probeModel(provider.manifest());
        var requestId = "probe-" + UUID.randomUUID();
        var message = mapper.createObjectNode()
            .put("role", "user")
            .put("content", "Reply briefly with " + MARKER);
        var raw = mapper.createObjectNode()
            .put("model", model)
            .put("stream", false);
        var request = new CanonicalRequest(
            requestId, CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            account.providerId(), model, false, List.of(message),
            Map.of(), Map.of(), List.of(), Map.of(), raw);
        var context = new ProviderExecutionContext(
            requestId, account.accountId(), Long.toString(account.credentialVersion()),
            account.lease().ownerToken(), account.lease().fencingToken(),
            Instant.now().plus(timeout));
        return Flux.defer(() -> {
                ProviderRequestValidation.requireSupportedRequest(
                    request, provider.manifest(), provider.protocolContract());
                provider.validate(request);
                return CanonicalEventStream.enforce(
                    request, provider.generate(request, context, account));
            })
            .timeout(timeout)
            .collectList()
            .map(events -> result(model, events, context.credentialPatch()))
            .onErrorResume(error -> {
                var failure = provider.classify(error);
                LOGGER.warn(
                    "Inference readiness probe failed provider={} error_type={} failure={} detail={}",
                    account.providerId(), error.getClass().getSimpleName(), failure.type(),
                    safeDetail(error));
                return Mono.just(Result.failed(
                    model, failure.type(), context.credentialPatch()));
            });
    }

    private Result result(
        String model,
        List<CanonicalEvent> events,
        JsonNode credentialPatch
    ) {
        var failed = events.stream().filter(CanonicalEvent.Failed.class::isInstance)
            .map(CanonicalEvent.Failed.class::cast).findFirst();
        if (failed.isPresent()) {
            return Result.failed(model, failed.get().errorType(), credentialPatch);
        }
        var completed = events.stream().anyMatch(CanonicalEvent.Completed.class::isInstance);
        var output = events.stream().filter(CanonicalEvent.OutputTextDelta.class::isInstance)
            .map(CanonicalEvent.OutputTextDelta.class::cast)
            .map(CanonicalEvent.OutputTextDelta::delta)
            .reduce("", String::concat);
        return completed && !output.isBlank()
            ? Result.ready(model, credentialPatch)
            : Result.failed(model, "InferenceProbeIncomplete", credentialPatch);
    }

    private static String probeModel(com.any2api.provider.ProviderManifest manifest) {
        var preferred = manifest.randomModelPreferences()
            .getOrDefault(RandomModelRole.TOP_TEXT, List.of());
        if (!preferred.isEmpty()) return preferred.getFirst();
        if (!manifest.defaultModels().isEmpty()) return manifest.defaultModels().getFirst();
        throw new IllegalStateException("provider has no model for inference readiness probe");
    }

    private static String safeDetail(Throwable error) {
        var message = error.getMessage();
        if (message == null || message.isBlank()) return "unavailable";
        var redacted = message
            .replaceAll("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", "[email]")
            .replaceAll("[A-Za-z0-9_\\-=]{32,}", "[secret]")
            .replaceAll("\\s+", " ")
            .trim();
        return redacted.substring(0, Math.min(500, redacted.length()));
    }

    record Result(boolean ready, String model, String errorClass, JsonNode credentialPatch) {
        Result {
            credentialPatch = credentialPatch == null
                ? tools.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                : credentialPatch.deepCopy();
        }
        static Result ready(String model) {
            return ready(model, tools.jackson.databind.node.JsonNodeFactory.instance.objectNode());
        }
        static Result ready(String model, JsonNode credentialPatch) {
            return new Result(true, model, "", credentialPatch);
        }
        static Result failed(String model, String errorClass) {
            return failed(model, errorClass,
                tools.jackson.databind.node.JsonNodeFactory.instance.objectNode());
        }
        static Result failed(String model, String errorClass, JsonNode credentialPatch) {
            var normalized = errorClass == null || errorClass.isBlank()
                ? "InferenceProbeFailed" : errorClass;
            return new Result(false, model, normalized, credentialPatch);
        }
        static Result notRequired() {
            return new Result(true, "", "",
                tools.jackson.databind.node.JsonNodeFactory.instance.objectNode());
        }
    }
}
