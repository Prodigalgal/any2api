package com.any2api.transport;

import com.any2api.config.Any2ApiProperties;
import com.any2api.observability.RequestCorrelation;
import com.any2api.provider.ProviderAction;
import com.any2api.provider.ProviderTransportMode;
import com.any2api.runtime.ProviderRuntimeRuleService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Gateway-side client for the stable provider Action/Channel contract. */
@Component
public final class OfficialBrowserTransportClient {
    private final WebClient client;
    private final String token;
    private final ProviderRuntimeRuleService rules;
    private final ObjectMapper mapper;

    public OfficialBrowserTransportClient(
        WebClient.Builder builder,
        Any2ApiProperties properties,
        ProviderRuntimeRuleService rules,
        ObjectMapper mapper
    ) {
        client = builder.clone()
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(20 << 20))
            .filter(RequestCorrelation.propagationFilter())
            .baseUrl(properties.getAutomation().getBaseUrl().toString())
            .build();
        token = properties.getSecurity().getInternalToken();
        this.rules = rules;
        this.mapper = mapper;
    }

    public Mono<TransportResponse> request(
        String providerId,
        String operation,
        JsonNode semanticCommand,
        JsonNode credential,
        Map<String, Object> proxyPool,
        String affinityKey
    ) {
        return request(
            providerId, operation, semanticCommand, credential, proxyPool, affinityKey,
            Map.of(), ProviderTransportMode.RUNTIME);
    }

    public Mono<TransportResponse> request(
        String providerId,
        String operation,
        JsonNode semanticCommand,
        JsonNode credential,
        Map<String, Object> proxyPool,
        String affinityKey,
        Map<String, Object> runtimeOptions
    ) {
        return request(
            providerId, operation, semanticCommand, credential, proxyPool, affinityKey,
            runtimeOptions, ProviderTransportMode.RUNTIME);
    }

    public Mono<TransportResponse> request(
        String providerId,
        String operation,
        JsonNode semanticCommand,
        JsonNode credential,
        Map<String, Object> proxyPool,
        String affinityKey,
        Map<String, Object> runtimeOptions,
        ProviderTransportMode transportMode
    ) {
        var request = transportMode == ProviderTransportMode.API
            ? request(providerId, null, operation, semanticCommand, credential, proxyPool,
                affinityKey, runtimeOptions, transportMode)
            : runtimePlan(providerId).flatMap(plan -> request(
                providerId, plan, operation, semanticCommand, credential, proxyPool,
                affinityKey, runtimeOptions, transportMode));
        return request
            .flatMap(value -> acceptReports(providerId, value.path("runtime_reports"))
                .thenReturn(new TransportResponse(
                    value.path("status").asInt(502),
                    value.path("body").asText(""),
                    value.path("credential_patch").deepCopy())));
    }

    private Mono<JsonNode> request(
        String providerId,
        ProviderRuntimeRuleService.RuntimePlan plan,
        String operation,
        JsonNode semanticCommand,
        JsonNode credential,
        Map<String, Object> proxyPool,
        String affinityKey,
        Map<String, Object> runtimeOptions,
        ProviderTransportMode transportMode
    ) {
        return client.post()
                .uri("/internal/v1/providers/{providerId}/actions/request", providerId)
                .headers(this::headers)
                .bodyValue(command(
                    operation, semanticCommand, plan, credential, proxyPool, affinityKey,
                    runtimeOptions, transportMode))
                .exchangeToMono(response -> response.bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .map(body -> decodeActionResponse(
                        response.statusCode().value(), body)));
    }

    public Flux<JsonNode> stream(
        String providerId,
        String operation,
        JsonNode semanticCommand,
        JsonNode credential,
        Map<String, Object> proxyPool,
        String affinityKey
    ) {
        return stream(
            providerId, operation, semanticCommand, credential, proxyPool, affinityKey,
            Map.of(), ProviderTransportMode.RUNTIME);
    }

    public Flux<JsonNode> stream(
        String providerId,
        String operation,
        JsonNode semanticCommand,
        JsonNode credential,
        Map<String, Object> proxyPool,
        String affinityKey,
        Map<String, Object> runtimeOptions
    ) {
        return stream(
            providerId, operation, semanticCommand, credential, proxyPool, affinityKey,
            runtimeOptions, ProviderTransportMode.RUNTIME);
    }

    public Flux<JsonNode> stream(
        String providerId,
        String operation,
        JsonNode semanticCommand,
        JsonNode credential,
        Map<String, Object> proxyPool,
        String affinityKey,
        Map<String, Object> runtimeOptions,
        ProviderTransportMode transportMode
    ) {
        var stream = transportMode == ProviderTransportMode.API
            ? stream(providerId, null, operation, semanticCommand, credential, proxyPool,
                affinityKey, runtimeOptions, transportMode)
            : runtimePlan(providerId).flatMapMany(plan -> stream(
                providerId, plan, operation, semanticCommand, credential, proxyPool,
                affinityKey, runtimeOptions, transportMode));
        return stream
            .concatMap(frame -> "runtime_canary".equals(frame.path("type").asText(""))
                ? acceptReport(providerId, frame).then(Mono.empty())
                : Mono.just(frame));
    }

    private Flux<JsonNode> stream(
        String providerId,
        ProviderRuntimeRuleService.RuntimePlan plan,
        String operation,
        JsonNode semanticCommand,
        JsonNode credential,
        Map<String, Object> proxyPool,
        String affinityKey,
        Map<String, Object> runtimeOptions,
        ProviderTransportMode transportMode
    ) {
        return client.post()
                .uri("/internal/v1/providers/{providerId}/actions/stream", providerId)
                .headers(this::headers)
                .bodyValue(command(
                    operation, semanticCommand, plan, credential, proxyPool, affinityKey,
                    runtimeOptions, transportMode))
                .exchangeToFlux(response -> {
                    var status = response.statusCode().value();
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToFlux(JsonNode.class);
                    }
                    return response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMapMany(body -> Flux.just(
                            statusFrame(status), errorFrame(status, body)));
                });
    }

    private Map<String, Object> command(
        String operation,
        JsonNode semanticCommand,
        ProviderRuntimeRuleService.RuntimePlan runtimePlan,
        JsonNode credential,
        Map<String, Object> proxyPool,
        String affinityKey
    ) {
        return command(
            operation, semanticCommand, runtimePlan, credential, proxyPool, affinityKey,
            Map.of(), ProviderTransportMode.RUNTIME);
    }

    private Map<String, Object> command(
        String operation,
        JsonNode semanticCommand,
        ProviderRuntimeRuleService.RuntimePlan runtimePlan,
        JsonNode credential,
        Map<String, Object> proxyPool,
        String affinityKey,
        Map<String, Object> runtimeOptions
    ) {
        return command(
            operation, semanticCommand, runtimePlan, credential, proxyPool, affinityKey,
            runtimeOptions, ProviderTransportMode.RUNTIME);
    }

    private Map<String, Object> command(
        String operation,
        JsonNode semanticCommand,
        ProviderRuntimeRuleService.RuntimePlan runtimePlan,
        JsonNode credential,
        Map<String, Object> proxyPool,
        String affinityKey,
        Map<String, Object> runtimeOptions,
        ProviderTransportMode transportMode
    ) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("credential", credential);
        if (proxyPool != null && !proxyPool.isEmpty()) {
            payload.put("proxy_pool", proxyPool);
        }
        if (affinityKey != null && !affinityKey.isBlank()) {
            payload.put("proxy_affinity_key", affinityKey);
            payload.put("strict_proxy_affinity", true);
        }
        if (credential != null && credential.has("proxy_node_offset")) {
            payload.put("proxy_node_offset",
                Math.max(0, credential.path("proxy_node_offset").asInt(0)));
        }
        if (runtimeOptions != null && !runtimeOptions.isEmpty()) {
            payload.put("runtime_options", Map.copyOf(runtimeOptions));
        }
        var command = new LinkedHashMap<String, Object>();
        command.put("action", ProviderAction.fromLegacyOperation(operation).externalName());
        command.put("channel", transportMode.externalName());
        if (operation != null && !operation.isBlank()) command.put("operation", operation);
        command.put("semantic_command", semanticCommand);
        command.put("runtime_plan", runtimePlan == null
            ? mapper.createObjectNode() : mapper.valueToTree(runtimePlan));
        command.put("payload", payload);
        return command;
    }

    private Mono<ProviderRuntimeRuleService.RuntimePlan> runtimePlan(String providerId) {
        return Mono.fromCallable(() -> rules.plan(providerId))
            .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Void> acceptReports(String providerId, JsonNode values) {
        if (!values.isArray() || values.isEmpty()) return Mono.empty();
        var reports = new ArrayList<ProviderRuntimeRuleService.CanaryReport>();
        values.forEach(value -> reports.add(report(providerId, value)));
        return Mono.fromRunnable(() -> reports.forEach(rules::acceptReport))
            .subscribeOn(Schedulers.boundedElastic()).then();
    }

    private Mono<Void> acceptReport(String providerId, JsonNode value) {
        var report = report(providerId, value);
        return Mono.fromRunnable(() -> rules.acceptReport(report))
            .subscribeOn(Schedulers.boundedElastic()).then();
    }

    private ProviderRuntimeRuleService.CanaryReport report(String providerId, JsonNode value) {
        return new ProviderRuntimeRuleService.CanaryReport(
            providerId,
            value.path("revision").asLong(),
            value.path("build_id").asText(""),
            ProviderRuntimeRuleService.CanaryStatus.valueOf(
                value.path("status").asText("").toUpperCase(java.util.Locale.ROOT)),
            value.path("reason").asText(""));
    }

    private JsonNode decodeActionResponse(int status, String body) {
        if (status >= 200 && status < 300) {
            try {
                var value = mapper.readTree(body);
                if (value != null) return value;
            } catch (RuntimeException ignored) {
                // Preserve the bounded body so the provider can classify malformed JSON.
            }
        }
        return mapper.createObjectNode()
            .put("status", status)
            .put("body", boundedBody(body));
    }

    private JsonNode statusFrame(int status) {
        return mapper.createObjectNode()
            .put("type", "status")
            .put("status", status);
    }

    private JsonNode errorFrame(int status, String body) {
        return mapper.createObjectNode()
            .put("type", "error")
            .put("data", summarize(status, body));
    }

    private String summarize(int status, String body) {
        var compact = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        if (compact.length() > 16_384) compact = compact.substring(0, 16_384);
        return compact.isBlank()
            ? "automation action returned HTTP " + status
            : "automation action returned HTTP " + status + ": " + compact;
    }

    private String boundedBody(String body) {
        var value = body == null ? "" : body;
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= 16_384) return value;
        return new String(bytes, 0, 16_384, StandardCharsets.UTF_8);
    }

    private void headers(HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        if (!token.isBlank()) headers.setBearerAuth(token);
    }

    public record TransportResponse(int status, String body, JsonNode credentialPatch) {}
}
