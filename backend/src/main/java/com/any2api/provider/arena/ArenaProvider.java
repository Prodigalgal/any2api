package com.any2api.provider.arena;

import com.any2api.account.LeasedProviderAccount;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.provider.DiscoveredModel;
import com.any2api.provider.InferenceProvider;
import com.any2api.provider.ModelCapabilityContract;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Arena Web adapter. It deliberately exposes no official/API channel. */
@Component
public final class ArenaProvider implements InferenceProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(ArenaProvider.class);
    private static final ProviderProtocolContract PROTOCOL = new ProviderProtocolContract(
        Map.of(
            "mode", ProviderProtocolContract.OptionType.STRING,
            "model_id", ProviderProtocolContract.OptionType.STRING,
            "web_search", ProviderProtocolContract.OptionType.BOOLEAN),
        Set.of("web_search"),
        Set.of("web_search"),
        Set.of());

    private static final ProviderManifest MANIFEST = new ProviderManifest(
        "arena",
        "Arena",
        "official-browser-arena-v1",
        "3",
        List.of("Max"),
        Map.ofEntries(
            Map.entry(ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE),
            Map.entry(ProviderCapability.RESPONSES, SupportLevel.NATIVE),
            Map.entry(ProviderCapability.STREAMING, SupportLevel.NATIVE),
            Map.entry(ProviderCapability.IMAGE_INPUT, SupportLevel.NATIVE),
            Map.entry(ProviderCapability.FILE_INPUT, SupportLevel.NATIVE),
            Map.entry(ProviderCapability.MODEL_DISCOVERY, SupportLevel.NATIVE),
            Map.entry(ProviderCapability.ACCOUNT_KEEPALIVE, SupportLevel.NATIVE),
            Map.entry(ProviderCapability.REGISTRATION, SupportLevel.NATIVE),
            Map.entry(ProviderCapability.REAUTHENTICATION, SupportLevel.NATIVE)),
        Map.of(
            RandomModelRole.TOP_TEXT, List.of("Max"),
            RandomModelRole.TOP_MULTIMODAL, List.of("Max")),
        true);

    private final ArenaProperties properties;
    private final ProxyPoolService proxyPools;
    private final ObjectMapper mapper;
    private final OfficialBrowserTransportClient transport;
    private final OfficialBrowserSemanticCommandFactory semanticCommands;
    private final ArenaRequestMapper requestMapper = new ArenaRequestMapper();

    public ArenaProvider(
        ArenaProperties properties,
        ProxyPoolService proxyPools,
        ObjectMapper mapper,
        OfficialBrowserTransportClient transport,
        OfficialBrowserSemanticCommandFactory semanticCommands
    ) {
        this.properties = properties;
        this.proxyPools = proxyPools;
        this.mapper = mapper;
        this.transport = transport;
        this.semanticCommands = semanticCommands;
    }

    @Override public ProviderManifest manifest() { return MANIFEST; }

    @Override public ProviderProtocolContract protocolContract() { return PROTOCOL; }

    @Override
    public Duration modelProbeTimeout() { return properties.getModelProbeTimeout(); }

    @Override
    public Duration accountProbeTimeout() { return properties.getModelProbeTimeout(); }

    @Override
    public void validateCredential(JsonNode credential) {
        var identity = first(credential, "arena_user_id", "user_id", "userId", "external_id");
        var email = credential.path("email").asText("").trim();
        if (identity.isBlank() && email.isBlank()) {
            throw new IllegalArgumentException("Arena credential requires an account identity");
        }
        if (!credential.path("browser_execution_context").isObject()) {
            throw new IllegalArgumentException(
                "Arena credential requires a browser_execution_context");
        }
    }

    @Override
    public void validate(CanonicalRequest request) {
        requestMapper.validate(request);
        ProviderRequestValidation.requireBooleanParameters(request, "web_search");
        ProviderRequestValidation.requireInlineMediaUploads(
            request, "Arena", Set.of(ProviderCapability.IMAGE_INPUT, ProviderCapability.FILE_INPUT));
    }

    @Override
    public ModelCapabilityContract modelContract(DiscoveredModel model) {
        var contract = ModelCapabilityContract.from(manifest(), protocolContract(), model);
        var metadata = mapper.valueToTree(model.metadata());
        var arenaCapabilities = metadata.path("arena_capabilities");
        if (!arenaCapabilities.isObject()) arenaCapabilities = metadata.path("capabilities");
        var input = arenaCapabilities.path("inputCapabilities");
        if (!input.isObject()) return contract;
        if (!capabilityEnabled(input, "image")) contract = contract.withoutInputMedia("image");
        if (!capabilityEnabled(input, "file")) contract = contract.withoutInputMedia("file");
        return contract;
    }

    @Override
    public Flux<CanonicalEvent> generate(
        CanonicalRequest request,
        ProviderExecutionContext context,
        LeasedProviderAccount account
    ) {
        validateCredential(account.credential());
        requestMapper.validate(request);
        return Flux.defer(() -> {
            var decoder = new ArenaEventDecoder(request.requestId());
            var status = new AtomicInteger(-1);
            var frameCount = new AtomicInteger();
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
                    } else if ("recaptcha".equals(type)) {
                        LOGGER.info(
                            "arena_recaptcha_v3 available={} token_length={}",
                            frame.path("available").asBoolean(false),
                            frame.path("tokenLength").asInt(0));
                    } else if ("error".equals(type)) {
                        var code = status.get() < 0 ? 502 : status.get();
                        sink.error(new ArenaUpstreamException(
                            code, summarize(code, frame.path("data").asText(""))));
                    } else if ("data".equals(type) && status.get() < 400) {
                        var data = frame.path("data").asText("");
                        if (frameCount.getAndIncrement() < 12) {
                            LOGGER.info(
                                "arena_upstream_frame index={} descriptor={} bytes={}",
                                frameCount.get() - 1,
                                describeFrame(data),
                                data.getBytes(StandardCharsets.UTF_8).length);
                        }
                        sink.next(data.getBytes(StandardCharsets.UTF_8));
                    } else if ("credential_patch".equals(type)) {
                        context.acceptCredentialPatch(frame.path("data"));
                    }
                })
                .cast(byte[].class)
                .concatMapIterable(bytes -> decoder.decode(
                    new String(bytes, StandardCharsets.UTF_8)))
                .concatWith(Flux.defer(() -> status.get() >= 400
                    ? Flux.error(new ArenaUpstreamException(
                        status.get(), "Arena upstream returned HTTP " + status.get()))
                    : Flux.fromIterable(decoder.finish())))
                .takeUntil(event -> event instanceof CanonicalEvent.Completed
                    || event instanceof CanonicalEvent.Failed);
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
                    return Mono.error(new ArenaUpstreamException(
                        response.status(), summarize(response.status(), response.body())));
                }
                try {
                    return Mono.just(parseModels(mapper.readTree(response.body())));
                } catch (RuntimeException error) {
                    return Mono.error(new ArenaUpstreamException(
                        502, "Arena model discovery returned invalid JSON"));
                }
            });
    }

    static List<DiscoveredModel> parseModels(JsonNode root) {
        var output = new LinkedHashMap<String, DiscoveredModel>();
        for (var item : root.path("models")) {
            var id = item.path("id").asText("").trim();
            if (id.isBlank()) continue;
            var display = item.path("display_name").asText(item.path("displayName").asText(id));
            var metadata = item.path("metadata").isObject()
                ? new LinkedHashMap<String, Object>(
                    new ObjectMapper().convertValue(
                        item.path("metadata"), new TypeReference<Map<String, Object>>() {}))
                : new LinkedHashMap<String, Object>();
            output.putIfAbsent(id, new DiscoveredModel(id, display, metadata));
        }
        return List.copyOf(output.values());
    }

    @Override
    public ProviderFailure classify(Throwable error) {
        if (error instanceof ArenaUpstreamException upstream) {
            var status = upstream.status();
            if (status == 429 && upstream.getMessage() != null
                && upstream.getMessage().toLowerCase(Locale.ROOT).contains("prompt failed")) {
                return new ProviderFailure(
                    "anti_bot_rejected", upstream.getMessage(), false,
                    Map.of("status", status, "challenge", "recaptcha_v2"));
            }
            var retryable = status >= 500 || Set.of(408, 409, 425, 429).contains(status);
            var type = switch (status) {
                case 401 -> "credential_rejected";
                case 403 -> "permission_denied";
                case 404 -> "model_unavailable";
                case 409, 429 -> "rate_limited";
                case 400, 422 -> "invalid_request_error";
                default -> "provider_upstream_error";
            };
            return new ProviderFailure(type, upstream.getMessage(), retryable,
                Map.of("status", status));
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
        return Map.of("base_url", properties.getBaseUrl());
    }

    private static String proxyAffinityKey(LeasedProviderAccount account) {
        var persisted = account.credential().path("proxy_affinity_key").asText("").trim();
        return persisted.isBlank() ? account.accountId().toString() : persisted;
    }

    private static boolean capabilityEnabled(JsonNode input, String name) {
        var value = input.path(name);
        if (value.isMissingNode() || value.isNull()) return false;
        if (value.isBoolean()) return value.asBoolean();
        if (value.isObject() && value.has("enabled") && value.path("enabled").isBoolean()) {
            return value.path("enabled").asBoolean();
        }
        return value.isObject();
    }

    private String summarize(int status, String body) {
        var compact = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        if (compact.length() > 1000) compact = compact.substring(0, 1000);
        return compact.isBlank()
            ? "Arena upstream returned HTTP " + status
            : "Arena upstream returned HTTP " + status + ": " + compact;
    }

    private String describeFrame(String data) {
        var frame = data == null ? "" : data.trim();
        var separator = frame.indexOf(':');
        if (separator < 1 || separator == frame.length() - 1) return "invalid_frame";
        var code = frame.substring(0, separator);
        var payload = frame.substring(separator + 1).trim();
        try {
            var value = mapper.readTree(payload);
            if (!value.isObject()) {
                return code + ":" + value.getNodeType().name().toLowerCase(Locale.ROOT);
            }
            var fields = new ArrayList<String>();
            for (var entry : value.properties()) {
                if (fields.size() >= 12) break;
                var name = entry.getKey();
                if (name.matches("[A-Za-z0-9_]{1,40}")) fields.add(name);
            }
            return code + ":object:" + String.join(",", fields);
        } catch (RuntimeException error) {
            return code + ":invalid_json";
        }
    }

    private static String first(JsonNode source, String... fields) {
        if (source == null) return "";
        for (var field : fields) {
            var value = source.path(field).asText("").trim();
            if (!value.isBlank()) return value;
        }
        return "";
    }
}
