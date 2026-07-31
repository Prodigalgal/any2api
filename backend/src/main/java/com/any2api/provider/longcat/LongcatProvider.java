package com.any2api.provider.longcat;

import com.any2api.account.LeasedProviderAccount;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.provider.InferenceProvider;
import com.any2api.provider.ProviderCapability;
import com.any2api.provider.ProviderExecutionContext;
import com.any2api.provider.ProviderFailure;
import com.any2api.provider.ProviderManifest;
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
import java.util.Set;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public final class LongcatProvider implements InferenceProvider {
    private final BrowserTransportClient transport;
    private final ProxyPoolService proxyPools;
    private final LongcatProperties properties;
    private final LongcatRequestMapper requestMapper;
    private final LongcatToolProtocol toolProtocol;
    private final ObjectMapper mapper;

    public LongcatProvider(
        BrowserTransportClient transport,
        ProxyPoolService proxyPools,
        LongcatProperties properties,
        LongcatRequestMapper requestMapper,
        LongcatToolProtocol toolProtocol,
        ObjectMapper mapper
    ) {
        this.transport = transport;
        this.proxyPools = proxyPools;
        this.properties = properties;
        this.requestMapper = requestMapper;
        this.toolProtocol = toolProtocol;
        this.mapper = mapper;
    }

    @Override
    public ProviderManifest manifest() {
        return new ProviderManifest("longcat", "LongCat", "native-longcat-web-v1", "1",
            List.of("longcat-flash", "longcat-thinking", "longcat-search",
                "longcat-reason-search", "longcat-pro"),
            Map.of(
                ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE,
                ProviderCapability.RESPONSES, SupportLevel.NATIVE,
                ProviderCapability.STREAMING, SupportLevel.NATIVE,
                ProviderCapability.FUNCTION_TOOLS, SupportLevel.EMULATED,
                ProviderCapability.REASONING, SupportLevel.NATIVE,
                ProviderCapability.ACCOUNT_KEEPALIVE, SupportLevel.NATIVE,
                ProviderCapability.REGISTRATION, SupportLevel.NATIVE,
                ProviderCapability.REAUTHENTICATION, SupportLevel.NATIVE),
            Map.of(RandomModelRole.TOP_TEXT, List.of("longcat-pro")), true);
    }

    @Override
    public void validate(CanonicalRequest request) {
        ProviderRequestValidation.requireKnownOptions(request, Set.of(
            "agent_id", "reason_enabled", "search_enabled"));
        ProviderRequestValidation.requireKnownGenerationParameters(request, Set.of(
            "tool_choice", "parallel_tool_calls"));
        toolProtocol.plan(request);
    }

    @Override
    public Flux<CanonicalEvent> generate(
        CanonicalRequest request,
        ProviderExecutionContext context,
        LeasedProviderAccount account
    ) {
        var credential = LongcatCredential.from(account);
        var prepared = requestMapper.prepare(request);
        var affinityKey = account.accountId() + ":" + request.requestId();
        return Flux.usingWhen(
            transport.open(sessionCommand(credential, affinityKey)),
            session -> createSession(session, prepared.agentId())
            .flatMapMany(conversationId -> {
                var decoder = new LongcatEventDecoder(request.requestId(), prepared.reasonEnabled(),
                    prepared.toolPlan(), toolProtocol);
                var sse = new SseDataDecoder();
                var body = prepared.chatBody(conversationId);
                return transport.stream(session.id(), request(
                        "/api/v1/chat-completion-V2", body, 300))
                    .concatMapIterable(sse::decode)
                    .concatWith(Flux.defer(() -> Flux.fromIterable(sse.finish())))
                    .concatMapIterable(decoder::decode)
                    .concatWith(Flux.defer(() -> Flux.fromIterable(decoder.finish())));
            }),
            session -> close(session),
            (session, ignored) -> close(session),
            this::close);
    }

    private Mono<String> createSession(BrowserTransportClient.Session session, String agentId) {
        var body = mapper.createObjectNode().put("model", "").put("agentId", agentId);
        return transport.request(session.id(), request("/api/v1/session-create", body, 120))
            .flatMap(response -> {
                JsonNode value;
                try {
                    value = mapper.readTree(response.text());
                } catch (RuntimeException error) {
                    return Mono.error(new LongcatUpstreamException(502,
                        "LongCat session-create returned invalid JSON"));
                }
                if (!response.successful() || value.path("code").asInt(-1) != 0) {
                    return Mono.error(new LongcatUpstreamException(response.status(),
                        summarize(response.status(), value.toString())));
                }
                var id = value.path("data").path("conversationId").asText("").trim();
                return id.isBlank()
                    ? Mono.error(new LongcatUpstreamException(
                        502, "LongCat session-create returned no conversationId"))
                    : Mono.just(id);
            });
    }

    private BrowserTransportClient.Request request(String path, JsonNode body, int timeout) {
        return new BrowserTransportClient.Request(
            "POST", path, Map.of(
                "m-appkey", properties.getAppKey(),
                "m-traceid", Long.toString(System.currentTimeMillis()),
                "x-client-language", properties.getLanguage(),
                "x-requested-with", properties.getRequestedWith()),
            BrowserTransportClient.FingerprintProfile.SAME_ORIGIN_FETCH,
            body, timeout, null, null, "/t");
    }

    private BrowserTransportClient.OpenCommand sessionCommand(
        LongcatCredential credential,
        String affinityKey
    ) {
        var origin = URI.create(properties.getBaseUrl());
        var proxyPool = proxyPools.runtimeForProvider(
            manifest().id(), ProxyTrafficScope.INFERENCE).orElse(Map.of());
        return new BrowserTransportClient.OpenCommand(
            origin, credential.cookies(), List.of("." + origin.getHost()),
            properties.getUserAgent(), "chrome146", "v2", proxyPool, 300,
            List.of(), affinityKey, !proxyPool.isEmpty(), "");
    }

    private Mono<Void> close(BrowserTransportClient.Session session) {
        return transport.close(session.id()).then();
    }

    @Override
    public ProviderFailure classify(Throwable error) {
        if (error instanceof LongcatUpstreamException upstream) {
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
            ? "LongCat upstream returned HTTP " + status
            : "LongCat upstream returned HTTP " + status + ": " + compact;
    }
}
