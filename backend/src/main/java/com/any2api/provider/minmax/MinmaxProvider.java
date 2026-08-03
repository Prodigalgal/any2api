package com.any2api.provider.minmax;

import com.any2api.account.LeasedProviderAccount;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.provider.InferenceProvider;
import com.any2api.provider.DiscoveredModel;
import com.any2api.provider.ProviderCapability;
import com.any2api.provider.ProviderExecutionContext;
import com.any2api.provider.ProviderFailure;
import com.any2api.provider.ProviderManifest;
import com.any2api.provider.ProviderProtocolContract;
import com.any2api.provider.RandomModelRole;
import com.any2api.provider.SupportLevel;
import com.any2api.proxy.ProxyPoolService;
import com.any2api.proxy.ProxyTrafficScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public final class MinmaxProvider implements InferenceProvider {
    private static final ProviderProtocolContract PROTOCOL = new ProviderProtocolContract(
        Map.of(
            "variant", ProviderProtocolContract.OptionType.STRING,
            "agent_role", ProviderProtocolContract.OptionType.STRING,
            "enable_team", ProviderProtocolContract.OptionType.BOOLEAN,
            "worktree_mode", ProviderProtocolContract.OptionType.BOOLEAN),
        java.util.Set.of("reasoning", "reasoning_effort"),
        java.util.Set.of("reasoning", "reasoning_effort"),
        java.util.Set.of());
    private final MinmaxTransportClient transport;
    private final ProxyPoolService proxyPools;
    private final MinmaxRequestMapper requestMapper;
    private final MinmaxMediaUploader mediaUploader;
    private final ObjectMapper mapper;

    public MinmaxProvider(
        MinmaxTransportClient transport,
        ProxyPoolService proxyPools,
        MinmaxRequestMapper requestMapper,
        MinmaxMediaUploader mediaUploader,
        ObjectMapper mapper
    ) {
        this.transport = transport;
        this.proxyPools = proxyPools;
        this.requestMapper = requestMapper;
        this.mediaUploader = mediaUploader;
        this.mapper = mapper;
    }

    @Override
    public ProviderManifest manifest() {
        return new ProviderManifest("minmax", "MinMax", "native-minmax-agent-web-v1", "2",
            List.of("MiniMax-M3", "MiniMax-M2.7", "MiniMax-M2.7-highspeed"), Map.of(
                ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE,
                ProviderCapability.RESPONSES, SupportLevel.NATIVE,
                ProviderCapability.STREAMING, SupportLevel.NATIVE,
                ProviderCapability.REASONING, SupportLevel.NATIVE,
                ProviderCapability.IMAGE_INPUT, SupportLevel.NATIVE,
                ProviderCapability.MODEL_DISCOVERY, SupportLevel.NATIVE,
                ProviderCapability.ACCOUNT_KEEPALIVE, SupportLevel.NATIVE,
                ProviderCapability.REGISTRATION, SupportLevel.NATIVE,
                ProviderCapability.REAUTHENTICATION, SupportLevel.NATIVE),
            Map.of(RandomModelRole.TOP_TEXT, List.of("MiniMax-M3")), true);
    }

    @Override
    public ProviderProtocolContract protocolContract() {
        return PROTOCOL;
    }

    @Override
    public void validate(CanonicalRequest request) {
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
        var credential = MinmaxCredential.from(account);
        var prepared = requestMapper.prepare(request);
        var proxyPool = proxyPool();
        var affinityKey = account.accountId() + ":" + request.requestId();
        return mediaUploader.upload(account.credential(), prepared.media(), proxyPool, affinityKey)
            .flatMapMany(attachments -> resolveAgent(account.credential(), credential,
                    prepared.agentRole(), proxyPool, affinityKey)
                .flatMap(agentId -> createSession(account.credential(), agentId,
                    prepared.sessionModel(), proxyPool, affinityKey))
                .flatMapMany(sessionId -> streamMessage(account.credential(), sessionId,
                    prepared, attachments, request.requestId(), proxyPool, affinityKey)));
    }

    @Override
    public Mono<List<DiscoveredModel>> discoverModels(LeasedProviderAccount account) {
        MinmaxCredential.from(account);
        return transport.request("GET", "/archon/api/v1/config", "", account.credential(),
                proxyPool(), account.accountId().toString())
            .flatMap(response -> responseJson(response).flatMap(body -> {
                    var models = new java.util.LinkedHashMap<String, DiscoveredModel>();
                    for (var item : body.path("models")) {
                        var id = item.path("model_id").asText(item.path("id").asText("")).trim();
                        if (id.isBlank()) continue;
                        var displayName = item.path("model_name").asText(item.path("name").asText(id));
                        var metadata = new java.util.LinkedHashMap<String, Object>();
                        if (item.path("variants").isArray()) metadata.put("variants", item.path("variants").deepCopy());
                        if (item.path("provider_id").isTextual()) metadata.put("provider_id", item.path("provider_id").asText());
                        models.putIfAbsent(id, new DiscoveredModel(id, displayName, metadata));
                    }
                    return Mono.just(List.copyOf(models.values()));
                }));
    }

    private <T> Mono<T> upstreamError(int status, JsonNode body) {
        return Mono.error(new MinmaxUpstreamException(status, summarize(status, body.toString())));
    }

    private Mono<String> resolveAgent(
        JsonNode rawCredential,
        MinmaxCredential credential,
        String role,
        Map<String, Object> proxyPool,
        String affinityKey
    ) {
        if (!credential.agentId().isBlank()) return Mono.just(credential.agentId());
        return transport.request("GET", "/archon/api/v1/agent?limit=20", "", rawCredential,
                proxyPool, affinityKey)
            .flatMap(this::responseJson)
            .flatMap(body -> {
                    for (var agent : body.path("agents")) {
                        if (role.equalsIgnoreCase(agent.path("agent_role").asText(""))) {
                            return Mono.just(agent.path("name").asText());
                        }
                    }
                    return Mono.error(new MinmaxUpstreamException(502,
                        "MinMax agent list has no role " + role));
            });
    }

    private Mono<String> createSession(
        JsonNode rawCredential,
        String agentId,
        String model,
        Map<String, Object> proxyPool,
        String affinityKey
    ) {
        var body = mapper.createObjectNode().put("model", model);
        var bodyText = mapper.writeValueAsString(body);
        var path = "/archon/api/v1/agent/" + agentId + "/session";
        return transport.request("POST", path, bodyText, rawCredential, proxyPool, affinityKey)
            .flatMap(this::responseJson)
            .flatMap(bodyNode -> {
                    var sessionId = bodyNode.path("session_id").asText("").trim();
                    return sessionId.isBlank()
                        ? Mono.error(new MinmaxUpstreamException(502,
                            "MinMax session creation returned no session_id"))
                        : Mono.just(sessionId);
            });
    }

    private Flux<CanonicalEvent> streamMessage(
        JsonNode rawCredential,
        String sessionId,
        MinmaxPreparedRequest prepared,
        List<ObjectNode> attachments,
        String requestId,
        Map<String, Object> proxyPool,
        String affinityKey
    ) {
        var body = mapper.createObjectNode();
        body.put("content", prepared.content());
        body.set("model", prepared.model().deepCopy());
        body.put("turn_id", UUID.randomUUID().toString());
        body.put("enable_team", prepared.enableTeam());
        body.put("worktreeMode", prepared.worktreeMode());
        var upstreamAttachments = body.putArray("attachments");
        attachments.forEach(upstreamAttachments::add);
        var bodyText = mapper.writeValueAsString(body);
        var path = "/archon/api/v1/session/" + sessionId + "/message";
        return Flux.defer(() -> {
            var decoder = new MinmaxEventDecoder(requestId);
            var status = new java.util.concurrent.atomic.AtomicInteger(-1);
            return transport.stream("POST", path, bodyText, rawCredential, proxyPool, affinityKey)
                .handle((frame, sink) -> {
                    var type = frame.path("type").asText("");
                    if ("status".equals(type)) {
                        status.set(frame.path("status").asInt(502));
                    } else if ("error".equals(type)) {
                        var code = status.get() < 0 ? 502 : status.get();
                        sink.error(new MinmaxUpstreamException(code,
                            summarize(code, frame.path("data").asText(""))));
                    } else if ("data".equals(type) && status.get() < 400) {
                        sink.next(frame.path("data").asText(""));
                    }
                })
                .cast(String.class)
                .concatMapIterable(decoder::decode)
                .concatWith(Flux.defer(() -> status.get() >= 400
                    ? Flux.error(new MinmaxUpstreamException(status.get(),
                        "MinMax upstream returned HTTP " + status.get()))
                    : Flux.fromIterable(decoder.finish())));
        });
    }

    private Mono<JsonNode> responseJson(MinmaxTransportClient.TransportResponse response) {
        if (response.status() < 200 || response.status() >= 300) {
            return upstreamError(response.status(), mapper.createObjectNode()
                .put("body", response.body()));
        }
        try {
            return Mono.just(mapper.readTree(response.body()));
        } catch (RuntimeException error) {
            return Mono.error(new MinmaxUpstreamException(502,
                "MinMax upstream returned invalid JSON"));
        }
    }

    private Map<String, Object> proxyPool() {
        return proxyPools.runtimeForProvider(manifest().id(), ProxyTrafficScope.INFERENCE)
            .orElse(Map.of());
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
