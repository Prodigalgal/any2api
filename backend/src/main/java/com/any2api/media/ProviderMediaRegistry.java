package com.any2api.media;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class ProviderMediaRegistry {
    private final Map<String, ProviderMediaHandler> handlers;

    public ProviderMediaRegistry(List<ProviderMediaHandler> discoveredHandlers) {
        handlers = new LinkedHashMap<>();
        for (var handler : discoveredHandlers.stream()
            .sorted(Comparator.comparing(ProviderMediaHandler::providerId)).toList()) {
            if (handlers.putIfAbsent(handler.providerId(), handler) != null) {
                throw new IllegalArgumentException(
                    "duplicate provider media handler: " + handler.providerId());
            }
        }
    }

    public ProviderMediaHandler require(MediaRequest request) {
        var handler = handlers.get(request.providerId());
        if (handler == null || !handler.supports(request)) {
            throw new IllegalArgumentException(
                "provider does not support the requested media operation and model");
        }
        handler.validate(request);
        return handler;
    }
}
