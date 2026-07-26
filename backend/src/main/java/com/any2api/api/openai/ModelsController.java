package com.any2api.api.openai;

import com.any2api.provider.ProviderManifest;
import com.any2api.provider.ProviderRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ModelsController {

    private final ProviderRegistry registry;

    public ModelsController(ProviderRegistry registry) {
        this.registry = registry;
    }

    @GetMapping({"/v1/models", "/api/catalog/v1/models"})
    public Map<String, Object> models() {
        var data = registry.list().stream()
            .flatMap(provider -> provider.defaultModels().stream().map(model -> model(provider, model, true)))
            .toList();
        return Map.of("object", "list", "data", data);
    }

    @GetMapping("/{providerId:[a-z][a-z0-9_-]{1,31}}/v1/models")
    public Map<String, Object> providerModels(@PathVariable String providerId) {
        var provider = registry.require(providerId);
        var manifest = provider.manifest();
        var data = manifest.defaultModels().stream().map(model -> model(manifest, model, false)).toList();
        return Map.of("object", "list", "data", data);
    }

    private Map<String, Object> model(ProviderManifest provider, String model, boolean namespaced) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", namespaced ? provider.id() + "/" + model : model);
        result.put("object", "model");
        result.put("created", Instant.now().getEpochSecond());
        result.put("owned_by", provider.id());
        result.put("available", provider.configured());
        result.put("capabilities", provider.capabilities());
        return result;
    }
}
