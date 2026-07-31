package com.any2api.api.admin;

import com.any2api.provider.ProviderRuntimeService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v1/providers")
public class AdminProviderController {
    private final ProviderRuntimeService providers;

    public AdminProviderController(ProviderRuntimeService providers) {
        this.providers = providers;
    }

    @GetMapping
    public List<ProviderRuntimeService.ProviderRuntimeView> list() {
        return providers.list();
    }

    @PatchMapping("/{providerId}")
    public ProviderRuntimeService.ProviderRuntimeView update(
        @PathVariable String providerId,
        @RequestBody StateRequest request
    ) {
        if (request.enabled() == null) {
            throw new IllegalArgumentException("enabled is required");
        }
        return providers.setEnabled(providerId, request.enabled());
    }

    public record StateRequest(Boolean enabled) {}
}
