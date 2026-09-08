package com.any2api.provider.grok;

import com.any2api.account.LeasedProviderAccount;
import com.any2api.observability.RequestCorrelation;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.protocol.OpenAiSseEventDecoder;
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
import com.any2api.proxy.ProxyPoolService;
import com.any2api.proxy.ProxyTrafficScope;
import com.any2api.transport.OfficialBrowserSemanticCommandFactory;
import com.any2api.transport.OfficialBrowserTransportClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public final class GrokProvider implements InferenceProvider {
    private static final ProviderProtocolContract PROTOCOL = new ProviderProtocolContract(
        Map.of("skip_x_search", ProviderProtocolContract.OptionType.BOOLEAN),
        Set.of(
            "temperature", "top_p", "max_tokens", "max_completion_tokens",
            "max_output_tokens", "reasoning", "reasoning_effort", "tools", "tool_choice",
            "parallel_tool_calls", "prompt_cache_key", "user", "instructions",
            "stream_tool_calls", "conversation_id", "conversation", "thread_id", "session_id",
            "metadata", "_skip_x_search"),
        Set.of(
            "background", "context_management", "conversation", "include", "max_output_tokens",
            "max_tool_calls", "moderation", "parallel_tool_calls", "previous_response_id", "prompt",
            "prompt_cache_key", "prompt_cache_options", "reasoning", "safety_identifier",
            "service_tier", "store", "temperature", "tools", "tool_choice", "top_logprobs",
            "top_p", "truncation", "user"),
        Set.of("function", "x_search"),
        Set.of("effort", "summary"));

    private static final ProviderManifest MANIFEST = new ProviderManifest(
        "grok",
        "Grok",
        "xai-cli-responses-v1",
        "3",
        List.of("grok-4.5"),
        Map.ofEntries(
            Map.entry(ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE),
            Map.entry(ProviderCapability.RESPONSES, SupportLevel.NATIVE),
            Map.entry(ProviderCapability.STREAMING, SupportLevel.NATIVE),
            Map.entry(ProviderCapability.FUNCTION_TOOLS, SupportLevel.NATIVE),
            Map.entry(ProviderCapability.REASONING, SupportLevel.NATIVE),
            Map.entry(ProviderCapability.IMAGE_INPUT, SupportLevel.NATIVE),
            Map.entry(ProviderCapability.FILE_INPUT, SupportLevel.NATIVE),
            Map.entry(ProviderCapability.MODEL_DISCOVERY, SupportLevel.NATIVE),
            Map.entry(ProviderCapability.ACCOUNT_KEEPALIVE, SupportLevel.NATIVE),
            Map.entry(ProviderCapability.REGISTRATION, SupportLevel.NATIVE),
            Map.entry(ProviderCapability.REAUTHENTICATION, SupportLevel.NATIVE)),
        Map.of(RandomModelRole.TOP_TEXT, List.of("grok-4.5")),
        true);

    private final OfficialBrowserTransportClient transport;
    private final OfficialBrowserSemanticCommandFactory semanticCommands;
    private final GrokProperties properties;
    private final ProxyPoolService proxyPools;
    private final ObjectMapper objectMapper;

    public GrokProvider(
        OfficialBrowserTransportClient transport,
        OfficialBrowserSemanticCommandFactory semanticCommands,
        GrokProperties properties,
        ProxyPoolService proxyPools,
        ObjectMapper objectMapper
    ) {
        this.transport = transport;
        this.semanticCommands = semanticCommands;
        this.properties = properties;
        this.proxyPools = proxyPools;
        this.objectMapper = objectMapper;
    }

    @Override public ProviderManifest manifest() { return MANIFEST; }

    @Override public ProviderProtocolContract protocolContract() { return PROTOCOL; }

    @Override
    public void validate(CanonicalRequest request) {
        ProviderRequestValidation.requireBooleanParameters(request, "_skip_x_search");
    }

    @Override
    public Flux<CanonicalEvent> generate(
        CanonicalRequest request,
        ProviderExecutionContext context,
        LeasedProviderAccount account
    ) {
        return Flux.defer(() -> {
            var decoder = new OpenAiSseEventDecoder(objectMapper, request.requestId());
            var status = new AtomicInteger(-1);
            return transport.stream(
                    MANIFEST.id(),
                    "chat",
                    semanticCommands.chat(request),
                    account.credential(),
                    proxyPool(),
                    proxyAffinityKey(account),
                    runtimeOptions())
                .handle((frame, sink) -> {
                    var type = frame.path("type").asText("");
                    if ("status".equals(type)) {
                        status.set(frame.path("status").asInt(502));
                    } else if ("error".equals(type)) {
                        var code = status.get() < 0 ? 502 : status.get();
                        sink.error(new GrokUpstreamException(
                            code, summarize(code, frame.path("data").asText(""))));
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
                    ? Flux.error(new GrokUpstreamException(
                        status.get(), "Grok upstream returned HTTP " + status.get()))
                    : Flux.fromIterable(decoder.finish())));
        });
    }

    @Override
    public Mono<List<DiscoveredModel>> discoverModels(LeasedProviderAccount account) {
        return transport.request(
                MANIFEST.id(),
                "models",
                semanticCommands.models(),
                account.credential(),
                proxyPool(),
                proxyAffinityKey(account),
                runtimeOptions())
            .flatMap(response -> {
                if (response.status() < 200 || response.status() >= 300) {
                    return Mono.error(new GrokUpstreamException(
                        response.status(), summarize(response.status(), response.body())));
                }
                try {
                    return Mono.just(parseModels(objectMapper.readTree(response.body())));
                } catch (RuntimeException error) {
                    return Mono.error(new GrokUpstreamException(
                        502, "Grok model discovery returned invalid JSON"));
                }
            });
    }

    static List<DiscoveredModel> parseModels(JsonNode body) {
        var models = new java.util.ArrayList<DiscoveredModel>();
        for (var item : body.path("data")) {
            var id = item.path("id").asText("").trim();
            if (id.isBlank()) continue;
            var metadata = new LinkedHashMap<String, Object>();
            if (item.has("created")) metadata.put("created", item.path("created").asLong());
            if (item.has("owned_by")) metadata.put("owned_by", item.path("owned_by").asText());
            models.add(new DiscoveredModel(id, id, metadata));
        }
        return List.copyOf(models);
    }

    @Override
    public ProviderFailure classify(Throwable error) {
        if (error instanceof GrokUpstreamException upstream) {
            var retryable = upstream.status() >= 500
                || List.of(403, 408, 409, 425, 429).contains(upstream.status());
            var type = switch (upstream.status()) {
                case 401 -> "credential_rejected";
                case 403 -> "permission_denied_unknown";
                case 429 -> "rate_limited";
                default -> "provider_upstream_error";
            };
            var detail = new LinkedHashMap<String, Object>();
            detail.put("status", upstream.status());
            if (upstream.status() == 403) {
                detail.put("attribution", "unknown");
                detail.put("candidates", List.of("account", "email_domain", "egress_ip"));
            }
            return new ProviderFailure(type, upstream.getMessage(), retryable, Map.copyOf(detail));
        }
        return new ProviderFailure(
            "provider_transport_error",
            error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(),
            true,
            Map.of());
    }

    private Map<String, Object> proxyPool() {
        return proxyPools.runtimeForProvider(MANIFEST.id(), ProxyTrafficScope.INFERENCE)
            .orElse(Map.of());
    }

    private Map<String, Object> runtimeOptions() {
        return Map.of(
            "base_url", trimTrailingSlash(properties.getBaseUrl().toString()),
            "token_auth", properties.getTokenAuth(),
            "client_version", properties.getClientVersion(),
            "client_identifier", properties.getClientIdentifier());
    }

    private String proxyAffinityKey(LeasedProviderAccount account) {
        var persisted = account.credential().path("proxy_affinity_key").asText("").trim();
        return persisted.isBlank() ? account.accountId().toString() : persisted;
    }

    private String summarize(int status, String body) {
        var compact = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        if (compact.length() > 1000) compact = compact.substring(0, 1000);
        return compact.isBlank()
            ? "Grok upstream returned HTTP " + status
            : "Grok upstream returned HTTP " + status + ": " + compact;
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
