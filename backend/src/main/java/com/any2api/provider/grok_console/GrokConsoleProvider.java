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
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
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
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
        new ParameterizedTypeReference<>() {};
    private static final ProviderManifest MANIFEST = new ProviderManifest(
        "grok_console", "Grok Console", "xai-console-sso-v1", "2",
        GrokConsoleModelCatalog.modelIds(), Map.of(
            ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE,
            ProviderCapability.RESPONSES, SupportLevel.NATIVE,
            ProviderCapability.STREAMING, SupportLevel.NATIVE,
            ProviderCapability.REASONING, SupportLevel.NATIVE,
            ProviderCapability.FUNCTION_TOOLS, SupportLevel.NATIVE,
            ProviderCapability.ACCOUNT_KEEPALIVE, SupportLevel.NATIVE),
        Map.of(RandomModelRole.TOP_TEXT, List.of(
            "grok-4.20-multi-agent-0309", "grok-4.20-0309-reasoning", "grok-4.3")), true);

    private final WebClient client;
    private final GrokConsoleProperties properties;
    private final GrokConsoleRequestMapper requestMapper;
    private final ObjectMapper mapper;

    public GrokConsoleProvider(
        WebClient.Builder builder,
        GrokConsoleProperties properties,
        GrokConsoleRequestMapper requestMapper,
        ObjectMapper mapper
    ) {
        this.client = builder.baseUrl(trim(properties.getBaseUrl().toString())).build();
        this.properties = properties;
        this.requestMapper = requestMapper;
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
        var decoder = new OpenAiSseEventDecoder(mapper, request.requestId());
        return client.post().uri("/v1/responses")
            .header(HttpHeaders.AUTHORIZATION, "Bearer anonymous")
            .header(HttpHeaders.COOKIE, cookies(account))
            .header(HttpHeaders.ORIGIN, trim(properties.getBaseUrl().toString()))
            .header(HttpHeaders.REFERER, trim(properties.getBaseUrl().toString()) + "/")
            .header(HttpHeaders.USER_AGENT, userAgent(account))
            .header("x-cluster", properties.getCluster())
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "same-origin")
            .header("Priority", "u=1, i")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(requestMapper.prepare(request))
            .exchangeToFlux(response -> response.statusCode().is2xxSuccessful()
                ? response.bodyToFlux(SSE_TYPE).mapNotNull(ServerSentEvent::data)
                    .concatMapIterable(decoder::decode)
                    .concatWith(Flux.defer(() -> Flux.fromIterable(decoder.finish())))
                : response.bodyToMono(String.class).defaultIfEmpty("")
                    .flatMapMany(body -> Flux.error(new GrokConsoleUpstreamException(
                        response.statusCode().value(), summarize(response.statusCode().value(), body)))));
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

    private String cookies(LeasedProviderAccount account) {
        var token = first(account, "sso", "sso-rw", "sso_rw", "sso_token");
        if (token.isBlank()) throw new IllegalStateException("Grok Console credential has no SSO token");
        var result = "sso=" + token + "; sso-rw=" + token;
        var clearance = first(account, "cloudflare_cookies", "cf_cookies");
        return clearance.isBlank() ? result : result + "; " + clearance;
    }

    private String userAgent(LeasedProviderAccount account) {
        var value = first(account, "user_agent");
        return value.isBlank() ? properties.getUserAgent() : value;
    }

    private String first(LeasedProviderAccount account, String... fields) {
        return first(account.credential(), fields);
    }

    private String first(tools.jackson.databind.JsonNode credential, String... fields) {
        for (var field : fields) {
            var value = credential.path(field).asText("").trim();
            if (!value.isBlank()) return value.startsWith("sso=") ? value.substring(4).trim() : value;
        }
        return "";
    }

    private static String trim(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String summarize(int status, String body) {
        var compact = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        if (compact.length() > 1000) compact = compact.substring(0, 1000);
        return compact.isBlank() ? "Grok Console returned HTTP " + status
            : "Grok Console returned HTTP " + status + ": " + compact;
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
