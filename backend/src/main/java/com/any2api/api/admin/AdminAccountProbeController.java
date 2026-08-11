package com.any2api.api.admin;

import com.any2api.lifecycle.AccountProbeService;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/admin/v1/account-probes")
public final class AdminAccountProbeController {
    private final AccountProbeService probes;

    public AdminAccountProbeController(AccountProbeService probes) {
        this.probes = probes;
    }

    @PostMapping
    public Mono<AccountProbeService.Result> probe(@RequestBody ProbeRequest request) {
        if (request.accountId() == null) {
            return Mono.error(new IllegalArgumentException("account id is required"));
        }
        return probes.probe(request.accountId(), request.modelId());
    }

    public record ProbeRequest(UUID accountId, String modelId) {}
}
