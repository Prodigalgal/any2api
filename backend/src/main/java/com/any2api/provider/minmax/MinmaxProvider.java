package com.any2api.provider.minmax;

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
import com.any2api.provider.ProviderRetryPolicy;
import com.any2api.provider.RandomModelRole;
import com.any2api.provider.SupportLevel;
import com.any2api.proxy.ProxyPoolService;
import com.any2api.proxy.ProxyTrafficScope;
import com.any2api.transport.OfficialBrowserSemanticCommandFactory;
import com.any2api.transport.OfficialBrowserTransportClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public final class MinmaxProvider implements InferenceProvider {
    private static final ProviderProtocolContract PROTOCOL = new ProviderProtocolContract(
        Map.of(
            "variant", ProviderProtocolContract.OptionType.STRING,
            "agent_role", ProviderProtocolContract.OptionType.STRING,
            "agent_id", ProviderProtocolContract.OptionType.STRING,
            "enable_team", ProviderProtocolContract.OptionType.BOOLEAN,
            "worktree_mode", ProviderProtocolContract.OptionType.BOOLEAN),
        java.util.Set.of("reasoning", "reasoning_effort"),
        java.util.Set.of("reasoning", "reasoning_effort"),
        java.util.Set.of());

    private final OfficialBrowserTransportClient transport;
    private final OfficialBrowserSemanticCommandFactory semanticCommands;
    private final ProxyPoolService proxyPools;
    private final ObjectMapper mapper;

    public MinmaxProvider(
        OfficialBrowserTransportClient transport,
        OfficialBrowserSemanticCommandFactory semanticCommands,
        ProxyPoolService proxyPools,
        ObjectMapper mapper
    ) {
        this.transport = transport;
        this.semanticCommands = semanticCommands;
        this.proxyPools = proxyPools;
        this.mapper = mapper;
    }

    @Override
    public ProviderManifest manifest() {
        return new ProviderManifest("minmax", "MinMax", "official-browser-minmax-agent-v2", "3",
            List.of("MiniMax-M3", "MiniMax-M2.7", "MiniMax-M2.7-highspeed"), Map.of(
                ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE,
                ProviderCapability.RESPONSES, SupportLevel.NATIVE,
                ProviderCapability.STREAMING, SupportLevel.NATIVE,
                ProviderCapability.REASONING, SupportLevel.NATIVE,
                ProviderCapability.IMAGE_INPUT, SupportLevel.NATIVE,
                ProviderCapability.MODEL_DISCOVERY, SupportLevel.NATIVE,
                ProviderCapability.ACCOUNT_KEEPALIVE, SupportLevel.NATIVE,
                ProviderCapability.ACCOUNT_DAILY_CHECKIN, SupportLevel.NATIVE,
                ProviderCapability.REGISTRATION, SupportLevel.NATIVE,
                ProviderCapability.REAUTHENTICATION, SupportLevel.NATIVE),
            Map.of(RandomModelRole.TOP_TEXT, List.of("MiniMax-M3")), true);
    }

    @Override
    public ProviderProtocolContract protocolContract() {
        return PROTOCOL;
    }

    @Override
    public ProviderRetryPolicy retryPolicy() {
        return ProviderRetryPolicy.standardWith(3, "quota_exhausted");
    }

    @Override
    public Duration accountProbeTimeout() {
        return Duration.ofMinutes(2);
    }

    @Override
    public void validate(CanonicalRequest request) {
        ProviderRequestValidation.requireInlineImageUploads(request, "MinMax");
        if (!request.tools().isEmpty()) {
            throw new IllegalArgumentException("MinMax does not support tools");
        }
    }

    @Override
    public Flux<CanonicalEvent> generate(
        CanonicalRequest request,
        ProviderExecutionContext context,
        LeasedProviderAccount account
    ) {
        MinmaxCredential.from(account);
        var proxyPool = proxyPool();
        var affinityKey = proxyAffinityKey(account);
        return Flux.defer(() -> {
            var decoder = new MinmaxEventDecoder(request.requestId());
            var status = new AtomicInteger(-1);
            return transport.stream(
                    manifest().id(),
                    "chat",
                    semanticCommands.chat(request),
                    account.credential(),
                    proxyPool,
                    affinityKey)
                .handle((frame, sink) -> {
                    var type = frame.path("type").asText("");
                    if ("status".equals(type)) {
                        status.set(frame.path("status").asInt(502));
                    } else if ("error".equals(type)) {
                        var code = status.get() < 0 ? 502 : status.get();
                        sink.error(new MinmaxUpstreamException(
                            code, summarize(code, frame.path("data").asText(""))));
                    } else if ("data".equals(type) && status.get() < 400) {
                        sink.next(frame.path("data").asText(""));
                    } else if ("credential_patch".equals(type)) {
                        context.acceptCredentialPatch(frame.path("data"));
                    }
                })
                .cast(String.class)
                .concatMapIterable(decoder::decode)
                .concatWith(Flux.defer(() -> status.get() >= 400
                    ? Flux.error(new MinmaxUpstreamException(
                        status.get(), "MinMax upstream returned HTTP " + status.get()))
                    : Flux.fromIterable(decoder.finish())));
        });
    }

    @Override
    public Mono<List<DiscoveredModel>> discoverModels(LeasedProviderAccount account) {
        MinmaxCredential.from(account);
        return transport.request(
                manifest().id(),
                "models",
                semanticCommands.models(),
                account.credential(),
                proxyPool(),
                proxyAffinityKey(account))
            .flatMap(response -> responseJson(response, null))
            .map(this::parseModels);
    }

    private List<DiscoveredModel> parseModels(JsonNode body) {
        var models = new java.util.LinkedHashMap<String, DiscoveredModel>();
        for (var item : body.path("models")) {
            var id = item.path("model_id").asText(item.path("id").asText("")).trim();
            if (!id.isBlank()) {
                var displayName = item.path("model_name").asText(item.path("name").asText(id));
                var metadata = new java.util.LinkedHashMap<String, Object>();
                if (item.path("variants").isArray()) {
                    metadata.put("variants", item.path("variants").deepCopy());
                }
                if (item.path("provider_id").isTextual()) {
                    metadata.put("provider_id", item.path("provider_id").asText());
                }
                models.putIfAbsent(id, new DiscoveredModel(id, displayName, metadata));
            }
        }
        return List.copyOf(models.values());
    }

    private Mono<JsonNode> responseJson(
        OfficialBrowserTransportClient.TransportResponse response,
        ProviderExecutionContext context
    ) {
        if (context != null) context.acceptCredentialPatch(response.credentialPatch());
        if (response.status() < 200 || response.status() >= 300) {
            return Mono.error(new MinmaxUpstreamException(
                response.status(), summarize(response.status(), response.body())));
        }
        try {
            return Mono.just(mapper.readTree(response.body()));
        } catch (RuntimeException error) {
            return Mono.error(new MinmaxUpstreamException(
                502, "MinMax upstream returned invalid JSON"));
        }
    }

    private Map<String, Object> proxyPool() {
        return proxyPools.runtimeForProvider(manifest().id(), ProxyTrafficScope.INFERENCE)
            .orElse(Map.of());
    }

    private static String proxyAffinityKey(LeasedProviderAccount account) {
        var persisted = account.credential().path("proxy_affinity_key").asText("").trim();
        return persisted.isBlank() ? account.accountId().toString() : persisted;
    }

    @Override
    public ProviderFailure classify(Throwable error) {
        if (error instanceof MinmaxUpstreamException upstream) {
            var retryable = upstream.status() >= 500
                || List.of(408, 409, 425, 429).contains(upstream.status());
            var type = switch (upstream.status()) {
                case 401, 403 -> "credential_rejected";
                case 429 -> "rate_limited";
                default -> "provider_upstream_error";
            };
            return new ProviderFailure(type, upstream.getMessage(), retryable,
                Map.of("status", upstream.status()));
        }
        return new ProviderFailure("provider_transport_error",
            error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(),
            true, Map.of());
    }

    private String summarize(int status, String body) {
        var compact = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        if (compact.length() > 1000) compact = compact.substring(0, 1000);
        return compact.isBlank() ? "MinMax upstream returned HTTP " + status
            : "MinMax upstream returned HTTP " + status + ": " + compact;
    }
}
