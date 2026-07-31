package com.any2api.provider;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

public interface ProviderAccountCommandHandler {
    String providerId();

    List<CommandDescriptor> commands();

    Mono<CommandResult> execute(String command, CommandContext context);

    record CommandDescriptor(String name, String displayName, boolean idempotent) {}

    record CommandContext(
        UUID accountId,
        Map<String, Object> metadata,
        JsonNode credential,
        Map<String, Object> proxyPool
    ) {}

    record CommandResult(JsonNode metadataPatch, JsonNode credentialPatch) {}
}
