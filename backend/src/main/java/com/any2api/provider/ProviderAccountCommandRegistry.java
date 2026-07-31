package com.any2api.provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class ProviderAccountCommandRegistry {
    private static final Pattern COMMAND = Pattern.compile("[a-z][a-z0-9_-]{1,63}");
    private final Map<String, ProviderCommands> handlers;

    public ProviderAccountCommandRegistry(
        List<ProviderAccountCommandHandler> discovered,
        ProviderRegistry providers
    ) {
        var indexed = new LinkedHashMap<String, ProviderCommands>();
        for (var handler : discovered) {
            providers.requirePlugin(handler.providerId());
            var commands = new LinkedHashMap<String,
                ProviderAccountCommandHandler.CommandDescriptor>();
            for (var descriptor : handler.commands()) {
                if (!COMMAND.matcher(descriptor.name()).matches()) {
                    throw new IllegalArgumentException(
                        "invalid provider account command: " + descriptor.name());
                }
                if (commands.putIfAbsent(descriptor.name(), descriptor) != null) {
                    throw new IllegalArgumentException(
                        "duplicate provider account command: " + descriptor.name());
                }
            }
            if (commands.isEmpty()) {
                throw new IllegalArgumentException(
                    "provider account command handler must declare commands");
            }
            if (indexed.putIfAbsent(handler.providerId(),
                new ProviderCommands(handler, Map.copyOf(commands))) != null) {
                throw new IllegalArgumentException(
                    "duplicate provider account command handler: " + handler.providerId());
            }
        }
        handlers = Map.copyOf(indexed);
    }

    public List<ProviderAccountCommandHandler.CommandDescriptor> commandsFor(String providerId) {
        return Optional.ofNullable(handlers.get(providerId))
            .map(value -> value.commands().values().stream().sorted(
                java.util.Comparator.comparing(
                    ProviderAccountCommandHandler.CommandDescriptor::name)).toList())
            .orElseGet(List::of);
    }

    public ProviderAccountCommandHandler require(String providerId, String command) {
        var provider = handlers.get(providerId);
        if (provider == null || !provider.commands().containsKey(command)) {
            throw new IllegalArgumentException(
                "provider does not support account command: " + command);
        }
        return provider.handler();
    }

    private record ProviderCommands(
        ProviderAccountCommandHandler handler,
        Map<String, ProviderAccountCommandHandler.CommandDescriptor> commands
    ) {}
}
