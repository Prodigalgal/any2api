package com.any2api.provider.longcat;

import com.any2api.account.LeasedProviderAccount;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
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
        Set.of("function"));

    private static final ProviderManifest MANIFEST = new ProviderManifest(
        "longcat", "LongCat", "native-longcat-web-v2", "3",
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
                        sink.error(new LongcatUpstreamException(
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
                    ? Flux.error(new LongcatUpstreamException(
                        status.get(), "LongCat upstream returned HTTP " + status.get()))
                    : Flux.fromIterable(decoder.finish())));
        });
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
        return new ProviderFailure(
            "provider_transport_error",
            error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(),
            true,
            Map.of());
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
