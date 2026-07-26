package com.any2api.api.admin;

import com.any2api.account.AccountRepository;
import com.any2api.account.AccountStatus;
import com.any2api.config.Any2ApiProperties;
import com.any2api.provider.ProviderRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/admin/v1")
public class AdminOverviewController {

    private final ProviderRegistry providers;
    private final AccountRepository accounts;
    private final Any2ApiProperties properties;
    private final ExecutorService databaseExecutor;

    public AdminOverviewController(
        ProviderRegistry providers,
        AccountRepository accounts,
        Any2ApiProperties properties,
        ExecutorService databaseExecutor
    ) {
        this.providers = providers;
        this.accounts = accounts;
        this.properties = properties;
        this.databaseExecutor = databaseExecutor;
    }

    @GetMapping("/overview")
    public Mono<Map<String, Object>> overview() {
        return Mono.fromCallable(() -> {
            var result = new LinkedHashMap<String, Object>();
            result.put("service", "any2api-server");
            result.put("time", Instant.now());
            result.put("providers", providers.list());
            result.put("accounts", Map.of(
                "total", accounts.count(),
                "active", accounts.countByProviderIdAndStatus("grok", AccountStatus.ACTIVE)
                    + accounts.countByProviderIdAndStatus("mimo", AccountStatus.ACTIVE)
                    + accounts.countByProviderIdAndStatus("qwen", AccountStatus.ACTIVE)
                    + accounts.countByProviderIdAndStatus("longcat", AccountStatus.ACTIVE)));
            result.put("automationUrl", properties.getAutomation().getBaseUrl());
            return Map.copyOf(result);
        }).subscribeOn(reactor.core.scheduler.Schedulers.fromExecutor(databaseExecutor));
    }
}

