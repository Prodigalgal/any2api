package com.any2api.provider.qwen;

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
import com.any2api.provider.ProviderRetryPolicy;
import com.any2api.provider.ProviderTransportMode;
import com.any2api.provider.RandomModelRole;
import com.any2api.provider.SupportLevel;
import com.any2api.proxy.ProxyPoolService;
import com.any2api.proxy.ProxyTrafficScope;
import com.any2api.transport.OfficialBrowserSemanticCommandFactory;
import com.any2api.transport.OfficialBrowserTransportClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public final class QwenProvider implements InferenceProvider {
    private static final ProviderProtocolContract PROTOCOL = new ProviderProtocolContract(
        Map.of(
            "thinking_mode", ProviderProtocolContract.OptionType.STRING,
            "thinking_budget", ProviderProtocolContract.OptionType.INTEGER,
            "web_search", ProviderProtocolContract.OptionType.BOOLEAN),
        Set.of(
            "temperature", "top_p", "max_tokens", "max_completion_tokens",
            "max_output_tokens", "reasoning", "reasoning_effort", "thinking_mode",
            "enable_thinking", "thinking_budget", "web_search", "enable_search", "search",
            "tools", "tool_choice"),
        Set.of(
            "temperature", "top_p", "max_tokens", "max_completion_tokens",
            "max_output_tokens", "reasoning", "reasoning_effort", "thinking_mode",
            "enable_thinking", "thinking_budget", "web_search", "enable_search", "search",
            "tools", "tool_choice"),
        Set.of("web_search", "web_search_preview", "search"));
    private final OfficialBrowserTransportClient transport;
    private final OfficialBrowserSemanticCommandFactory semanticCommands;
    private final ProxyPoolService proxyPools;
    private final QwenProperties properties;
    private final ObjectMapper mapper;

    public QwenProvider(
        OfficialBrowserTransportClient transport,
        OfficialBrowserSemanticCommandFactory semanticCommands,
        ProxyPoolService proxyPools,
        QwenProperties properties,
        ObjectMapper mapper
    ) {
        this.transport = transport;
        this.semanticCommands = semanticCommands;
        this.proxyPools = proxyPools;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    public ProviderManifest manifest() {
        return new ProviderManifest("qwen", "Qwen", "native-qwen-web-v2.1", "2",
            List.of("qwen3.7-plus"), Map.of(
                ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE,
                ProviderCapability.RESPONSES, SupportLevel.NATIVE,
                ProviderCapability.STREAMING, SupportLevel.NATIVE,
                ProviderCapability.REASONING, SupportLevel.NATIVE,
                ProviderCapability.IMAGE_INPUT, SupportLevel.NATIVE,
                ProviderCapability.MODEL_DISCOVERY, SupportLevel.NATIVE,
                ProviderCapability.ACCOUNT_KEEPALIVE, SupportLevel.NATIVE,
                ProviderCapability.REGISTRATION, SupportLevel.NATIVE,
                ProviderCapability.REAUTHENTICATION, SupportLevel.NATIVE),
            Map.of(RandomModelRole.TOP_TEXT, List.of("qwen3.7-plus")), true);
    }

    @Override
    public ProviderProtocolContract protocolContract() {
        return PROTOCOL;
    }

    @Override
    public Set<ProviderTransportMode> supportedTransportModes() {
        return Set.of(ProviderTransportMode.API, ProviderTransportMode.RUNTIME);
    }

    @Override
    public Duration modelProbeTimeout() {
        return properties.getModelProbeTimeout();
    }

    @Override
    public Duration accountProbeTimeout() {
        return properties.getModelProbeTimeout();
    }

    @Override
    public void validate(CanonicalRequest request) {
        ProviderRequestValidation.requireInlineImageUploads(request, "Qwen");
        ProviderRequestValidation.requireStringParameters(request, "thinking_mode");
        ProviderRequestValidation.requireBooleanParameters(
            request, "enable_thinking", "web_search", "enable_search", "search");
        ProviderRequestValidation.requirePositiveIntegerParameters(request, "thinking_budget");
        ProviderRequestValidation.requireEnumParameter(
            request, "thinking_mode", Set.of("Auto", "Thinking", "Fast"));
        ProviderRequestValidation.requireProviderOptionEnum(
            request, "thinking_mode", Set.of("Auto", "Thinking", "Fast"));
        ProviderRequestValidation.requirePositiveIntegerProviderOption(
            request, "thinking_budget");
        ProviderRequestValidation.requireConsistentBooleanAliases(
            request, "web_search", "web_search", "enable_search", "search");
        validateThinkingAliases(request);
        var toolChoice = request.rawRequest().path("tool_choice");
        if (!toolChoice.isMissingNode() && !toolChoice.isNull()
            && (!toolChoice.isTextual()
                || !Set.of("auto", "none").contains(toolChoice.asText().toLowerCase()))) {
            throw new IllegalArgumentException(
                "Qwen tool_choice supports only auto or none for search tools");
        }
        var unsupportedTools = request.tools().stream()
            .map(tool -> tool.path("type").asText("function"))
            .filter(type -> !Set.of("web_search", "web_search_preview", "search").contains(type))
            .sorted()
            .toList();
        if (!unsupportedTools.isEmpty()) {
            throw new IllegalArgumentException(
                "Qwen does not support tool types: " + String.join(", ", unsupportedTools));
        }
    }

    private void validateThinkingAliases(CanonicalRequest request) {
        var explicitMode = String.valueOf(request.providerOptions().getOrDefault(
            "thinking_mode", request.rawRequest().path("thinking_mode").asText(""))).trim();
        var enable = request.rawRequest().path("enable_thinking");
        if (explicitMode.isBlank() && enable.isBoolean()) {
            explicitMode = enable.asBoolean() ? "Thinking" : "Fast";
        } else if (!explicitMode.isBlank() && enable.isBoolean()) {
            var enabledByMode = !"Fast".equalsIgnoreCase(explicitMode);
            if (enabledByMode != enable.asBoolean()) {
                throw OpenAiRequestException.conflict(
                    "thinking_mode", "thinking_mode conflicts with enable_thinking");
            }
        }
        var effort = String.valueOf(request.reasoning().getOrDefault(
            "effort", request.rawRequest().path("reasoning_effort").asText("")))
            .trim().toLowerCase();
        if (explicitMode.isBlank() || effort.isBlank()) return;
        var effortMode = switch (effort) {
            case "none", "minimal" -> "Fast";
            case "auto" -> "Auto";
            default -> "Thinking";
        };
        if (!explicitMode.equalsIgnoreCase(effortMode)) {
            throw OpenAiRequestException.conflict(
                "thinking_mode", "thinking_mode conflicts with reasoning effort " + effort);
        }
    }

    @Override
    public ProviderRetryPolicy retryPolicy() {
        return ProviderRetryPolicy.standardWith(
            4, "provider_upstream_error", "account_unavailable", "quota_exhausted");
    }

    @Override
    public Flux<CanonicalEvent> generate(
        CanonicalRequest request,
        ProviderExecutionContext context,
        LeasedProviderAccount account
    ) {
        var decoder = new QwenEventDecoder(request.requestId());
        var status = new java.util.concurrent.atomic.AtomicInteger(-1);
        var upstream = context.transportMode() == ProviderTransportMode.API
            ? transport.stream(
                manifest().id(), "chat", semanticCommands.chat(request), account.credential(),
                proxyPool(), proxyAffinityKey(account), runtimeOptions(), context.transportMode())
            : transport.stream(
                manifest().id(), "chat", semanticCommands.chat(request), account.credential(),
                proxyPool(), proxyAffinityKey(account), runtimeOptions());
        return upstream
            .handle((frame, sink) -> {
                var type = frame.path("type").asText("");
                if ("status".equals(type)) {
                    status.set(frame.path("status").asInt(502));
                } else if ("error".equals(type)) {
                    var code = status.get() < 0 ? 502 : status.get();
                    sink.error(new QwenUpstreamException(
                        code, summarize(code, frame.path("data").asText(""))));
                } else if ("data".equals(type)
                    && status.get() >= 200
                    && status.get() < 300) {
                    sink.next(frame.path("data").asText(""));
                } else if ("credential_patch".equals(type)) {
                    context.acceptCredentialPatch(frame.path("data"));
                }
            })
            .cast(String.class)
            .takeUntil(data -> "[DONE]".equals(data.trim()))
            .concatMapIterable(decoder::decode)
            .concatWith(Flux.defer(() -> status.get() < 200 || status.get() >= 300
                ? Flux.error(new QwenUpstreamException(
                    status.get() < 0 ? 502 : status.get(),
                    "Qwen upstream returned HTTP " + status.get()))
                : Flux.fromIterable(decoder.finish())));
    }

    @Override
    public Mono<List<DiscoveredModel>> discoverModels(LeasedProviderAccount account) {
        return discoverModels(account, ProviderTransportMode.RUNTIME);
    }

    @Override
    public Mono<List<DiscoveredModel>> discoverModels(
        LeasedProviderAccount account,
        ProviderTransportMode transportMode
    ) {
        var upstream = transportMode == ProviderTransportMode.API
            ? transport.request(
                manifest().id(), "models", semanticCommands.models(), account.credential(),
                proxyPool(), proxyAffinityKey(account), runtimeOptions(), transportMode)
            : transport.request(
                manifest().id(), "models", semanticCommands.models(), account.credential(),
                proxyPool(), proxyAffinityKey(account), runtimeOptions());
        return upstream
            .flatMap(response -> response.status() >= 200 && response.status() < 300
                ? Mono.just(parseModels(json(response.body())))
                : Mono.error(new QwenUpstreamException(
                    response.status(), summarize(response.status(), response.body()))));
    }

    static List<DiscoveredModel> parseModels(JsonNode root) {
        var candidates = List.of(root, root.path("models"), root.path("data"),
            root.path("data").path("models"), root.path("data").path("data"));
        JsonNode items = tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        for (var candidate : candidates) {
            if (candidate.isArray()) {
                items = candidate;
                break;
            }
        }
        var models = new java.util.LinkedHashMap<String, DiscoveredModel>();
        for (var item : items) {
            if (item.isTextual()) {
                var id = item.asText().trim();
                if (!id.isBlank()) models.putIfAbsent(id, new DiscoveredModel(id, id, Map.of()));
                continue;
            }
            var info = item.path("info");
            if (item.path("is_active").isBoolean() && !item.path("is_active").asBoolean()) continue;
            if (info.path("is_active").isBoolean() && !info.path("is_active").asBoolean()) continue;
            var id = firstText(item, "id", "model", "name");
            if (id.isBlank()) continue;
            var displayName = item.path("name").asText(id);
            var metadata = new java.util.LinkedHashMap<String, Object>();
            if (info.path("created_at").isNumber()) metadata.put("created", info.path("created_at").asLong());
            if (item.path("owned_by").isTextual()) metadata.put("owned_by", item.path("owned_by").asText());
            if (info.path("meta").isObject()) metadata.put("qwen", info.path("meta").deepCopy());
            models.putIfAbsent(id, new DiscoveredModel(id, displayName, metadata));
        }
        return List.copyOf(models.values());
    }

    private static String firstText(JsonNode node, String... fields) {
        for (var field : fields) {
            var value = node.path(field).asText("").trim();
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private JsonNode json(String response) {
        try {
            return mapper.readTree(response);
        } catch (RuntimeException error) {
            throw new QwenUpstreamException(502, "Qwen upstream returned invalid JSON");
        }
    }

    @Override
    public ProviderFailure classify(Throwable error) {
        if (error instanceof QwenUpstreamException upstream) {
            var antiBot = upstream.status() == 403 && (
                upstream.getMessage().contains("anti-bot challenge")
                    || upstream.getMessage().contains("FAIL_SYS_USER_VALIDATE"));
            var retryable = antiBot || upstream.status() >= 500
                || List.of(408, 409, 425, 429).contains(upstream.status());
            var type = antiBot ? "anti_bot_rejected" : switch (upstream.status()) {
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
        return compact.isBlank() ? "Qwen upstream returned HTTP " + status
            : "Qwen upstream returned HTTP " + status + ": " + compact;
    }

    private Map<String, Object> proxyPool() {
        return proxyPools.runtimeForProvider(manifest().id(), ProxyTrafficScope.INFERENCE)
            .orElse(Map.of());
    }

    private String proxyAffinityKey(LeasedProviderAccount account) {
        var persisted = account.credential().path("proxy_affinity_key").asText("").trim();
        return persisted.isBlank() ? account.accountId().toString() : persisted;
    }

    private Map<String, Object> runtimeOptions() {
        return Map.of(
            "base_url", properties.getBaseUrl(),
            "source", properties.getSource(),
            "request_version", properties.getRequestVersion());
    }
}
