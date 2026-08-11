package com.any2api.provider;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ProviderRegistry {

    private static final Pattern PROVIDER_ID = Pattern.compile("^[a-z][a-z0-9_-]{1,31}$");
    private final Map<String, InferenceProvider> providers;
    private final Predicate<String> enabled;

    public ProviderRegistry(
        List<InferenceProvider> discoveredProviders,
        ProviderInstallationCatalog installations
    ) {
        this.enabled = installations::isEnabled;
        providers = new LinkedHashMap<>();
        for (var provider : discoveredProviders.stream()
            .sorted(Comparator.comparing(item -> item.manifest().id())).toList()) {
            var id = provider.manifest().id();
            if (!PROVIDER_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("invalid provider id: " + id);
            }
            if (providers.putIfAbsent(id, provider) != null) {
                throw new IllegalArgumentException("duplicate provider id: " + id);
            }
            requireProtocolFamily(provider.manifest(), ProviderCapability.CHAT_COMPLETIONS);
            requireProtocolFamily(provider.manifest(), ProviderCapability.RESPONSES);
            requireProtocolContract(provider);
        }
    }

    public static ProviderRegistry allEnabled(List<InferenceProvider> discoveredProviders) {
        return new ProviderRegistry(discoveredProviders, new ProviderInstallationCatalog() {
            @Override
            public void requireInstalled(String providerId) {
                // Every discovered provider is installed in this isolated registry.
            }

            @Override
            public boolean isEnabled(String providerId) {
                return true;
            }

            @Override
            public void refresh() {
                // The isolated registry has no external installation state to refresh.
            }
        });
    }

    public List<ProviderManifest> list() {
        return enabledPlugins().stream().map(InferenceProvider::manifest).toList();
    }

    public List<InferenceProvider> plugins() {
        return List.copyOf(providers.values());
    }

    public List<InferenceProvider> enabledPlugins() {
        return providers.values().stream()
            .filter(provider -> enabled.test(provider.manifest().id()))
            .toList();
    }

    public InferenceProvider requirePlugin(String id) {
        var provider = providers.get(id);
        if (provider == null) {
            throw new IllegalArgumentException("unknown provider: " + id);
        }
        return provider;
    }

    public InferenceProvider require(String id) {
        var provider = requirePlugin(id);
        if (!enabled.test(id)) {
            throw new IllegalArgumentException("provider is disabled: " + id);
        }
        return provider;
    }

    private void requireProtocolFamily(ProviderManifest manifest, ProviderCapability capability) {
        var support = manifest.capabilities().getOrDefault(capability, SupportLevel.UNSUPPORTED);
        if (support == SupportLevel.UNSUPPORTED) {
            throw new IllegalArgumentException(
                "provider " + manifest.id() + " must implement " + capability);
        }
    }

    private void requireProtocolContract(InferenceProvider provider) {
        var contract = provider.protocolContract();
        if (contract.toolTypes().contains("function")
            && provider.manifest().capabilities().getOrDefault(
                ProviderCapability.FUNCTION_TOOLS, SupportLevel.UNSUPPORTED)
                == SupportLevel.UNSUPPORTED) {
            throw new IllegalArgumentException(
                "provider " + provider.manifest().id()
                    + " accepts function tools without declaring FUNCTION_TOOLS");
        }
    }
}
