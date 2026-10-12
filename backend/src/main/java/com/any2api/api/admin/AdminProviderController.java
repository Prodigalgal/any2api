package com.any2api.api.admin;

import com.any2api.provider.ProviderRuntimeService;
import com.any2api.provider.ProviderTransportMode;
import java.util.List;
import java.util.UUID;
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
        boolean updateProxy = Boolean.TRUE.equals(request.clearProxyPool())
            || request.proxyPoolId() != null
            || Boolean.TRUE.equals(request.updateProxyPool());
        if (request.enabled() == null && request.transportMode() == null
            && request.probeModel() == null && !updateProxy) {
            throw new IllegalArgumentException("enabled, transport_mode, probe_model, or proxy_pool_id is required");
        }
        var targetProxyPoolId = Boolean.TRUE.equals(request.clearProxyPool()) ? null : request.proxyPoolId();
        return providers.setState(
            providerId, request.enabled(), request.transportMode(), request.probeModel(),
            targetProxyPoolId, updateProxy);
    }

    public record StateRequest(
        Boolean enabled,
        ProviderTransportMode transportMode,
        String probeModel,
        UUID proxyPoolId,
        Boolean clearProxyPool,
        Boolean updateProxyPool
    ) {}
}
