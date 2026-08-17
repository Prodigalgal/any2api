package com.any2api.provider.glm;

import com.any2api.account.LeasedProviderAccount;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.provider.DiscoveredModel;
import com.any2api.provider.InferenceProvider;
import com.any2api.provider.ProviderCapability;
import com.any2api.provider.ProviderExecutionContext;
import com.any2api.provider.ProviderFailure;
import com.any2api.provider.ProviderManifest;
import com.any2api.provider.ProviderProtocolContract;
import com.any2api.provider.ProviderRequestValidation;
import com.any2api.provider.RandomModelRole;
import com.any2api.provider.SupportLevel;
import com.any2api.observability.RequestCorrelation;
import com.any2api.proxy.ProxyPoolService;
import com.any2api.proxy.ProxyTrafficScope;
import com.any2api.transport.BrowserTransportClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public final class GlmProvider implements InferenceProvider {
    private static final ProviderProtocolContract PROTOCOL = new ProviderProtocolContract(
        Map.of(
            "enable_thinking", ProviderProtocolContract.OptionType.BOOLEAN,
            "reasoning_effort", ProviderProtocolContract.OptionType.STRING,
            "web_search", ProviderProtocolContract.OptionType.BOOLEAN,
            "preview_mode", ProviderProtocolContract.OptionType.BOOLEAN),
        java.util.Set.of(
            "temperature", "top_p", "max_tokens", "max_completion_tokens",
            "max_output_tokens", "reasoning", "reasoning_effort", "web_search",
            "preview_mode"),
        java.util.Set.of(
            "temperature", "top_p", "max_tokens", "max_completion_tokens",
            "max_output_tokens", "reasoning", "reasoning_effort", "web_search",
            "preview_mode"),
        java.util.Set.of());
    private static final ProviderManifest MANIFEST = new ProviderManifest(
        "glm", "GLM", "official-browser-z-ai-web-v1", "3", List.of("glm-5.2"), Map.of(
            ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE,
            ProviderCapability.RESPONSES, SupportLevel.NATIVE,
            ProviderCapability.STREAMING, SupportLevel.NATIVE,
            ProviderCapability.REASONING, SupportLevel.NATIVE,
            ProviderCapability.MODEL_DISCOVERY, SupportLevel.NATIVE,
            ProviderCapability.ACCOUNT_KEEPALIVE, SupportLevel.NATIVE,
            ProviderCapability.REGISTRATION, SupportLevel.NATIVE,
            ProviderCapability.REAUTHENTICATION, SupportLevel.NATIVE),
        Map.of(RandomModelRole.TOP_TEXT, List.of("glm-5.2")), true);

    private final GlmProtocolClient protocol;
    private final GlmProperties properties;
    private final ProxyPoolService proxyPools;
    private final ObjectMapper mapper;
    private final WebClient webClient;

    public GlmProvider(
        GlmProtocolClient protocol,
        GlmProperties properties,
        ProxyPoolService proxyPools,
        ObjectMapper mapper,
        WebClient.Builder webClientBuilder
    ) {
        this.protocol = protocol;
        this.properties = properties;
        this.proxyPools = proxyPools;
        this.mapper = mapper;
        this.webClient = webClientBuilder.baseUrl(properties.getBaseUrl())
            .filter(RequestCorrelation.propagationFilter()).build();
    }

    @Override public ProviderManifest manifest() { return MANIFEST; }

    @Override public ProviderProtocolContract protocolContract() { return PROTOCOL; }

    @Override
    public void validateCredential(JsonNode credential) {
        if (first(credential, "token", "access_token", "jwt").isBlank()
            || first(credential, "user_id", "userId", "id").isBlank()) {
            throw new IllegalArgumentException("GLM credential requires token and user_id");
        }
    }

    @Override
    public void validate(CanonicalRequest request) {
        ProviderRequestValidation.requireStringParameters(request, "reasoning_effort");
        ProviderRequestValidation.requireBooleanParameters(
            request, "web_search", "preview_mode");
        ProviderRequestValidation.requireReasoningBooleanConsistency(
            request, "enable_thinking", java.util.Set.of("none", "minimal", "low"));
        if (!request.tools().isEmpty()) {
            throw new IllegalArgumentException("GLM does not support function tools");
        }
    }

    @Override
    public Flux<CanonicalEvent> generate(
        CanonicalRequest request,
        ProviderExecutionContext context,
        LeasedProviderAccount account
    ) {
        GlmCredential.from(account);
        var decoder = new GlmEventDecoder(request.requestId(), mapper);
        var proxyPool = proxyPools.runtimeForProvider(
            manifest().id(), ProxyTrafficScope.INFERENCE).orElse(Map.of());
        return protocol.chat(
                account.credential(),
                request,
                proxyPool,
                proxyAffinityKey(account),
                context::acceptCredentialPatch)
            .concatMapIterable(decoder::decode)
            .concatWith(Flux.defer(() -> Flux.fromIterable(decoder.finish())));
    }

    @Override
    public Mono<List<DiscoveredModel>> discoverModels(LeasedProviderAccount account) {
        return webClient.get().uri("/api/models")
            .retrieve()
            .bodyToMono(JsonNode.class)
            .map(GlmProvider::parseModels);
    }

    static List<DiscoveredModel> parseModels(JsonNode root) {
        var candidates = List.of(
            root,
            root.path("models"),
            root.path("data"),
            root.path("data").path("models"),
            root.path("data").path("data"));
        JsonNode items = tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        for (var candidate : candidates) {
            if (candidate.isArray()) {
                items = candidate;
                break;
            }
        }
        var output = new LinkedHashMap<String, DiscoveredModel>();
        for (var item : items) {
            var id = item.isTextual() ? item.asText("").trim()
                : first(item, "id", "model", "name");
            if (id.isBlank()) continue;
            var info = item.path("info");
            if (item.path("is_active").isBoolean() && !item.path("is_active").asBoolean()) {
                continue;
            }
            if (info.path("is_active").isBoolean() && !info.path("is_active").asBoolean()) {
                continue;
            }
            var metadata = new LinkedHashMap<String, Object>();
            if (info.path("meta").isObject()) metadata.put("glm", info.path("meta").deepCopy());
            var display = item.path("name").asText(id);
            output.putIfAbsent(id, new DiscoveredModel(id, display, metadata));
        }
        return List.copyOf(output.values());
    }

    @Override
    public ProviderFailure classify(Throwable error) {
        var status = status(error);
        if (status > 0) {
            var retryable = status >= 500 || List.of(408, 409, 425, 429).contains(status);
            var type = switch (status) {
                case 401, 403 -> "credential_rejected";
                case 429 -> "rate_limited";
                default -> "provider_upstream_error";
            };
            return new ProviderFailure(type, message(error), retryable, Map.of("status", status));
        }
        return new ProviderFailure(
            "provider_transport_error", message(error), true, Map.of());
    }

    private int status(Throwable error) {
        if (error instanceof GlmUpstreamException value) return value.status();
        if (error instanceof BrowserTransportClient.BrowserTransportException value) {
            return value.status();
        }
        if (error instanceof WebClientResponseException value) return value.getStatusCode().value();
        return 0;
    }

    private String proxyAffinityKey(LeasedProviderAccount account) {
        var persisted = account.credential().path("proxy_affinity_key").asText("").trim();
        if (!persisted.isBlank()) return persisted;
        var identityGroup = String.valueOf(
            account.metadata().getOrDefault("identity_group_id", "")).trim();
        return identityGroup.isBlank() ? account.accountId().toString() : identityGroup;
    }

    private String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static String first(JsonNode source, String... fields) {
        for (var field : fields) {
            var value = source.path(field).asText("").trim();
            if (!value.isBlank()) return value;
        }
        return "";
    }
}
