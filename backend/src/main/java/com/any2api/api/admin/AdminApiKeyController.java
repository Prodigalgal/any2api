package com.any2api.api.admin;

import com.any2api.auth.ApiKeyProtocol;
import com.any2api.auth.ApiKeyFeature;
import com.any2api.auth.ApiKeyService;
import com.any2api.auth.ApiKeyUsageService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v1/api-keys")
public class AdminApiKeyController {
    private final ApiKeyService keys;
    private final ApiKeyUsageService usage;

    public AdminApiKeyController(ApiKeyService keys, ApiKeyUsageService usage) {
        this.keys = keys;
        this.usage = usage;
    }

    @GetMapping
    public List<ApiKeyService.View> list() {
        return keys.list();
    }

    @GetMapping("/{id}")
    public ApiKeyUsageService.Detail get(@PathVariable UUID id) {
        return usage.get(id);
    }

    @PostMapping
    public ApiKeyService.Created create(@RequestBody CreateRequest request) {
        return keys.create(new ApiKeyService.CreateCommand(
            request.name(), request.providerModels(), request.protocols(), request.features(),
            request.expiresAt()));
    }

    @PatchMapping("/{id}")
    public ApiKeyService.View update(@PathVariable UUID id, @RequestBody UpdateRequest request) {
        if (request.enabled() == null) {
            throw new IllegalArgumentException("enabled is required");
        }
        return keys.setEnabled(id, request.enabled());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        keys.delete(id);
    }

    public record CreateRequest(
        String name,
        Map<String, List<String>> providerModels,
        Set<ApiKeyProtocol> protocols,
        Set<ApiKeyFeature> features,
        Instant expiresAt
    ) {}

    public record UpdateRequest(Boolean enabled) {}
}
