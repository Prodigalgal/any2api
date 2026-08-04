package com.any2api.observability;

import com.any2api.provider.ModelCatalogCache;
import com.any2api.provider.ModelRuntimeGuard;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class ModelRuntimeMetrics {
    private final ModelCatalogCache catalog;
    private final ModelRuntimeGuard runtime;
    private final MultiGauge eligibleAccounts;
    private final MultiGauge availableAccounts;
    private final MultiGauge quotaLimitedAccounts;
    private final MultiGauge health;
    private final MultiGauge rollingSuccessRate;

    public ModelRuntimeMetrics(
        ModelCatalogCache catalog,
        ModelRuntimeGuard runtime,
        io.micrometer.core.instrument.MeterRegistry meters
    ) {
        this.catalog = catalog;
        this.runtime = runtime;
        this.eligibleAccounts = MultiGauge.builder("any2api.model.accounts.eligible")
            .register(meters);
        this.availableAccounts = MultiGauge.builder("any2api.model.accounts.available")
            .register(meters);
        this.quotaLimitedAccounts = MultiGauge.builder("any2api.model.accounts.quota_limited")
            .register(meters);
        this.health = MultiGauge.builder("any2api.model.health")
            .description("Model health: READY=2, DEGRADED=1, UNAVAILABLE=0")
            .register(meters);
        this.rollingSuccessRate = MultiGauge.builder("any2api.model.success.rate")
            .register(meters);
    }

    @Scheduled(
        initialDelayString = "${any2api.model-runtime.metrics-initial-delay:30s}",
        fixedDelayString = "${any2api.model-runtime.metrics-interval:30s}"
    )
    public void refresh() {
        var models = catalog.list().block(Duration.ofSeconds(20));
        if (models == null) return;
        eligibleAccounts.register(rows(models, Value.ELIGIBLE), true);
        availableAccounts.register(rows(models, Value.AVAILABLE), true);
        quotaLimitedAccounts.register(rows(models, Value.QUOTA_LIMITED), true);
        health.register(rows(models, Value.HEALTH), true);
        rollingSuccessRate.register(rows(models, Value.SUCCESS_RATE), true);
    }

    private List<MultiGauge.Row<?>> rows(List<ModelCatalogCache.Entry> models, Value value) {
        return models.stream().<MultiGauge.Row<?>>map(model -> MultiGauge.Row.of(
            Tags.of("provider", model.providerId(), "model", model.id()),
            switch (value) {
                case ELIGIBLE -> model.eligibleAccountCount();
                case AVAILABLE -> model.availableAccountCount();
                case QUOTA_LIMITED -> model.quotaLimitedAccountCount();
                case HEALTH -> !runtime.callable(model.providerId(), model.id()) ? 0
                    : switch (model.runtimeStatus()) {
                        case "READY" -> 2;
                        case "DEGRADED" -> 1;
                        default -> 0;
                    };
                case SUCCESS_RATE -> model.rollingSuccessRate();
            })).toList();
    }

    private enum Value { ELIGIBLE, AVAILABLE, QUOTA_LIMITED, HEALTH, SUCCESS_RATE }
}
