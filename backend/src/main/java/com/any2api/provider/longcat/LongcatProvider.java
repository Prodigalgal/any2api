package com.any2api.provider.longcat;

import com.any2api.account.LeasedProviderAccount;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.provider.InferenceProvider;
import com.any2api.provider.ProviderCapability;
import com.any2api.provider.ProviderExecutionContext;
import com.any2api.provider.ProviderFailure;
import com.any2api.provider.ProviderManifest;
import com.any2api.provider.ProviderFailureSignals;
import com.any2api.provider.ProviderProtocolContract;
import com.any2api.provider.WebParameterAdaptation;
import com.any2api.provider.ProviderRequestValidation;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public final class LongcatProvider implements InferenceProvider {
    private static final ProviderProtocolContract PROTOCOL = new ProviderProtocolContract(
        Map.of(
            "agent_id", ProviderProtocolContract.OptionType.STRING,
            "reason_enabled", ProviderProtocolContract.OptionType.BOOLEAN,
            "search_enabled", ProviderProtocolContract.OptionType.BOOLEAN),
        Set.of(
            "reasoning", "reasoning_effort", "agent_id", "reason_enabled", "search_enabled",
            "tools", "tool_choice", "parallel_tool_calls"),
        Set.of(
            "reasoning", "reasoning_effort", "agent_id", "reason_enabled", "search_enabled",
            "tools", "tool_choice", "parallel_tool_calls"),
        Set.of("function")).withParameterMappings(Map.of(
            "reasoning", WebParameterAdaptation.toggle("reason_enabled"),
            "search", WebParameterAdaptation.mapped("search_enabled")));

    private static final ProviderManifest MANIFEST = new ProviderManifest(
        "longcat", "LongCat", "native-longcat-web-v3", "3",
        List.of("longcat-flash", "longcat-thinking", "longcat-search",
            "longcat-reason-search", "longcat-pro"),
        Map.of(
            ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE,
            ProviderCapability.RESPONSES, SupportLevel.NATIVE,
            ProviderCapability.STREAMING, SupportLevel.NATIVE,
            ProviderCapability.FUNCTION_TOOLS, SupportLevel.EMULATED,
            ProviderCapability.REASONING, SupportLevel.NATIVE,
            ProviderCapability.IMAGE_INPUT, SupportLevel.NATIVE,
            ProviderCapability.FILE_INPUT, SupportLevel.NATIVE,
            ProviderCapability.ACCOUNT_KEEPALIVE, SupportLevel.NATIVE,
            ProviderCapability.REGISTRATION, SupportLevel.NATIVE,
            ProviderCapability.REAUTHENTICATION, SupportLevel.NATIVE),
        Map.of(
            RandomModelRole.TOP_TEXT, List.of("longcat-flash", "longcat-search", "longcat-pro"),
            RandomModelRole.TOP_MULTIMODAL, List.of("longcat-pro")), true);

    private final OfficialBrowserTransportClient transport;
    private final OfficialBrowserSemanticCommandFactory semanticCommands;
    private final ProxyPoolService proxyPools;
    private final LongcatProperties properties;
    private final LongcatToolProtocol toolProtocol;
    private final ObjectMapper mapper;

    public LongcatProvider(
        OfficialBrowserTransportClient transport,
        OfficialBrowserSemanticCommandFactory semanticCommands,
        ProxyPoolService proxyPools,
        LongcatProperties properties,
        LongcatToolProtocol toolProtocol,
        ObjectMapper mapper
    ) {
        this.transport = transport;
        this.semanticCommands = semanticCommands;
        this.proxyPools = proxyPools;
        this.properties = properties;
        this.toolProtocol = toolProtocol;
        this.mapper = mapper;
    }

    @Override public ProviderManifest manifest() { return MANIFEST; }

    @Override
    public java.util.Optional<String> scheduledProbeModel() {
        return java.util.Optional.of("longcat-flash");
    }

    @Override
    public Set<ProviderTransportMode> supportedTransportModes() {
        return Set.of(ProviderTransportMode.API, ProviderTransportMode.RUNTIME);
    }

    @Override public ProviderProtocolContract protocolContract() { return PROTOCOL; }

    @Override public Duration modelProbeTimeout() { return properties.getModelProbeTimeout(); }

    @Override public Duration accountProbeTimeout() { return properties.getModelProbeTimeout(); }

    @Override
    public void validateCredential(JsonNode credential) {
        var cookie = credential.path("cookie").asText("").trim();
        var passport = credential.path("passport_token_key")
            .asText(credential.path("passport_token").asText("")).trim();
        if (cookie.isBlank() && passport.isBlank()) {
            throw new IllegalArgumentException(
                "LongCat credential requires cookie or passport_token_key");
        }
    }

    @Override
    public void validate(CanonicalRequest request) {
        ProviderRequestValidation.requireStringParameters(request, "agent_id");
        ProviderRequestValidation.requireBooleanParameters(
            request, "reason_enabled", "search_enabled");
        ProviderRequestValidation.requireInlineMediaUploads(
            request, "LongCat", Set.of(ProviderCapability.IMAGE_INPUT, ProviderCapability.FILE_INPUT));
        validateMediaPlacement(request);
        LongcatMediaValidation.validate(request);
        ProviderRequestValidation.requireReasoningBooleanConsistency(
            request, "reason_enabled", Set.of("none", "minimal"), "reason_enabled");
        toolProtocol.plan(request);
    }

    @Override
    public Flux<CanonicalEvent> generate(
        CanonicalRequest request,
        ProviderExecutionContext context,
        LeasedProviderAccount account
    ) {
        validateCredential(account.credential());
        var toolPlan = toolProtocol.plan(request);
        var reasoningEnabled = reasoningEnabled(request);
        return Flux.defer(() -> {
            var decoder = new LongcatEventDecoder(
                request.requestId(), reasoningEnabled, toolPlan, toolProtocol);
            var status = new AtomicInteger(-1);
            var upstream = context.transportMode() == ProviderTransportMode.API
                ? transport.stream(
                    MANIFEST.id(), "chat", semanticCommands.chat(request), account.credential(),
                    proxyPool(), proxyAffinityKey(account), runtimeOptions(),
                    context.transportMode())
                : transport.stream(
                    MANIFEST.id(), "chat", semanticCommands.chat(request), account.credential(),
                    proxyPool(), proxyAffinityKey(account), runtimeOptions());
            return upstream
                .handle((frame, sink) -> {
                    var type = frame.path("type").asText("");
                    if ("status".equals(type)) {
                        status.set(frame.path("status").asInt(502));
                    } else if ("error".equals(type)) {
                        var rawText = frame.path("data").asText("");
                        var code = status.get();
                        if (code < 0) {
                            code = ProviderFailureSignals.isCredentialRejected(502, rawText) ? 401 : 502;
                        }
                        sink.error(new LongcatUpstreamException(
                            code, summarize(code, rawText)));
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
                    ? Flux.error(new LongcatUpstreamException(
                        status.get() < 0 ? 502 : status.get(),
                        "LongCat upstream returned HTTP " + status.get()))
                    : Flux.fromIterable(decoder.finish())));
        });
    }

    @Override
    public ProviderFailure classify(Throwable error) {
        if (error instanceof LongcatUpstreamException upstream) {
            var antiBot = ProviderFailureSignals.isAntiBot(
                upstream.status(), upstream.getMessage());
            if (antiBot) {
                return new ProviderFailure(
                    "anti_bot_rejected", upstream.getMessage(), true,
                    Map.of("status", upstream.status(), "challenge", "provider_verification"));
            }
            var credentialRejected = ProviderFailureSignals.isCredentialRejected(
                upstream.status(), upstream.getMessage());
            var retryable = !credentialRejected && (upstream.status() >= 500
                || List.of(408, 409, 425, 429).contains(upstream.status()));
            var type = credentialRejected ? "credential_rejected" : switch (upstream.status()) {
                case 401, 403 -> "credential_rejected";
                case 429 -> "rate_limited";
                case 400, 422 -> "invalid_request";
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

    private void validateMediaPlacement(CanonicalRequest request) {
        var messages = request.messages();
        var lastUser = -1;
        for (var index = 0; index < messages.size(); index++) {
            if ("user".equals(messages.get(index).path("role").asText("user"))) lastUser = index;
        }
        var callIds = new java.util.HashSet<String>();
        var imageCount = 0;
        var fileCount = 0;
        for (var index = 0; index < messages.size(); index++) {
            var message = messages.get(index);
            var role = message.path("role").asText("user");
            if ("assistant".equals(role)) {
                for (var call : message.path("tool_calls")) {
                    var id = call.path("id").asText("");
                    if (!id.isBlank()) callIds.add(id);
                }
            }
            if (!message.path("content").isArray()) continue;
            for (var part : message.path("content")) {
                var type = part.path("type").asText("");
                var image = Set.of("image", "input_image", "image_url").contains(type);
                var file = Set.of("input_file", "file", "attachment").contains(type);
                if (!image && !file) continue;
                var toolResult = index > lastUser && "tool".equals(role)
                    && callIds.contains(message.path("tool_call_id").asText(""));
                if (lastUser < 0 || (index != lastUser && !toolResult)) {
                    throw new IllegalArgumentException(
                        "LongCat media requires the latest user message or a matching trailing tool result");
                }
                if (image) imageCount++; else fileCount++;
            }
        }
        if (imageCount > 9 || fileCount > 1 || (imageCount > 0 && fileCount > 0)) {
            throw new IllegalArgumentException("LongCat accepts up to 9 images or one document per request");
        }
    }

    private boolean reasoningEnabled(CanonicalRequest request) {
        var option = request.providerOptions().get("reason_enabled");
        if (option instanceof Boolean value) return value;
        var raw = request.rawRequest().path("reason_enabled");
        if (raw.isBoolean()) return raw.asBoolean();
        var effort = String.valueOf(request.reasoning().getOrDefault(
            "effort", request.rawRequest().path("reasoning_effort").asText("")));
        if (!effort.isBlank()) return !Set.of("none", "minimal").contains(effort.toLowerCase());
        return Set.of("longcat-thinking", "longcat-reason", "longcat-reason-search",
            "longcat-pro").contains(request.model());
    }

    private Map<String, Object> proxyPool() {
        return proxyPools.runtimeForProvider(MANIFEST.id(), ProxyTrafficScope.INFERENCE)
            .orElse(Map.of());
    }

    private Map<String, Object> runtimeOptions() {
        return Map.of(
            "base_url", properties.getBaseUrl(),
            "app_key", properties.getAppKey(),
            "language", properties.getLanguage(),
            "requested_with", properties.getRequestedWith());
    }

    private static String proxyAffinityKey(LeasedProviderAccount account) {
        var persisted = account.credential().path("proxy_affinity_key").asText("").trim();
        return persisted.isBlank() ? account.accountId().toString() : persisted;
    }

    private String summarize(int status, String body) {
        var compact = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        if (compact.length() > 1000) compact = compact.substring(0, 1000);
        return compact.isBlank()
            ? "LongCat upstream returned HTTP " + status
            : "LongCat upstream returned HTTP " + status + ": " + compact;
    }
}
