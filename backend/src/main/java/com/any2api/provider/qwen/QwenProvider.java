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
    private final BrowserTransportClient transport;
    private final ProxyPoolService proxyPools;
    private final QwenProperties properties;
    private final QwenRequestMapper requestMapper;
    private final QwenTransportRequests requests;
    private final QwenMediaUploader mediaUploader;
    private final ObjectMapper mapper;

    public QwenProvider(
        BrowserTransportClient transport,
        ProxyPoolService proxyPools,
        QwenProperties properties,
        QwenRequestMapper requestMapper,
        QwenTransportRequests requests,
        QwenMediaUploader mediaUploader,
        ObjectMapper mapper
    ) {
        this.transport = transport;
        this.proxyPools = proxyPools;
        this.properties = properties;
        this.requestMapper = requestMapper;
        this.requests = requests;
        this.mediaUploader = mediaUploader;
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
    public void validate(CanonicalRequest request) {
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
        return new ProviderRetryPolicy(4, Set.of("empty_model_response"));
    }

    @Override
    public Flux<CanonicalEvent> generate(
        CanonicalRequest request,
        ProviderExecutionContext context,
        LeasedProviderAccount account
    ) {
        var credential = QwenCredential.from(account);
        var affinityKey = account.accountId() + ":" + request.requestId();
        return Flux.usingWhen(
            transport.open(sessionCommand(credential, affinityKey)),
            session -> mediaUploader.prepare(session, request.messages(), credential)
                .flatMapMany(preparedMessages -> createChat(
                        session, request.model())
                    .flatMapMany(chatId -> {
                        var body = requestMapper.prepare(request, chatId, preparedMessages);
                        var path = "/api/v2/chat/completions?chat_id=" + chatId;
                        var bodyText = mapper.writeValueAsString(body);
                        var decoder = new QwenEventDecoder(request.requestId());
                        var sse = new SseDataDecoder();
                        return requests.create("POST", path, bodyText, 300)
                            .flatMapMany(command -> transport.stream(session.id(), command))
                            .concatMapIterable(sse::decode)
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
        var credential = QwenCredential.from(account);
        var path = "/api/v2/models/";
        return Mono.usingWhen(
            transport.open(sessionCommand(credential, account.accountId() + ":catalog")),
            session -> requests.create("GET", path, "", 120)
                .flatMap(command -> transport.request(session.id(), command))
                .flatMap(response -> response.successful()
                    ? Mono.just(parseModels(json(response)))
                    : Mono.error(new QwenUpstreamException(response.status(),
                        summarize(response.status(), response.text())))),
            this::close,
            (session, ignored) -> close(session),
            this::close);
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

    private Mono<String> createChat(BrowserTransportClient.Session session, String model) {
        var body = mapper.createObjectNode()
            .put("chatId", "")
            .put("project_id", "")
            .put("timestamp", System.currentTimeMillis())
            .put("chat_type", "t2t")
            .put("chat_mode", "normal");
        body.putArray("models").add(model);
        var bodyText = mapper.writeValueAsString(body);
        var path = "/api/v2/chats/new";
        return requests.create("POST", path, bodyText, 120)
            .flatMap(command -> transport.request(session.id(), command))
            .flatMap(response -> {
                var json = json(response);
                if (!response.successful()) {
                    return Mono.error(new QwenUpstreamException(response.status(),
                        summarize(response.status(), json.toString())));
                }
                var id = json.path("id").asText(json.path("data").path("id").asText(
                    json.path("chat_id").asText(""))).trim();
                return id.isBlank()
                    ? Mono.error(new QwenUpstreamException(502,
                        "Qwen chats/new returned no chat id"))
                    : Mono.just(id);
            });
    }

    private BrowserTransportClient.OpenCommand sessionCommand(
        QwenCredential credential,
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
            origin, Map.of(), List.of("." + origin.getHost()), userAgent,
            browserProfile, "v2", proxyPool, 300, List.of(), affinityKey,
            !proxyPool.isEmpty(), "", credential.token());
    }

    private JsonNode json(BrowserTransportClient.BufferedResponse response) {
        try {
            return mapper.readTree(response.text());
        } catch (RuntimeException error) {
            throw new QwenUpstreamException(502, "Qwen upstream returned invalid JSON");
        }
    }

    private Mono<Void> close(BrowserTransportClient.Session session) {
        return transport.close(session.id()).then();
    }

    @Override
    public ProviderFailure classify(Throwable error) {
        if (error instanceof QwenUpstreamException upstream) {
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
        return compact.isBlank() ? "Qwen upstream returned HTTP " + status
            : "Qwen upstream returned HTTP " + status + ": " + compact;
    }
}
