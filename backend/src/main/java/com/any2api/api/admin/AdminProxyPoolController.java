package com.any2api.api.admin;

import com.any2api.proxy.ProxyPoolService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v1/proxy-pools")
public class AdminProxyPoolController {
    private final ProxyPoolService pools;

    public AdminProxyPoolController(ProxyPoolService pools) { this.pools = pools; }

    @GetMapping
    public List<ProxyPoolService.ProxyPoolView> list() { return pools.list(); }

    @PostMapping
    public ProxyPoolService.ProxyPoolView create(@RequestBody SaveRequest request) {
        return pools.create(command(request));
    }

    @PutMapping("/{poolId}")
    public ProxyPoolService.ProxyPoolView update(
        @PathVariable UUID poolId,
        @RequestBody SaveRequest request
    ) {
        return pools.update(poolId, command(request));
    }

    @DeleteMapping("/{poolId}")
    public ResponseEntity<Void> delete(@PathVariable UUID poolId) {
        pools.delete(poolId);
        return ResponseEntity.noContent().build();
    }

    private static ProxyPoolService.SaveCommand command(SaveRequest request) {
        return new ProxyPoolService.SaveCommand(
            request.name(), request.mode(), request.enabled(), request.source(),
            request.providerIds(), request.bindingScopes());
    }

    public record SaveRequest(
        String name, String mode, Boolean enabled, String source, List<String> providerIds,
        Map<String, List<String>> bindingScopes
    ) {}
}
