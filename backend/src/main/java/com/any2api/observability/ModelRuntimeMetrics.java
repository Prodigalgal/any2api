package com.any2api.observability;

import com.any2api.provider.ModelCatalogCache;
import com.any2api.provider.ModelRuntimeGuard;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.SignalType;

@Component
public final class ModelRuntimeMetrics {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModelRuntimeMetrics.class);
    private static final Duration REFRESH_TIMEOUT = Duration.ofSeconds(20);

    private final ModelCatalogCache catalog;
    private final ModelRuntimeGuard runtime;
    private final AtomicBoolean refreshInProgress = new AtomicBoolean();
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
        if (!refreshInProgress.compareAndSet(false, true)) {
            LOGGER.debug(
                "model runtime metrics refresh skipped because a refresh is still running");
            return;
        }
        catalog.list()
            .timeout(REFRESH_TIMEOUT)
            .doOnNext(this::register)
            .doOnError(error -> LOGGER.warn(
                "model runtime metrics refresh failed error_type={}",
                error.getClass().getSimpleName()))
            .doFinally(this::releaseRefresh)
            .subscribe(
                ignored -> {},
                ignored -> {});
    }

    private void register(List<ModelCatalogCache.Entry> models) {
        eligibleAccounts.register(rows(models, Value.ELIGIBLE), true);
        availableAccounts.register(rows(models, Value.AVAILABLE), true);
        quotaLimitedAccounts.register(rows(models, Value.QUOTA_LIMITED), true);
        health.register(rows(models, Value.HEALTH), true);
        rollingSuccessRate.register(rows(models, Value.SUCCESS_RATE), true);
    }

    private void releaseRefresh(SignalType signal) {
        refreshInProgress.set(false);
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
