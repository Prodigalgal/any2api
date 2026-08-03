package com.any2api.api.admin;

import com.any2api.account.AccountManagementService;
import com.any2api.account.AccountCommandService;
import com.any2api.provider.ProviderAccountCommandHandler;
import com.any2api.account.AccountStatus;
import com.any2api.account.AccountView;
import com.any2api.account.AccountPageView;
import com.any2api.account.AccountSearchQuery;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/admin/v1/accounts")
public class AdminAccountController {
    private final AccountManagementService accounts;
    private final AccountCommandService commands;

    public AdminAccountController(
        AccountManagementService accounts,
        AccountCommandService commands
    ) {
        this.accounts = accounts;
        this.commands = commands;
    }

    @GetMapping("/page")
    public AccountPageView page(
        @RequestParam(name = "provider", required = false) String provider,
        @RequestParam(name = "status", required = false) AccountStatus status,
        @RequestParam(name = "enabled", required = false) Boolean enabled,
        @RequestParam(name = "query", required = false) String query,
        @RequestParam(name = "expiry", defaultValue = "ANY") AccountSearchQuery.Expiry expiry,
        @RequestParam(name = "page", defaultValue = "0") int page,
        @RequestParam(name = "size", defaultValue = "25") int size
    ) {
        return accounts.search(new AccountSearchQuery(
            provider, status, enabled, query, expiry, page, size));
    }

    @PostMapping("/import")
    public AccountManagementService.ImportResult importAccount(@RequestBody ImportRequest request) {
        return accounts.importAccount(new AccountManagementService.ImportCommand(
            request.providerId(), request.externalId(), request.email(), request.expiresAt(),
            request.credentialExpiresAt(), request.metadata(), request.priority(), request.weight(),
            request.maxConcurrency(), request.status(), request.enabled(), request.credential(),
            request.scheduleLifecycle()));
    }

    @PatchMapping("/{accountId}")
    public AccountView updateState(
        @PathVariable UUID accountId,
        @RequestBody StateRequest request
    ) {
        return accounts.updateState(accountId,
            new AccountManagementService.StateCommand(request.status(), request.enabled()));
    }

    @DeleteMapping("/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID accountId) {
        accounts.delete(accountId);
    }

    @PostMapping("/{accountId}/reauthenticate")
    public AccountView reauthenticate(@PathVariable UUID accountId) {
        return accounts.reauthenticate(accountId);
    }

    @PostMapping("/{accountId}/probe")
    public AccountView probe(
        @PathVariable UUID accountId,
        @RequestBody(required = false) ProbeRequest request
    ) {
        var spreadSeconds = request == null || request.spreadSeconds() == null
            ? 3600L : request.spreadSeconds();
        return accounts.scheduleProbe(accountId, java.time.Duration.ofSeconds(spreadSeconds));
    }

    @GetMapping("/{accountId}/commands")
    public List<ProviderAccountCommandHandler.CommandDescriptor> commands(
        @PathVariable UUID accountId
    ) {
        return commands.commands(accountId);
    }

    @PostMapping("/{accountId}/commands/{command}")
    public Mono<AccountCommandService.ExecutionResult> executeCommand(
        @PathVariable UUID accountId,
        @PathVariable String command
    ) {
        return commands.execute(accountId, command);
    }

    public record ImportRequest(
        String providerId,
        String externalId,
        String email,
        Instant expiresAt,
        Instant credentialExpiresAt,
        Map<String, Object> metadata,
        Integer priority,
        Integer weight,
        Integer maxConcurrency,
        AccountStatus status,
        Boolean enabled,
        JsonNode credential,
        Boolean scheduleLifecycle
    ) {
    }

    public record StateRequest(AccountStatus status, Boolean enabled) {
    }

    public record ProbeRequest(Long spreadSeconds) {}
}
