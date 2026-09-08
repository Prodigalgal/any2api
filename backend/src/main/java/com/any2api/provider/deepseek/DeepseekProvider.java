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
public final class DeepseekProvider implements InferenceProvider {
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

    private static final ProviderManifest MANIFEST = new ProviderManifest(
        "deepseek", "DeepSeek", "native-deepseek-web-v2.3", "2",
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

    private final OfficialBrowserTransportClient transport;
    private final OfficialBrowserSemanticCommandFactory semanticCommands;
    private final ProxyPoolService proxyPools;
    private final DeepseekProperties properties;
    private final ObjectMapper mapper;

    public DeepseekProvider(
        OfficialBrowserTransportClient transport,
        OfficialBrowserSemanticCommandFactory semanticCommands,
        ProxyPoolService proxyPools,
        DeepseekProperties properties,
        ObjectMapper mapper
    ) {
        this.transport = transport;
        this.semanticCommands = semanticCommands;
        this.proxyPools = proxyPools;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override public ProviderManifest manifest() { return MANIFEST; }

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
            throw new IllegalArgumentException("DeepSeek tool_choice supports only auto or none");
        }
        var unsupported = request.tools().stream()
            .map(tool -> tool.path("type").asText("function"))
            .filter(type -> !Set.of("web_search", "web_search_preview", "search").contains(type))
            .sorted().toList();
        if (!unsupported.isEmpty()) {
            throw new IllegalArgumentException(
                "DeepSeek does not support tool types: " + String.join(", ", unsupported));
        }
        if ("expert".equals(request.model()) && searchEnabled(request)) {
            throw OpenAiRequestException.conflict(
                "search_enabled", "DeepSeek expert model does not support web search");
        }
        requirePrompt(request);
    }

    @Override
    public void validateCredential(JsonNode credential) {
        if (credential.path("token").asText("").isBlank()
            && credential.path("access_token").asText("").isBlank()) {
            throw new IllegalArgumentException("DeepSeek credential requires token");
        }
        if (credential.path("device_id").asText("").isBlank()) {
            throw new IllegalArgumentException("DeepSeek credential requires device_id");
        }
    }

    @Override public ProviderRetryPolicy retryPolicy() {
        return ProviderRetryPolicy.standard(3);
    }

    @Override
    public Flux<CanonicalEvent> generate(
        CanonicalRequest request,
        ProviderExecutionContext context,
        LeasedProviderAccount account
    ) {
        validateCredential(account.credential());
        return Flux.defer(() -> {
            var decoder = new DeepseekEventDecoder(request.requestId(), mapper);
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
                        sink.error(new DeepseekUpstreamException(
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
                    ? Flux.error(new DeepseekUpstreamException(
                        status.get(), "DeepSeek upstream returned HTTP " + status.get()))
                    : Flux.fromIterable(decoder.finish())));
        });
    }

    @Override
    public Mono<List<DiscoveredModel>> discoverModels(LeasedProviderAccount account) {
        validateCredential(account.credential());
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
                    return Mono.error(new DeepseekUpstreamException(
                        response.status(), summarize(response.status(), response.body())));
                }
                try {
                    return Mono.just(parseModels(mapper.readTree(response.body())));
                } catch (RuntimeException error) {
                    return Mono.error(new DeepseekUpstreamException(
                        502, "DeepSeek model discovery returned invalid JSON"));
                }
            });
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

    @Override
    public ProviderFailure classify(Throwable error) {
        if (error instanceof DeepseekUpstreamException upstream) {
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
        return new ProviderFailure(
            "provider_transport_error",
            error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(),
            true,
            Map.of());
    }

    private boolean searchEnabled(CanonicalRequest request) {
        for (var name : List.of("search_enabled", "web_search", "enable_search", "search")) {
            var option = request.providerOptions().get(name);
            if (option instanceof Boolean value) return value;
            var raw = request.rawRequest().path(name);
            if (raw.isBoolean()) return raw.asBoolean();
        }
        return request.tools().stream().anyMatch(tool -> Set.of(
            "web_search", "web_search_preview", "search").contains(tool.path("type").asText("")));
    }

    private void requirePrompt(CanonicalRequest request) {
        var hasText = request.messages().stream().anyMatch(message -> {
            var content = message.path("content");
            return content.isTextual() && !content.asText().isBlank()
                || content.isArray() && content.size() > 0;
        });
        if (!hasText) throw new IllegalArgumentException("DeepSeek prompt is empty");
    }

    private Map<String, Object> proxyPool() {
        return proxyPools.runtimeForProvider(MANIFEST.id(), ProxyTrafficScope.INFERENCE)
            .orElse(Map.of());
    }

    private Map<String, Object> runtimeOptions() {
        return Map.of(
            "base_url", properties.getBaseUrl(),
            "bundle_id", properties.getBundleId(),
            "platform", properties.getPlatform(),
            "client_version", properties.getClientVersion(),
            "locale", properties.getLocale(),
            "timezone_offset", properties.getTimezoneOffsetSeconds());
    }

    private static String proxyAffinityKey(LeasedProviderAccount account) {
        var persisted = account.credential().path("proxy_affinity_key").asText("").trim();
        return persisted.isBlank() ? account.accountId().toString() : persisted;
    }

    private String summarize(int status, String body) {
        var compact = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        if (compact.length() > 1000) compact = compact.substring(0, 1000);
        return compact.isBlank()
            ? "DeepSeek upstream returned HTTP " + status
            : "DeepSeek upstream returned HTTP " + status + ": " + compact;
    }
}
