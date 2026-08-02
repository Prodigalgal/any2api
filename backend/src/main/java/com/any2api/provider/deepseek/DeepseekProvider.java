package com.any2api.provider.deepseek;

import com.any2api.account.LeasedProviderAccount;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.protocol.OpenAiRequestException;
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
import com.any2api.transport.BrowserTransportClient;
import com.any2api.transport.SseDataDecoder;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public final class DeepseekProvider implements InferenceProvider {
    private static final String COMPLETION_PATH = "/api/v0/chat/completion";
    private static final ProviderProtocolContract PROTOCOL = new ProviderProtocolContract(
        Map.of(
            "thinking_enabled", ProviderProtocolContract.OptionType.BOOLEAN,
            "search_enabled", ProviderProtocolContract.OptionType.BOOLEAN),
        Set.of(
            "reasoning", "reasoning_effort", "enable_thinking",
            "web_search", "enable_search", "search", "tools", "tool_choice"),
        Set.of(
            "reasoning", "reasoning_effort", "enable_thinking",
            "web_search", "enable_search", "search", "tools", "tool_choice"),
        Set.of("web_search", "web_search_preview", "search"));

    private final BrowserTransportClient transport;
    private final ProxyPoolService proxyPools;
    private final DeepseekProperties properties;
    private final DeepseekRequestMapper requestMapper;
    private final ObjectMapper mapper;
    private final DeepseekPowSolver pow = new DeepseekPowSolver();

    public DeepseekProvider(
        BrowserTransportClient transport,
        ProxyPoolService proxyPools,
        DeepseekProperties properties,
        DeepseekRequestMapper requestMapper,
        ObjectMapper mapper
    ) {
        this.transport = transport;
        this.proxyPools = proxyPools;
        this.properties = properties;
        this.requestMapper = requestMapper;
        this.mapper = mapper;
    }

    @Override
    public ProviderManifest manifest() {
        return new ProviderManifest("deepseek", "DeepSeek", "native-deepseek-web-v2.3", "1",
            List.of("default", "expert", "vision"), Map.of(
                ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE,
                ProviderCapability.RESPONSES, SupportLevel.NATIVE,
                ProviderCapability.STREAMING, SupportLevel.NATIVE,
                ProviderCapability.REASONING, SupportLevel.NATIVE,
                ProviderCapability.MODEL_DISCOVERY, SupportLevel.NATIVE,
                ProviderCapability.ACCOUNT_KEEPALIVE, SupportLevel.NATIVE,
                ProviderCapability.REGISTRATION, SupportLevel.NATIVE,
                ProviderCapability.REAUTHENTICATION, SupportLevel.NATIVE),
            Map.of(RandomModelRole.TOP_TEXT, List.of("expert")), true);
    }

    @Override public ProviderProtocolContract protocolContract() { return PROTOCOL; }

    @Override
    public void validate(CanonicalRequest request) {
        ProviderRequestValidation.requireBooleanParameters(
            request, "enable_thinking", "web_search", "enable_search", "search");
        ProviderRequestValidation.requireConsistentBooleanAliases(
            request, "search_enabled", "web_search", "enable_search", "search");
        var toolChoice = request.rawRequest().path("tool_choice");
        if (!toolChoice.isMissingNode() && !toolChoice.isNull()
            && (!toolChoice.isTextual()
                || !Set.of("auto", "none").contains(toolChoice.asText().toLowerCase()))) {
            throw new IllegalArgumentException(
                "DeepSeek tool_choice supports only auto or none for web search");
        }
        var unsupported = request.tools().stream()
            .map(tool -> tool.path("type").asText("function"))
            .filter(type -> !Set.of("web_search", "web_search_preview", "search").contains(type))
            .sorted().toList();
        if (!unsupported.isEmpty()) {
            throw new IllegalArgumentException(
                "DeepSeek does not support tool types: " + String.join(", ", unsupported));
        }
        if ("expert".equals(request.model()) && requestMapper.search(request)) {
            throw OpenAiRequestException.conflict(
                "search_enabled", "DeepSeek expert model does not support web search");
        }
        DeepseekRequestMapper.prompt(request.messages());
    }

    @Override
    public void validateCredential(JsonNode credential) {
        if (credential.path("token").asText("").isBlank()
            || credential.path("device_id").asText("").isBlank()) {
            throw new IllegalArgumentException(
                "DeepSeek credential requires token and device_id");
        }
    }

    @Override public ProviderRetryPolicy retryPolicy() {
        return new ProviderRetryPolicy(3, Set.of("empty_model_response"));
    }

    @Override
    public Flux<CanonicalEvent> generate(
        CanonicalRequest request,
        ProviderExecutionContext context,
        LeasedProviderAccount account
    ) {
        var credential = DeepseekCredential.from(account);
        return Flux.usingWhen(
            transport.open(sessionCommand(credential, account.accountId() + ":" + request.requestId())),
            session -> createSession(session)
                .flatMapMany(sessionId -> createPow(session)
                    .flatMapMany(challenge -> Mono.fromCallable(() -> pow.solve(challenge))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMapMany(answer -> completion(
                            session, request, sessionId, challenge, answer)))),
            this::close,
            (session, ignored) -> close(session),
            this::close);
    }

    @Override
    public Mono<List<DiscoveredModel>> discoverModels(LeasedProviderAccount account) {
        var credential = DeepseekCredential.from(account);
        return Mono.usingWhen(
            transport.open(sessionCommand(credential, account.accountId() + ":catalog")),
            session -> transport.request(session.id(), request(
                    "GET",
                    "/api/v0/client/settings?did=" + url(credential.deviceId()) + "&scope=model",
                    null,
                    Map.of(),
                    120))
                .flatMap(response -> response.successful()
                    ? Mono.just(parseModels(json(response)))
                    : Mono.error(upstream(response))),
            this::close,
            (session, ignored) -> close(session),
            this::close);
    }

    static List<DiscoveredModel> parseModels(JsonNode root) {
        var items = root.path("data").path("biz_data").path("settings")
            .path("model_configs").path("value");
        if (!items.isArray()) return List.of();
        var result = new LinkedHashMap<String, DiscoveredModel>();
        for (var item : items) {
            if (!item.path("enabled").asBoolean(false)
                || !item.path("switchable").asBoolean(false)) continue;
            var id = item.path("model_type").asText("").trim();
            if (id.isBlank()) continue;
            var file = item.path("file_feature");
            var metadata = new LinkedHashMap<String, Object>();
            metadata.put("is_default", item.path("is_default").asBoolean(false));
            metadata.put("thinking", item.path("think_feature").isObject());
            metadata.put("search", item.path("search_feature").isObject());
            metadata.put("vision", file.path("vision").asBoolean(false));
            metadata.put("input_character_limit", item.path("input_character_limit").asLong(0));
            result.putIfAbsent(id, new DiscoveredModel(
                id, item.path("name").asText(id), metadata));
        }
        return List.copyOf(result.values());
    }

    private Mono<String> createSession(BrowserTransportClient.Session session) {
        return transport.request(session.id(), request(
                "POST", "/api/v0/chat_session/create", mapper.createObjectNode(), Map.of(), 120))
            .flatMap(response -> {
                if (!response.successful()) return Mono.error(upstream(response));
                var root = json(response);
                requireSuccess(root, "create session");
                var id = root.path("data").path("biz_data").path("chat_session")
                    .path("id").asText("").trim();
                return id.isBlank()
                    ? Mono.error(new DeepseekUpstreamException(
                        502, "DeepSeek create session returned no id"))
                    : Mono.just(id);
            });
    }

    private Mono<DeepseekPowSolver.Challenge> createPow(
        BrowserTransportClient.Session session
    ) {
        var body = mapper.createObjectNode().put("target_path", COMPLETION_PATH);
        return transport.request(session.id(), request(
                "POST", "/api/v0/chat/create_pow_challenge", body, Map.of(), 120))
            .flatMap(response -> {
                if (!response.successful()) return Mono.error(upstream(response));
                var root = json(response);
                requireSuccess(root, "create POW challenge");
                return Mono.just(DeepseekPowSolver.parse(root, "challenge"));
            });
    }

    private Flux<CanonicalEvent> completion(
        BrowserTransportClient.Session session,
        CanonicalRequest request,
        String sessionId,
        DeepseekPowSolver.Challenge challenge,
        int answer
    ) {
        var proof = mapper.createObjectNode()
            .put("algorithm", challenge.algorithm())
            .put("challenge", challenge.challenge())
            .put("salt", challenge.salt())
            .put("answer", answer)
            .put("signature", challenge.signature())
            .put("target_path", COMPLETION_PATH);
        var encoded = Base64.getEncoder().encodeToString(
            mapper.writeValueAsBytes(proof));
        var decoder = new DeepseekEventDecoder(request.requestId(), mapper);
        var sse = new SseDataDecoder();
        return transport.stream(session.id(), request(
                "POST", COMPLETION_PATH, requestMapper.prepare(request, sessionId),
                Map.of("X-DS-PoW-Response", encoded), 300))
            .concatMapIterable(sse::decode)
            .concatWith(Flux.defer(() -> Flux.fromIterable(sse.finish())))
            .concatMapIterable(decoder::decode)
            .concatWith(Flux.defer(() -> Flux.fromIterable(decoder.finish())));
    }

    private BrowserTransportClient.Request request(
        String method,
        String path,
        JsonNode body,
        Map<String, String> extraHeaders,
        int timeout
    ) {
        var headers = new LinkedHashMap<String, String>();
        if (body != null) headers.put("Content-Type", "application/json");
        headers.put("X-Client-Bundle-Id", properties.getBundleId());
        headers.put("X-Client-Platform", properties.getPlatform());
        headers.put("X-Client-Version", properties.getClientVersion());
        headers.put("X-Client-Locale", properties.getLocale());
        headers.put("X-Client-Timezone-Offset",
            Integer.toString(properties.getTimezoneOffsetSeconds()));
        headers.putAll(extraHeaders);
        return new BrowserTransportClient.Request(
            method, path, Map.copyOf(headers),
            BrowserTransportClient.FingerprintProfile.SAME_ORIGIN_FETCH,
            body, timeout, null, null, "/");
    }

    private BrowserTransportClient.OpenCommand sessionCommand(
        DeepseekCredential credential,
        String affinityKey
    ) {
        var origin = URI.create(properties.getBaseUrl());
        var proxyPool = proxyPools.runtimeForProvider(
            manifest().id(), ProxyTrafficScope.INFERENCE).orElse(Map.of());
        var userAgent = credential.userAgent().isBlank()
            ? properties.getUserAgent() : credential.userAgent();
        var browserProfile = credential.browserProfile().isBlank()
            ? properties.getBrowserProfile() : credential.browserProfile();
        return new BrowserTransportClient.OpenCommand(
            origin, Map.of(), List.of("." + origin.getHost()), userAgent,
            browserProfile, "v2", proxyPool, 300, List.of(), affinityKey,
            !proxyPool.isEmpty(), "", credential.token());
    }

    private JsonNode json(BrowserTransportClient.BufferedResponse response) {
        try {
            return mapper.readTree(response.text());
        } catch (RuntimeException error) {
            throw new DeepseekUpstreamException(502, "DeepSeek upstream returned invalid JSON");
        }
    }

    private void requireSuccess(JsonNode root, String operation) {
        var code = root.path("code").asInt(0);
        var bizCode = root.path("data").path("biz_code").asInt(0);
        if (code != 0 || bizCode != 0) {
            throw new DeepseekUpstreamException(400,
                "DeepSeek " + operation + " failed (code=" + code
                    + ", biz_code=" + bizCode + ")");
        }
    }

    private DeepseekUpstreamException upstream(
        BrowserTransportClient.BufferedResponse response
    ) {
        return new DeepseekUpstreamException(response.status(),
            "DeepSeek upstream returned HTTP " + response.status());
    }

    private Mono<Void> close(BrowserTransportClient.Session session) {
        return transport.close(session.id()).then();
    }

    private String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Override
    public ProviderFailure classify(Throwable error) {
        if (error instanceof DeepseekUpstreamException upstream) {
            return failure(upstream.status(), upstream.getMessage());
        }
        if (error instanceof BrowserTransportClient.BrowserTransportException upstream) {
            return failure(upstream.status(), upstream.getMessage());
        }
        return new ProviderFailure("provider_transport_error",
            error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(),
            true, Map.of());
    }

    private ProviderFailure failure(int status, String message) {
        var retryable = status >= 500 || List.of(408, 409, 425, 429).contains(status);
        var type = switch (status) {
            case 401, 403 -> "credential_rejected";
            case 429 -> "rate_limited";
            default -> "provider_upstream_error";
        };
        return new ProviderFailure(type, message, retryable, Map.of("status", status));
    }
}
