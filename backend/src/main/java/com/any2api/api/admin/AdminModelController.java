package com.any2api.api.admin;

import com.any2api.provider.ModelProbeService;
import com.any2api.provider.ModelTokenPolicyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/admin/v1/models")
public final class AdminModelController {
    private final ModelProbeService probes;
    private final ModelTokenPolicyService policies;

    public AdminModelController(ModelProbeService probes, ModelTokenPolicyService policies) {
        this.probes = probes;
        this.policies = policies;
    }

    @PostMapping("/probe")
    public Mono<ModelProbeService.Result> probe(@RequestBody ProbeRequest request) {
        return probes.probe(request.providerId(), request.modelId());
    }

    @GetMapping("/limits")
    public Mono<java.util.List<ModelTokenPolicyService.PolicyView>> limits() {
        return policies.list();
    }

    @PutMapping("/limits")
    public Mono<ModelTokenPolicyService.PolicyView> updateLimits(
        @RequestBody ModelTokenPolicyService.UpdateRequest request
    ) {
        return policies.update(request);
    }

    public record ProbeRequest(String providerId, String modelId) {}
}
