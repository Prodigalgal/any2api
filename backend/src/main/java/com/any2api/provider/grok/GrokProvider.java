package com.any2api.provider.grok;

import com.any2api.account.LeasedProviderAccount;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.protocol.OpenAiSseEventDecoder;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
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
        "2",
        List.of("grok-4.5"),
        Map.of(
            ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE,
            ProviderCapability.RESPONSES, SupportLevel.NATIVE,
            ProviderCapability.STREAMING, SupportLevel.NATIVE,
            ProviderCapability.FUNCTION_TOOLS, SupportLevel.NATIVE,
                ProviderCapability.REASONING, SupportLevel.NATIVE,
                ProviderCapability.MODEL_DISCOVERY, SupportLevel.NATIVE,
            ProviderCapability.ACCOUNT_KEEPALIVE, SupportLevel.NATIVE,
            ProviderCapability.REGISTRATION, SupportLevel.NATIVE,
            ProviderCapability.REAUTHENTICATION, SupportLevel.NATIVE),
        Map.of(RandomModelRole.TOP_TEXT, List.of("grok-4.5")),
        true);

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
        new ParameterizedTypeReference<>() {};

    private final WebClient webClient;
    private final GrokProperties properties;
    private final GrokRequestMapper requestMapper;
    private final ObjectMapper objectMapper;

    public GrokProvider(
        WebClient.Builder webClientBuilder,
        GrokProperties properties,
        GrokRequestMapper requestMapper,
        ObjectMapper objectMapper
    ) {
        this.webClient = webClientBuilder
            .baseUrl(trimTrailingSlash(properties.getBaseUrl().toString()))
            .build();
        this.properties = properties;
        this.requestMapper = requestMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public ProviderManifest manifest() {
        return MANIFEST;
    }

    @Override
    public ProviderProtocolContract protocolContract() {
        return PROTOCOL;
    }

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
        var token = accessToken(account);
        var prepared = requestMapper.prepare(request);
        return Flux.defer(() -> {
            var decoder = new OpenAiSseEventDecoder(objectMapper, request.requestId());
            return webClient.post()
                .uri("/responses")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("x-xai-token-auth", properties.getTokenAuth())
                .header("x-grok-client-version", properties.getClientVersion())
                .header("x-grok-client-identifier", properties.getClientIdentifier())
                .headers(headers -> {
                    if (prepared.conversationId() != null) {
                        headers.set("x-grok-conv-id", prepared.conversationId());
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(prepared.body())
                .exchangeToFlux(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToFlux(SSE_TYPE)
                            .mapNotNull(ServerSentEvent::data)
                            .concatMapIterable(decoder::decode)
                            .concatWith(Flux.defer(() -> Flux.fromIterable(decoder.finish())));
                    }
                    return response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMapMany(body -> Flux.error(new GrokUpstreamException(
                            response.statusCode().value(),
                            summarize(response.statusCode().value(), body))));
                });
        });
    }

    @Override
    public Mono<List<DiscoveredModel>> discoverModels(LeasedProviderAccount account) {
        return webClient.get()
            .uri("/models")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(account))
            .header("x-xai-token-auth", properties.getTokenAuth())
            .header("x-grok-client-version", properties.getClientVersion())
            .header("x-grok-client-identifier", properties.getClientIdentifier())
            .exchangeToMono(response -> response.bodyToMono(tools.jackson.databind.JsonNode.class)
                .defaultIfEmpty(tools.jackson.databind.node.JsonNodeFactory.instance.objectNode())
                .flatMap(body -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        return Mono.error(new GrokUpstreamException(response.statusCode().value(),
                            summarize(response.statusCode().value(), body.toString())));
                    }
                    var models = new java.util.ArrayList<DiscoveredModel>();
                    for (var item : body.path("data")) {
                        var id = item.path("id").asText("").trim();
                        if (!id.isBlank()) {
                            var metadata = new java.util.LinkedHashMap<String, Object>();
                            if (item.has("created")) metadata.put("created", item.path("created").asLong());
                            if (item.has("owned_by")) metadata.put("owned_by", item.path("owned_by").asText());
                            models.add(new DiscoveredModel(id, id, metadata));
                        }
                    }
                    return Mono.just(List.copyOf(models));
                }));
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
            var detail = new java.util.LinkedHashMap<String, Object>();
            detail.put("status", upstream.status());
            if (upstream.status() == 403) {
                detail.put("attribution", "unknown");
                detail.put("candidates", List.of("account", "email_domain", "egress_ip"));
            }
            return new ProviderFailure(
                type,
                upstream.getMessage(),
                retryable,
                Map.copyOf(detail));
        }
        return new ProviderFailure(
            "provider_transport_error",
            error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(),
            true,
            Map.of());
    }

    private String accessToken(LeasedProviderAccount account) {
        for (var field : List.of("access_token", "key", "token")) {
            var value = account.credential().path(field).asText("").trim();
            if (!value.isBlank()) {
                return value;
            }
        }
        throw new IllegalStateException("Grok account credential has no access token");
    }

    private String summarize(int status, String body) {
        var compact = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        if (compact.length() > 1000) {
            compact = compact.substring(0, 1000);
        }
        return compact.isBlank()
            ? "Grok upstream returned HTTP " + status
            : "Grok upstream returned HTTP " + status + ": " + compact;
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
