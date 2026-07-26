package com.any2api.provider;

import com.any2api.config.Any2ApiProperties;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.node.ObjectNode;

/** Base implementation for an upstream that already exposes OpenAI-compatible HTTP and SSE. */
public abstract class OpenAiBridgeProvider implements InferenceProvider {

    private final Any2ApiProperties.Provider connection;
    private final String id;
    private final String displayName;
    private final String adapterVersion;
    private final List<String> defaultModels;
    private final Map<ProviderCapability, SupportLevel> capabilities;

    protected OpenAiBridgeProvider(
        Any2ApiProperties properties,
        String id,
        String displayName,
        String adapterVersion,
        List<String> defaultModels,
        Map<ProviderCapability, SupportLevel> providerCapabilities
    ) {
        this.connection = properties.getProviders().get(id);
        this.id = id;
        this.displayName = displayName;
        this.adapterVersion = adapterVersion;
        this.defaultModels = List.copyOf(defaultModels);
        var resolved = new EnumMap<ProviderCapability, SupportLevel>(ProviderCapability.class);
        for (var capability : ProviderCapability.values()) {
            resolved.put(capability, SupportLevel.UNSUPPORTED);
        }
        resolved.put(ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE);
        resolved.put(ProviderCapability.RESPONSES, SupportLevel.NATIVE);
        resolved.put(ProviderCapability.STREAMING, SupportLevel.NATIVE);
        resolved.putAll(providerCapabilities);
        this.capabilities = Map.copyOf(resolved);
    }

    @Override
    public final ProviderManifest manifest() {
        return new ProviderManifest(
            id,
            displayName,
            adapterVersion,
            "1",
            defaultModels,
            capabilities,
            connection != null && connection.configured());
    }

    @Override
    public final PreparedProviderRequest prepare(
        ProviderOperation operation,
        ObjectNode request,
        String upstreamModel
    ) {
        if (connection == null || !connection.configured()) {
            throw new IllegalStateException("provider " + id + " is not configured");
        }
        if (capabilities.get(operation.capability()) == SupportLevel.UNSUPPORTED) {
            throw new IllegalArgumentException("provider " + id + " does not support " + operation);
        }
        var transformed = request.deepCopy();
        transformed.put("model", upstreamModel);
        transformRequest(operation, transformed);
        return new PreparedProviderRequest(
            connection.getBaseUrl(),
            connection.getApiKey(),
            operation.upstreamPath(),
            transformed);
    }

    protected void transformRequest(ProviderOperation operation, ObjectNode request) {
        // Provider packages override only their own request differences.
    }

    @Override
    public ProviderFailure classify(Throwable error) {
        return new ProviderFailure(
            "provider_transport_error",
            error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(),
            true,
            Map.of("provider", id));
    }
}
