package com.any2api.lifecycle;

import com.any2api.config.Any2ApiProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;

@Component
public class AutomationProviderCatalog {
    private static final Logger log = LoggerFactory.getLogger(AutomationProviderCatalog.class);
    private static final Pattern PROVIDER_ID = Pattern.compile("^[a-z][a-z0-9_-]{1,31}$");

    private final WebClient client;
    private final String token;
    private final AtomicReference<Snapshot> current = new AtomicReference<>(Snapshot.empty());

    public AutomationProviderCatalog(WebClient.Builder builder, Any2ApiProperties properties) {
        client = builder.baseUrl(properties.getAutomation().getBaseUrl().toString()).build();
        token = properties.getSecurity().getInternalToken();
    }

    @Scheduled(
        initialDelayString = "${any2api.automation.catalog-initial-delay:0s}",
        fixedDelayString = "${any2api.automation.catalog-refresh-interval:1m}"
    )
    public void refresh() {
        try {
            var response = client.get()
                .uri("/internal/v1/capabilities")
                .headers(headers -> {
                    if (!token.isBlank()) {
                        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
                    }
                })
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(10));
            replaceFrom(response);
        } catch (RuntimeException error) {
            log.warn("Automation provider catalog refresh failed: {}", error.getMessage());
        }
    }

    public Set<AutomationOperation> operationsFor(String providerId) {
        return current.get().operations().getOrDefault(providerId, Set.of());
    }

    public boolean supports(String providerId, AutomationOperation operation) {
        return operationsFor(providerId).contains(operation);
    }

    public boolean ready() {
        return current.get().refreshedAt() != null;
    }

    void replaceFrom(JsonNode response) {
        if (response == null || !response.path("providers").isArray()) {
            throw new IllegalArgumentException("automation provider catalog is missing providers");
        }
        var parsed = new LinkedHashMap<String, Set<AutomationOperation>>();
        for (var provider : response.path("providers")) {
            var providerId = provider.path("id").asText("");
            if (!PROVIDER_ID.matcher(providerId).matches()) {
                throw new IllegalArgumentException("invalid automation provider id: " + providerId);
            }
            var operations = EnumSet.noneOf(AutomationOperation.class);
            if (!provider.path("operations").isArray()) {
                throw new IllegalArgumentException(
                    "automation provider operations are missing: " + providerId);
            }
            for (var operation : provider.path("operations")) {
                operations.add(AutomationOperation.fromExternalName(operation.asText("")));
            }
            if (parsed.putIfAbsent(providerId, Set.copyOf(operations)) != null) {
                throw new IllegalArgumentException(
                    "duplicate automation provider id: " + providerId);
            }
        }
        current.set(new Snapshot(Map.copyOf(parsed), Instant.now()));
    }

    private record Snapshot(
        Map<String, Set<AutomationOperation>> operations,
        Instant refreshedAt
    ) {
        static Snapshot empty() {
            return new Snapshot(Map.of(), null);
        }
    }
}
