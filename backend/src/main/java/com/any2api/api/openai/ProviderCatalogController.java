package com.any2api.api.openai;

import com.any2api.lifecycle.AutomationProviderCatalog;
import com.any2api.lifecycle.ProviderLifecycleRegistry;
import com.any2api.provider.ProviderRegistry;
import com.any2api.provider.ProviderCapability;
import com.any2api.provider.SupportLevel;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProviderCatalogController {

    private final ProviderRegistry providers;
    private final AutomationProviderCatalog automation;
    private final ProviderLifecycleRegistry localLifecycle;

    public ProviderCatalogController(
        ProviderRegistry providers,
        AutomationProviderCatalog automation,
        ProviderLifecycleRegistry localLifecycle
    ) {
        this.providers = providers;
        this.automation = automation;
        this.localLifecycle = localLifecycle;
    }

    @GetMapping("/api/catalog/v1/providers")
    public Map<String, Object> providers() {
        var data = providers.list().stream().map(manifest -> new ProviderDescriptor(
            manifest.id(), manifest.displayName(), manifest.adapterVersion(),
            manifest.requestSchemaVersion(), manifest.defaultModels(), manifest.capabilities(),
            lifecycleOperations(manifest.id()),
            manifest.configured())).toList();
        return Map.of(
            "object", "list",
            "automationCatalogReady", automation.ready(),
            "data", data);
    }

    private List<String> lifecycleOperations(String providerId) {
        var operations = new HashSet<>(automation.operationsFor(providerId));
        operations.addAll(localLifecycle.operationsFor(providerId));
        return operations.stream().map(operation -> operation.externalName()).sorted().toList();
    }

    public record ProviderDescriptor(
        String id,
        String displayName,
        String adapterVersion,
        String requestSchemaVersion,
        List<String> defaultModels,
        Map<ProviderCapability, SupportLevel> capabilities,
        List<String> lifecycleOperations,
        boolean configured
    ) {}
}
