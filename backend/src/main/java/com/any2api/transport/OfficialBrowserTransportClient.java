package com.any2api.transport;

import com.any2api.config.Any2ApiProperties;
import com.any2api.observability.RequestCorrelation;
import com.any2api.runtime.ProviderRuntimeRuleService;
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
        return runtimePlan(providerId).flatMap(plan -> client.post()
                .uri("/internal/v1/providers/{providerId}/transport/request", providerId)
                .headers(this::headers)
                .bodyValue(command(
                    operation, semanticCommand, plan, credential, proxyPool, affinityKey))
                .retrieve()
                .bodyToMono(JsonNode.class))
            .flatMap(value -> acceptReports(providerId, value.path("runtime_reports"))
                .thenReturn(new TransportResponse(
                    value.path("status").asInt(502),
                    value.path("body").asText(""),
                    value.path("credential_patch").deepCopy())));
    }

    public Flux<JsonNode> stream(
        String providerId,
        String operation,
        JsonNode semanticCommand,
        JsonNode credential,
        Map<String, Object> proxyPool,
        String affinityKey
    ) {
        return runtimePlan(providerId).flatMapMany(plan -> client.post()
                .uri("/internal/v1/providers/{providerId}/transport/stream", providerId)
                .headers(this::headers)
                .bodyValue(command(
                    operation, semanticCommand, plan, credential, proxyPool, affinityKey))
                .retrieve()
                .bodyToFlux(JsonNode.class))
            .concatMap(frame -> "runtime_canary".equals(frame.path("type").asText(""))
                ? acceptReport(providerId, frame).then(Mono.empty())
                : Mono.just(frame));
    }

    private Map<String, Object> command(
        String operation,
        JsonNode semanticCommand,
        ProviderRuntimeRuleService.RuntimePlan runtimePlan,
        JsonNode credential,
        Map<String, Object> proxyPool,
        String affinityKey
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
        var command = new LinkedHashMap<String, Object>();
        command.put("operation", operation);
        command.put("semantic_command", semanticCommand);
        command.put("runtime_plan", mapper.valueToTree(runtimePlan));
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

    private void headers(HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        if (!token.isBlank()) headers.setBearerAuth(token);
    }

    public record TransportResponse(int status, String body, JsonNode credentialPatch) {}
}
