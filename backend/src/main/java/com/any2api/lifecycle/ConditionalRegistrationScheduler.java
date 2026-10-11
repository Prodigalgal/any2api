package com.any2api.lifecycle;

import com.any2api.account.AccountRepository;
import com.any2api.config.Any2ApiProperties;
import com.any2api.provider.ProviderCapability;
import com.any2api.provider.ProviderRegistry;
import com.any2api.provider.SupportLevel;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ConditionalRegistrationScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConditionalRegistrationScheduler.class);

    private final AccountRepository accounts;
    private final RegistrationJobService registrationJobs;
    private final ProviderRegistry providers;
    private final AutomationProviderCatalog automationProviders;
    private final JdbcClient jdbc;
    private final Any2ApiProperties properties;
    private final ConcurrentHashMap<String, Instant> lastTriggered = new ConcurrentHashMap<>();

    public ConditionalRegistrationScheduler(
        AccountRepository accounts,
        RegistrationJobService registrationJobs,
        ProviderRegistry providers,
        AutomationProviderCatalog automationProviders,
        JdbcClient jdbc,
        Any2ApiProperties properties
    ) {
        this.accounts = accounts;
        this.registrationJobs = registrationJobs;
        this.providers = providers;
        this.automationProviders = automationProviders;
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${any2api.lifecycle.conditional-registration.check-interval:30s}")
    public void checkAndTrigger() {
        var config = properties.getLifecycle().getConditionalRegistration();
        if (!config.isEnabled()) {
            return;
        }

        var now = Instant.now();
        for (var provider : providers.list()) {
            var providerId = provider.id();
            if (!supportsRegistration(providerId, provider)) {
                continue;
            }

            try {
                evaluateProvider(providerId, config, now);
            } catch (Exception error) {
                LOGGER.warn("Conditional registration evaluation failed for provider {}: {}",
                    providerId, error.getMessage(), error);
            }
        }
    }

    void evaluateProvider(
        String providerId,
        Any2ApiProperties.Lifecycle.ConditionalRegistration config,
        Instant now
    ) {
        long activeAccounts = accounts.countActiveByProviderId(providerId, now);
        if (activeAccounts >= config.getMinActiveAccounts()) {
            return;
        }

        Integer inFlight = jdbc.sql("""
            SELECT COUNT(*) FROM registration_jobs
            WHERE provider_id = :provider AND status IN ('PENDING', 'RUNNING')
            """).param("provider", providerId).query(Integer.class).single();
        if (inFlight != null && inFlight > 0) {
            return;
        }

        var last = lastTriggered.get(providerId);
        if (last != null && now.isBefore(last.plus(config.getCooldown()))) {
            return;
        }

        Integer recentJobs = jdbc.sql("""
            SELECT COUNT(*) FROM registration_jobs
            WHERE provider_id = :provider
              AND idempotency_key LIKE 'conditional-reg:%'
              AND created_at > :threshold
            """)
            .param("provider", providerId)
            .param("threshold", java.sql.Timestamp.from(now.minus(config.getCooldown())))
            .query(Integer.class).single();
        if (recentJobs != null && recentJobs > 0) {
            return;
        }

        int target = config.getBatchTarget();
        long periodBucket = config.getCooldown().toMillis() > 0
            ? now.toEpochMilli() / config.getCooldown().toMillis()
            : now.toEpochMilli();
        String idempotencyKey = "conditional-reg:" + providerId + ":" + periodBucket;
        var command = new RegistrationJobService.CreateCommand(
            providerId, target, null, null, null, null, null, null, null, null, null, null, null, null, idempotencyKey
        );
        LOGGER.info("Triggering conditional registration for provider {}: activeAccounts={} < threshold={}, target={}",
            providerId, activeAccounts, config.getMinActiveAccounts(), target);
        registrationJobs.create(command);
        lastTriggered.put(providerId, now);
    }

    private boolean supportsRegistration(String providerId, com.any2api.provider.ProviderManifest manifest) {
        return manifest.capabilities().getOrDefault(ProviderCapability.REGISTRATION, SupportLevel.UNSUPPORTED) != SupportLevel.UNSUPPORTED
            && automationProviders.supports(providerId, AutomationOperation.REGISTER);
    }
}
