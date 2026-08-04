package com.any2api.api.admin;

import com.any2api.provider.ModelProbeService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/admin/v1/models")
public final class AdminModelController {
    private final ModelProbeService probes;

    public AdminModelController(ModelProbeService probes) {
        this.probes = probes;
    }

    @PostMapping("/probe")
    public Mono<ModelProbeService.Result> probe(@RequestBody ProbeRequest request) {
        return probes.probe(request.providerId(), request.modelId());
    }

    public record ProbeRequest(String providerId, String modelId) {}
}
