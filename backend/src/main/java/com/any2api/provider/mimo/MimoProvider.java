package com.any2api.provider.mimo;

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
import com.any2api.provider.ProviderRequestValidation;
import com.any2api.provider.RandomModelRole;
import com.any2api.provider.SupportLevel;
import com.any2api.proxy.ProxyPoolService;
import com.any2api.proxy.ProxyTrafficScope;
import com.any2api.transport.BrowserTransportClient;
import com.any2api.transport.SseDataDecoder;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public final class MimoProvider implements InferenceProvider {
    private static final ProviderProtocolContract PROTOCOL = new ProviderProtocolContract(
        Map.of(
            "conversation_id", ProviderProtocolContract.OptionType.STRING,
            "thinking", ProviderProtocolContract.OptionType.BOOLEAN,
            "web_search_status", ProviderProtocolContract.OptionType.STRING),
        java.util.Set.of(
            "temperature", "top_p", "reasoning", "reasoning_effort", "thinking",
            "web_search_status", "tools", "tool_choice", "parallel_tool_calls"),
        java.util.Set.of(
            "temperature", "top_p", "reasoning", "reasoning_effort", "thinking",
            "web_search_status", "tools", "tool_choice", "parallel_tool_calls"),
        java.util.Set.of("function"));
    private final BrowserTransportClient transport;
    private final ProxyPoolService proxyPools;
    private final MimoProperties properties;
    private final MimoRequestMapper requestMapper;
    private final MimoMediaUploader mediaUploader;
    private final ObjectMapper mapper;

    public MimoProvider(
        BrowserTransportClient transport,
        ProxyPoolService proxyPools,
        MimoProperties properties,
        MimoRequestMapper requestMapper,
        MimoMediaUploader mediaUploader,
        ObjectMapper mapper
    ) {
        this.transport = transport;
        this.proxyPools = proxyPools;
        this.properties = properties;
        this.requestMapper = requestMapper;
        this.mediaUploader = mediaUploader;
        this.mapper = mapper;
    }

    @Override
    public ProviderManifest manifest() {
        return new ProviderManifest("mimo", "MiMo", "native-mimo-web-v1", "2",
            List.of("mimo-v2.5-pro", "mimo-v2.5"), Map.of(
                ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE,
                ProviderCapability.RESPONSES, SupportLevel.NATIVE,
                ProviderCapability.STREAMING, SupportLevel.NATIVE,
                ProviderCapability.REASONING, SupportLevel.NATIVE,
                ProviderCapability.FUNCTION_TOOLS, SupportLevel.EMULATED,
                ProviderCapability.IMAGE_INPUT, SupportLevel.NATIVE,
                ProviderCapability.MODEL_DISCOVERY, SupportLevel.NATIVE,
                ProviderCapability.ACCOUNT_KEEPALIVE, SupportLevel.NATIVE,
                ProviderCapability.REGISTRATION, SupportLevel.NATIVE,
                ProviderCapability.REAUTHENTICATION, SupportLevel.NATIVE),
            Map.of(
                RandomModelRole.TOP_TEXT, List.of("mimo-v2.5-pro"),
                RandomModelRole.TOP_MULTIMODAL, List.of("mimo-v2.5")), true);
    }

    @Override
    public ProviderProtocolContract protocolContract() {
        return PROTOCOL;
    }

    @Override
    public void validate(CanonicalRequest request) {
        ProviderRequestValidation.requireBooleanParameters(request, "thinking");
        ProviderRequestValidation.requireStringParameters(request, "web_search_status");
        ProviderRequestValidation.requireReasoningBooleanConsistency(
            request, "thinking", java.util.Set.of("none", "minimal"), "thinking");
    }

    @Override
    public Flux<CanonicalEvent> generate(
        CanonicalRequest request,
        ProviderExecutionContext context,
        LeasedProviderAccount account
    ) {
        var credential = MimoCredential.from(account);
        var prepared = requestMapper.prepare(request);
        var affinityKey = account.accountId() + ":" + request.requestId();
        return Flux.usingWhen(
            transport.open(sessionCommand(credential, affinityKey)),
            session -> mediaUploader.upload(
                    session, credential, prepared.media(), request.model())
                .flatMapMany(media -> Flux.defer(() -> {
                    var upstreamMedia = prepared.body().putArray("multiMedias");
                    media.forEach(upstreamMedia::add);
                    var decoder = new MimoEventDecoder(
                        request.requestId(), prepared.tools(), prepared.toolRequired(),
                        prepared.parallelToolCalls());
                    var sse = new SseDataDecoder();
                    return transport.stream(session.id(), request(
                            "POST", chatPath(credential), prepared.body(), 300))
                        .concatMapIterable(sse::decode)
                        .takeUntil(data -> "[DONE]".equals(data.trim()))
                        .concatWith(Flux.defer(() -> Flux.fromIterable(sse.finish())))
                        .concatMapIterable(decoder::decode)
                        .concatWith(Flux.defer(() -> Flux.fromIterable(decoder.finish())));
                })),
            this::close,
            (session, ignored) -> close(session),
            this::close);
    }

    @Override
    public Mono<List<DiscoveredModel>> discoverModels(LeasedProviderAccount account) {
        var credential = MimoCredential.from(account);
        return Mono.usingWhen(
            transport.open(sessionCommand(credential, account.accountId() + ":catalog")),
            session -> transport.request(session.id(), request(
                    "GET", configPath(credential), null, 120))
                .flatMap(response -> response.successful()
                    ? Mono.just(parseModels(json(response)))
                    : Mono.error(new MimoUpstreamException(response.status(),
                        summarize(response.status(), response.text())))),
            this::close,
            (session, ignored) -> close(session),
            this::close);
    }

    private List<DiscoveredModel> parseModels(JsonNode body) {
        var excluded = java.util.regex.Pattern.compile(
            "(?:tts|asr|speech|audio|voice(?:clone|design)?)",
            java.util.regex.Pattern.CASE_INSENSITIVE);
        var models = new java.util.LinkedHashMap<String, DiscoveredModel>();
        for (var item : body.path("data").path("modelConfigList")) {
            var id = item.path("model").asText("").trim();
            if (!id.isBlank() && !excluded.matcher(id).find()) {
                models.putIfAbsent(id, new DiscoveredModel(id, id,
                    Map.of("mimo", item.deepCopy())));
            }
        }
        return List.copyOf(models.values());
    }

    private BrowserTransportClient.OpenCommand sessionCommand(
        MimoCredential credential,
        String affinityKey
    ) {
        var origin = URI.create(properties.getBaseUrl());
        var proxyPool = proxyPools.runtimeForProvider(
            manifest().id(), ProxyTrafficScope.INFERENCE).orElse(Map.of());
        var userAgent = credential.userAgent().isBlank()
            ? properties.getUserAgent() : credential.userAgent();
        var browserProfile = credential.browserProfile().isBlank()
            ? "chrome146" : credential.browserProfile();
        return new BrowserTransportClient.OpenCommand(
            origin, credential.cookies(), List.of("." + origin.getHost()),
            userAgent, browserProfile, "v2", proxyPool, 300, List.of(),
            affinityKey, !proxyPool.isEmpty(), "");
    }

    private BrowserTransportClient.Request request(
        String method,
        String path,
        JsonNode body,
        int timeout
    ) {
        return new BrowserTransportClient.Request(
            method, path, Map.of("x-timezone", properties.getTimezone()),
            BrowserTransportClient.FingerprintProfile.SAME_ORIGIN_FETCH,
            body, timeout);
    }

    private String chatPath(MimoCredential credential) {
        return path("/open-apis/bot/chat", credential);
    }

    private String configPath(MimoCredential credential) {
        return path("/open-apis/bot/config", credential);
    }

    private String path(String value, MimoCredential credential) {
        return org.springframework.web.util.UriComponentsBuilder.fromPath(value)
            .queryParam("xiaomichatbot_ph", credential.phase())
            .build().encode().toUriString();
    }

    private JsonNode json(BrowserTransportClient.BufferedResponse response) {
        try {
            return mapper.readTree(response.text());
        } catch (RuntimeException error) {
            throw new MimoUpstreamException(502, "MiMo upstream returned invalid JSON");
        }
    }

    private Mono<Void> close(BrowserTransportClient.Session session) {
        return transport.close(session.id()).then();
    }

    @Override
    public ProviderFailure classify(Throwable error) {
        if (error instanceof MimoUpstreamException upstream) {
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
        if (error instanceof BrowserTransportClient.BrowserTransportException upstream) {
            var retryable = upstream.status() >= 500
                || List.of(408, 409, 425, 429).contains(upstream.status());
            var type = switch (upstream.status()) {
                case 401, 403 -> "credential_rejected";
                case 429 -> "rate_limited";
                default -> "provider_transport_error";
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
        return compact.isBlank()
            ? "MiMo upstream returned HTTP " + status
            : "MiMo upstream returned HTTP " + status + ": " + compact;
    }
}
