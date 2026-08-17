package com.any2api.provider.mimo;

import com.any2api.account.LeasedProviderAccount;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.protocol.OpenAiRequestException;
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
import com.any2api.transport.OfficialBrowserTransportClient;
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
            "temperature", "top_p", "max_tokens", "max_completion_tokens",
            "max_output_tokens", "reasoning", "reasoning_effort", "thinking",
            "web_search_status", "tools", "tool_choice", "parallel_tool_calls"),
        java.util.Set.of(
            "temperature", "top_p", "max_tokens", "max_completion_tokens",
            "max_output_tokens", "reasoning", "reasoning_effort", "thinking",
            "web_search_status", "tools", "tool_choice", "parallel_tool_calls"),
        java.util.Set.of("function"));
    private final BrowserTransportClient transport;
    private final OfficialBrowserTransportClient officialTransport;
    private final ProxyPoolService proxyPools;
    private final MimoProperties properties;
    private final MimoRequestMapper requestMapper;
    private final MimoMediaUploader mediaUploader;
    private final ObjectMapper mapper;

    public MimoProvider(
        BrowserTransportClient transport,
        OfficialBrowserTransportClient officialTransport,
        ProxyPoolService proxyPools,
        MimoProperties properties,
        MimoRequestMapper requestMapper,
        MimoMediaUploader mediaUploader,
        ObjectMapper mapper
    ) {
        this.transport = transport;
        this.officialTransport = officialTransport;
        this.proxyPools = proxyPools;
        this.properties = properties;
        this.requestMapper = requestMapper;
        this.mediaUploader = mediaUploader;
        this.mapper = mapper;
    }

    @Override
    public ProviderManifest manifest() {
        return new ProviderManifest("mimo", "MiMo", "official-browser-mimo-web-v1", "3",
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
        requireNonBindingOutputLimit(request);
    }

    private void requireNonBindingOutputLimit(CanonicalRequest request) {
        for (var field : List.of("max_tokens", "max_completion_tokens", "max_output_tokens")) {
            if (!(request.generation().get(field) instanceof Number number)) continue;
            var requested = number.longValue();
            if (requested < properties.getWebOutputTokenCeiling()) {
                throw OpenAiRequestException.unsupported(field,
                    "MiMo Web cannot enforce " + field + " below its "
                        + properties.getWebOutputTokenCeiling() + " token output ceiling");
            }
            // The official Web request has no output-limit field. A ceiling at or above the
            // provider maximum is non-binding, so omitting it preserves the requested bound.
            return;
        }
    }

    @Override
    public Flux<CanonicalEvent> generate(
        CanonicalRequest request,
        ProviderExecutionContext context,
        LeasedProviderAccount account
    ) {
        var credential = MimoCredential.from(account);
        var prepared = requestMapper.prepare(request);
        var affinityKey = proxyAffinityKey(account);
        var proxyPool = proxyPool();
        return uploadMedia(credential, prepared, request.model(), affinityKey)
            .flatMapMany(media -> Flux.defer(() -> {
                var upstreamMedia = prepared.body().putArray("multiMedias");
                media.forEach(upstreamMedia::add);
                return streamOfficial(
                    account.credential(),
                    prepared,
                    request.requestId(),
                    proxyPool,
                    affinityKey,
                    context);
            }));
    }

    @Override
    public Mono<List<DiscoveredModel>> discoverModels(LeasedProviderAccount account) {
        MimoCredential.from(account);
        return officialTransport.request(
                manifest().id(),
                "GET",
                "/open-apis/bot/config",
                "",
                account.credential(),
                proxyPool(),
                proxyAffinityKey(account))
            .flatMap(response -> responseJson(response, null))
            .map(this::parseModels);
    }

    private List<DiscoveredModel> parseModels(JsonNode body) {
        var excluded = java.util.regex.Pattern.compile(
            "(?:tts|asr|speech|audio|voice(?:clone|design)?)",
            java.util.regex.Pattern.CASE_INSENSITIVE);
        var models = new java.util.LinkedHashMap<String, DiscoveredModel>();
        var items = body.path("data").path("modelConfigListNg");
        if (!items.isArray() || items.isEmpty()) {
            items = body.path("data").path("modelConfigList");
        }
        for (var item : items) {
            var id = item.path("model").asText("").trim();
            if (!id.isBlank() && !excluded.matcher(id).find()) {
                models.putIfAbsent(id, new DiscoveredModel(id, id,
                    Map.of("mimo", item.deepCopy())));
            }
        }
        return List.copyOf(models.values());
    }

    private Mono<List<tools.jackson.databind.node.ObjectNode>> uploadMedia(
        MimoCredential credential,
        MimoPreparedRequest prepared,
        String model,
        String affinityKey
    ) {
        if (prepared.media().isEmpty()) return Mono.just(List.of());
        return Mono.usingWhen(
            transport.open(sessionCommand(credential, affinityKey)),
            session -> mediaUploader.upload(session, credential, prepared.media(), model),
            this::close,
            (session, ignored) -> close(session),
            this::close);
    }

    private Flux<CanonicalEvent> streamOfficial(
        JsonNode rawCredential,
        MimoPreparedRequest prepared,
        String requestId,
        Map<String, Object> proxyPool,
        String affinityKey,
        ProviderExecutionContext context
    ) {
        var body = mapper.writeValueAsString(prepared.body());
        return Flux.defer(() -> {
            var decoder = new MimoEventDecoder(
                requestId,
                prepared.tools(),
                prepared.toolRequired(),
                prepared.parallelToolCalls());
            var status = new java.util.concurrent.atomic.AtomicInteger(-1);
            return officialTransport.stream(
                    manifest().id(),
                    "POST",
                    "/open-apis/bot/chat",
                    body,
                    rawCredential,
                    proxyPool,
                    affinityKey)
                .handle((frame, sink) -> {
                    var type = frame.path("type").asText("");
                    if ("status".equals(type)) {
                        status.set(frame.path("status").asInt(502));
                    } else if ("error".equals(type)) {
                        var code = status.get() < 0 ? 502 : status.get();
                        sink.error(new MimoUpstreamException(
                            code,
                            summarize(code, frame.path("data").asText(""))));
                    } else if ("data".equals(type) && status.get() < 400) {
                        sink.next(frame.path("data").asText(""));
                    } else if ("credential_patch".equals(type)) {
                        context.acceptCredentialPatch(frame.path("data"));
                    }
                })
                .cast(String.class)
                .takeUntil(data -> "[DONE]".equals(data.trim()))
                .concatMapIterable(decoder::decode)
                .concatWith(Flux.defer(() -> status.get() >= 400
                    ? Flux.error(new MimoUpstreamException(
                        status.get(),
                        "MiMo upstream returned HTTP " + status.get()))
                    : Flux.fromIterable(decoder.finish())));
        });
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

    private Mono<JsonNode> responseJson(
        OfficialBrowserTransportClient.TransportResponse response,
        ProviderExecutionContext context
    ) {
        if (context != null) context.acceptCredentialPatch(response.credentialPatch());
        if (response.status() < 200 || response.status() >= 300) {
            return Mono.error(new MimoUpstreamException(
                response.status(),
                summarize(response.status(), response.body())));
        }
        try {
            return Mono.just(mapper.readTree(response.body()));
        } catch (RuntimeException error) {
            return Mono.error(new MimoUpstreamException(
                502,
                "MiMo upstream returned invalid JSON"));
        }
    }

    private Mono<Void> close(BrowserTransportClient.Session session) {
        return transport.close(session.id()).then();
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
