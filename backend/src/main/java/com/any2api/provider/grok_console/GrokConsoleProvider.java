package com.any2api.provider.grok_console;

import com.any2api.account.LeasedProviderAccount;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.protocol.OpenAiSseEventDecoder;
import com.any2api.provider.InferenceProvider;
import com.any2api.provider.ProviderCapability;
import com.any2api.provider.ProviderExecutionContext;
import com.any2api.provider.ProviderFailure;
import com.any2api.provider.ProviderManifest;
import com.any2api.provider.ProviderProtocolContract;
import com.any2api.provider.RandomModelRole;
import com.any2api.provider.SupportLevel;
import com.any2api.proxy.ProxyPoolService;
import com.any2api.proxy.ProxyTrafficScope;
import com.any2api.transport.OfficialBrowserSemanticCommandFactory;
import com.any2api.transport.OfficialBrowserTransportClient;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

@Component
public final class GrokConsoleProvider implements InferenceProvider {
    private static final ProviderProtocolContract PROTOCOL = new ProviderProtocolContract(
        Map.of(),
        java.util.Set.of(
            "temperature", "top_p", "max_tokens", "max_completion_tokens",
            "max_output_tokens", "reasoning", "reasoning_effort", "tools", "tool_choice",
            "parallel_tool_calls"),
        java.util.Set.of(
            "include", "max_output_tokens", "max_tool_calls", "parallel_tool_calls",
            "reasoning", "temperature", "tools", "tool_choice", "top_logprobs", "top_p",
            "truncation"),
        java.util.Set.of("function"),
        java.util.Set.of("effort", "summary"));
    private static final ProviderManifest MANIFEST = new ProviderManifest(
        "grok_console", "Grok Console", "xai-console-sso-v1", "2",
        GrokConsoleModelCatalog.modelIds(), Map.of(
            ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE,
            ProviderCapability.RESPONSES, SupportLevel.NATIVE,
            ProviderCapability.STREAMING, SupportLevel.NATIVE,
            ProviderCapability.REASONING, SupportLevel.NATIVE,
            ProviderCapability.FUNCTION_TOOLS, SupportLevel.NATIVE,
            ProviderCapability.IMAGE_INPUT, SupportLevel.NATIVE,
            ProviderCapability.FILE_INPUT, SupportLevel.NATIVE,
            ProviderCapability.ACCOUNT_KEEPALIVE, SupportLevel.NATIVE),
        Map.of(RandomModelRole.TOP_TEXT, List.of(
            "grok-4.20-multi-agent-0309", "grok-4.20-0309-reasoning", "grok-4.3")), true);

    private final OfficialBrowserTransportClient transport;
    private final OfficialBrowserSemanticCommandFactory semanticCommands;
    private final ProxyPoolService proxyPools;
    private final GrokConsoleProperties properties;
    private final ObjectMapper mapper;

    public GrokConsoleProvider(
        OfficialBrowserTransportClient transport,
        OfficialBrowserSemanticCommandFactory semanticCommands,
        ProxyPoolService proxyPools,
        GrokConsoleProperties properties,
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
    public void validateCredential(tools.jackson.databind.JsonNode credential) {
        if (first(credential, "sso", "sso-rw", "sso_rw", "sso_token").isBlank()) {
            throw new IllegalArgumentException("Grok Console credential requires an SSO token");
        }
    }

    @Override
    public void validate(CanonicalRequest request) {
        GrokConsoleModelCatalog.require(request.model());
    }

    @Override
    public Flux<CanonicalEvent> generate(
        CanonicalRequest request,
        ProviderExecutionContext context,
        LeasedProviderAccount account
    ) {
        return Flux.defer(() -> {
            var decoder = new OpenAiSseEventDecoder(mapper, request.requestId());
            var status = new java.util.concurrent.atomic.AtomicInteger(-1);
            return transport.stream(
                    MANIFEST.id(),
                    "chat",
                    semanticCommands.chat(request),
                    account.credential(),
                    proxyPool(),
                    proxyAffinityKey(account),
                    Map.of(
                        "base_url", trimTrailingSlash(properties.getBaseUrl().toString()),
                        "cluster", properties.getCluster()))
                .handle((frame, sink) -> {
                    var type = frame.path("type").asText("");
                    if ("status".equals(type)) {
                        status.set(frame.path("status").asInt(502));
                    } else if ("error".equals(type)) {
                        var code = status.get() < 0 ? 502 : status.get();
                        sink.error(new GrokConsoleUpstreamException(
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
                    ? Flux.error(new GrokConsoleUpstreamException(
                        status.get(), "Grok Console upstream returned HTTP " + status.get()))
                    : Flux.fromIterable(decoder.finish())));
        });
    }

    @Override
    public ProviderFailure classify(Throwable error) {
        if (error instanceof GrokConsoleUpstreamException upstream) {
            var retryable = upstream.status() == 403 || upstream.status() == 429
                || upstream.status() >= 500;
            var type = switch (upstream.status()) {
                case 401 -> "credential_rejected";
                case 403 -> "permission_or_egress_denied";
                case 429 -> "rate_limited";
                default -> "provider_upstream_error";
            };
            return new ProviderFailure(type, upstream.getMessage(), retryable,
                Map.of("status", upstream.status(), "channel", "console"));
        }
        return new ProviderFailure("provider_transport_error",
            error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(),
            true, Map.of("channel", "console"));
    }

    private Map<String, Object> proxyPool() {
        return proxyPools.runtimeForProvider(MANIFEST.id(), ProxyTrafficScope.INFERENCE)
            .orElse(Map.of());
    }

    private static String proxyAffinityKey(LeasedProviderAccount account) {
        var persisted = account.credential().path("proxy_affinity_key").asText("").trim();
        return persisted.isBlank() ? account.accountId().toString() : persisted;
    }

    private static String first(tools.jackson.databind.JsonNode credential, String... fields) {
        for (var field : fields) {
            var value = credential.path(field).asText("").trim();
            if (!value.isBlank()) {
                return value.replaceFirst("(?i)^sso(?:-rw)?\\s*=\\s*", "");
            }
        }
        return "";
    }

    private static String summarize(int status, String body) {
        var compact = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        if (compact.length() > 1000) compact = compact.substring(0, 1000);
        return compact.isBlank() ? "Grok Console returned HTTP " + status
            : "Grok Console returned HTTP " + status + ": " + compact;
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static final class GrokConsoleUpstreamException extends RuntimeException {
        private final int status;
        private GrokConsoleUpstreamException(int status, String message) {
            super(message);
            this.status = status;
        }
        private int status() { return status; }
    }
}
