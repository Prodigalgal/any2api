package com.any2api.provider.grok_web;

import com.any2api.account.LeasedProviderAccount;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.protocol.state.ProviderResponseStateStore;
import com.any2api.provider.InferenceProvider;
import com.any2api.provider.ProviderAccountProfile;
import com.any2api.provider.ProviderCapability;
import com.any2api.provider.ProviderExecutionContext;
import com.any2api.provider.ProviderFailure;
import com.any2api.provider.ProviderManifest;
import com.any2api.provider.ProviderRequestValidation;
import com.any2api.provider.RandomModelRole;
import com.any2api.provider.SupportLevel;
import com.any2api.proxy.ProxyPoolService;
import com.any2api.proxy.ProxyTrafficScope;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

@Component
public final class GrokWebProvider implements InferenceProvider {
    private static final ProviderManifest MANIFEST = new ProviderManifest(
        "grok_web", "Grok Web", "grok-web-sso-v1", "1",
        GrokWebModelCatalog.modelIds(), Map.of(
            ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE,
            ProviderCapability.RESPONSES, SupportLevel.NATIVE,
            ProviderCapability.STREAMING, SupportLevel.NATIVE,
            ProviderCapability.REASONING, SupportLevel.NATIVE,
            ProviderCapability.FUNCTION_TOOLS, SupportLevel.EMULATED,
            ProviderCapability.IMAGE_GENERATION, SupportLevel.NATIVE,
            ProviderCapability.IMAGE_EDITING, SupportLevel.NATIVE,
            ProviderCapability.ACCOUNT_KEEPALIVE, SupportLevel.NATIVE),
        Map.of(RandomModelRole.TOP_TEXT, List.of(
            "grok-chat-heavy", "grok-chat-expert", "grok-chat-fast")), true);
    private static final Duration RESPONSE_STATE_TTL = Duration.ofHours(24);

    private final GrokWebProtocolClient protocol;
    private final ProxyPoolService proxyPools;
    private final ProviderResponseStateStore responseStates;
    private final ExecutorService databaseExecutor;
    private final GrokWebRequestMapper requestMapper;
    private final ObjectMapper mapper;
    private final GrokWebFailureClassifier failures;

    public GrokWebProvider(
        GrokWebProtocolClient protocol,
        ProxyPoolService proxyPools,
        ProviderResponseStateStore responseStates,
        ExecutorService databaseExecutor,
        GrokWebRequestMapper requestMapper,
        ObjectMapper mapper,
        GrokWebFailureClassifier failures
    ) {
        this.protocol = protocol;
        this.proxyPools = proxyPools;
        this.responseStates = responseStates;
        this.databaseExecutor = databaseExecutor;
        this.requestMapper = requestMapper;
        this.mapper = mapper;
        this.failures = failures;
    }

    @Override public ProviderManifest manifest() { return MANIFEST; }

    @Override
    public void validateCredential(tools.jackson.databind.JsonNode credential) {
        if (first(credential, "sso", "sso-rw", "sso_rw", "sso_token").isBlank()) {
            throw new IllegalArgumentException("Grok Web credential requires an SSO token");
        }
    }

    @Override
    public void validate(CanonicalRequest request) {
        ProviderRequestValidation.requireKnownOptions(request, Set.of());
        var model = GrokWebModelCatalog.require(request.model());
        if (model.kind() != GrokWebModelCatalog.Kind.CHAT) {
            throw new IllegalArgumentException("media model requires a media endpoint");
        }
        requestMapper.validateTools(request);
        previousResponseId(request).ifPresent(responseId -> responseStates
            .find(manifest().id(), responseId)
            .orElseThrow(() -> new IllegalArgumentException(
                "previous_response_id does not exist or has expired")));
    }

    @Override
    public boolean supportsAccount(CanonicalRequest request, ProviderAccountProfile account) {
        var model = GrokWebModelCatalog.require(request.model());
        if (!GrokWebModelCatalog.supports(String.valueOf(
            account.metadata().getOrDefault("tier", "basic")), model)) return false;
        return previousResponseId(request)
            .flatMap(responseId -> responseStates.find(manifest().id(), responseId))
            .map(state -> state.accountId().equals(account.accountId()))
            .orElse(true);
    }

    @Override
    public Flux<CanonicalEvent> generate(
        CanonicalRequest request,
        ProviderExecutionContext context,
        LeasedProviderAccount account
    ) {
        var previous = previousResponseId(request)
            .flatMap(responseId -> responseStates.find(manifest().id(), responseId));
        var previousConversationId = previous
            .map(state -> requiredState(state, "conversation_id"))
            .orElse("");
        var prepared = requestMapper.prepare(request);
        var decoder = new GrokWebEventDecoder(
            mapper, request.requestId(), previousConversationId, prepared.toolSieve());
        var body = prepared.body();
        previous.ifPresent(state -> body.put("responseId",
            requiredState(state, "upstream_response_id")));
        var proxyPool = proxyPools.runtimeForProvider(
            manifest().id(), ProxyTrafficScope.INFERENCE).orElse(Map.of());
        return protocol.chat(account.credential(), body, previousConversationId, proxyPool,
                affinity(account.metadata()), context::acceptCredentialPatch)
            .concatMapIterable(decoder::decode)
            .concatWith(Flux.defer(() -> Flux.fromIterable(decoder.finish())))
            .concatWith(Flux.defer(() -> saveResponseState(decoder, account)));
    }

    @Override
    public ProviderFailure classify(Throwable error) {
        return failures.classify(error);
    }

    private String first(tools.jackson.databind.JsonNode credential, String... fields) {
        for (var field : fields) {
            var value = credential.path(field).asText("").trim();
            if (!value.isBlank()) return value.startsWith("sso=") ? value.substring(4).trim() : value;
        }
        return "";
    }

    private String affinity(Map<String, Object> metadata) {
        return String.valueOf(metadata.getOrDefault("identity_group_id", "")).trim();
    }

    private java.util.Optional<String> previousResponseId(CanonicalRequest request) {
        if (request.protocol() != CanonicalRequest.Protocol.RESPONSES) {
            return java.util.Optional.empty();
        }
        var value = request.rawRequest().path("previous_response_id").asText("").trim();
        return value.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(value);
    }

    private String requiredState(
        ProviderResponseStateStore.ResponseState state,
        String field
    ) {
        var value = state.state().path(field).asText("").trim();
        if (value.isBlank()) {
            throw new IllegalStateException("stored provider response state is incomplete");
        }
        return value;
    }

    private Flux<CanonicalEvent> saveResponseState(
        GrokWebEventDecoder decoder,
        LeasedProviderAccount account
    ) {
        return decoder.responseState()
            .map(state -> Mono.fromRunnable(() -> responseStates.save(
                    decoder.responseId(), manifest().id(), account.accountId(),
                    state, RESPONSE_STATE_TTL))
                .subscribeOn(Schedulers.fromExecutor(databaseExecutor))
                .thenMany(Flux.<CanonicalEvent>empty()))
            .orElseGet(Flux::empty);
    }

}
