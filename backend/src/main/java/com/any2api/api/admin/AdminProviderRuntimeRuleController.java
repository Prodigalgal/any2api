package com.any2api.api.admin;

import com.any2api.runtime.ProviderRuntimeRuleService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v1/provider-runtime-rules")
public class AdminProviderRuntimeRuleController {
    private final ProviderRuntimeRuleService rules;

    public AdminProviderRuntimeRuleController(ProviderRuntimeRuleService rules) {
        this.rules = rules;
    }

    @GetMapping
    public List<ProviderRuntimeRuleService.RuleStateView> list() {
        return rules.list();
    }

    @GetMapping("/{providerId}")
    public ProviderRuntimeRuleService.RuleStateView get(@PathVariable String providerId) {
        return rules.get(providerId);
    }

    @PostMapping("/{providerId}/candidates")
    @ResponseStatus(HttpStatus.CREATED)
    public ProviderRuntimeRuleService.RuleStateView createCandidate(
        @PathVariable String providerId,
        @RequestBody ProviderRuntimeRuleService.RuleDocument request
    ) {
        return rules.createCandidate(providerId, request);
    }

    @PostMapping("/{providerId}/rollback/{revision}")
    public ProviderRuntimeRuleService.RuleStateView rollback(
        @PathVariable String providerId,
        @PathVariable long revision
    ) {
        return rules.rollback(providerId, revision);
    }

    @DeleteMapping("/{providerId}/candidate")
    public ProviderRuntimeRuleService.RuleStateView discardCandidate(
        @PathVariable String providerId
    ) {
        return rules.discardCandidate(providerId);
    }
}
