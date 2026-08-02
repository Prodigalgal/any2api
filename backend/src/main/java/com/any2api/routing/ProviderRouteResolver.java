package com.any2api.routing;

import com.any2api.protocol.OpenAiRequestException;
import com.any2api.provider.ProviderRegistry;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ProviderRouteResolver {

    private static final Pattern PROVIDER_PATH =
        Pattern.compile("^/([a-z][a-z0-9_-]{1,31})/v1(?:/.*)?$");
    private final ProviderRegistry registry;

    public ProviderRouteResolver(ProviderRegistry registry) {
        this.registry = registry;
    }

    public ResolvedRoute resolve(String requestPath, String requestedModel) {
        if (requestedModel == null || requestedModel.isBlank()) {
            throw OpenAiRequestException.invalid("model", "model is required");
        }
        var pathProvider = providerFromPath(requestPath);
        var separator = requestedModel.indexOf('/');
        var modelProvider = separator > 0 ? requestedModel.substring(0, separator) : null;
        var upstreamModel = separator > 0 ? requestedModel.substring(separator + 1) : requestedModel;

        if (pathProvider == null && modelProvider == null) {
            throw OpenAiRequestException.invalid(
                "model", "unified /v1 requests require a provider/model identifier");
        }
        if (pathProvider != null && modelProvider != null && !pathProvider.equals(modelProvider)) {
            throw OpenAiRequestException.conflict(
                "model", "provider path conflicts with model namespace");
        }
        var provider = pathProvider != null ? pathProvider : modelProvider;
        registry.require(provider);
        if (upstreamModel.isBlank()) {
            throw OpenAiRequestException.invalid("model", "upstream model is required");
        }
        return new ResolvedRoute(provider, upstreamModel);
    }

    private String providerFromPath(String path) {
        var matcher = PROVIDER_PATH.matcher(path);
        return matcher.matches() ? matcher.group(1) : null;
    }
}
