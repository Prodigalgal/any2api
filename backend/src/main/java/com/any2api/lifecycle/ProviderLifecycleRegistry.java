package com.any2api.lifecycle;

import com.any2api.provider.ProviderRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class ProviderLifecycleRegistry {
    private final Map<String, ProviderLifecycleHandler> handlers;

    public ProviderLifecycleRegistry(
        List<ProviderLifecycleHandler> discovered,
        ProviderRegistry providers
    ) {
        var indexed = new LinkedHashMap<String, ProviderLifecycleHandler>();
        for (var handler : discovered) {
            var providerId = handler.providerId();
            providers.requirePlugin(providerId);
            if (handler.operations() == null || handler.operations().isEmpty()) {
                throw new IllegalArgumentException(
                    "provider lifecycle handler must declare at least one operation");
            }
            if (indexed.putIfAbsent(providerId, handler) != null) {
                throw new IllegalArgumentException(
                    "duplicate provider lifecycle handler: " + providerId);
            }
        }
        handlers = Map.copyOf(indexed);
    }

    public Optional<ProviderLifecycleHandler> handler(
        String providerId,
        AutomationOperation operation
    ) {
        return Optional.ofNullable(handlers.get(providerId))
            .filter(handler -> handler.operations().contains(operation));
    }

    public Set<AutomationOperation> operationsFor(String providerId) {
        var handler = handlers.get(providerId);
        return handler == null ? Set.of() : handler.operations();
    }
}
