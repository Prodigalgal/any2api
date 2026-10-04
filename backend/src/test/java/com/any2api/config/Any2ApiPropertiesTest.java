package com.any2api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class Any2ApiPropertiesTest {
    @Test
    void cacheWaitBudgetIsPositiveAndDefaultsTo250Millis() {
        var cache = new Any2ApiProperties().getCache();
        assertThat(cache.getRedisAccessTimeout()).isEqualTo(java.time.Duration.ofMillis(250));
        assertThatIllegalArgumentException().isThrownBy(() -> cache.setRedisAccessTimeout(null));
        assertThatIllegalArgumentException().isThrownBy(() -> cache.setRedisAccessTimeout(java.time.Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> cache.setRedisAccessTimeout(java.time.Duration.ofMillis(-1)));
    }


    @Test
    void lifecycleAdmissionDefaultsToTwoActions() {
        assertThat(new Any2ApiProperties().getLifecycle().getActionConcurrency()).isEqualTo(2);
    }

    @Test
    void lifecycleAdmissionRejectsValuesOutsideTheSafeRange() {
        var lifecycle = new Any2ApiProperties().getLifecycle();

        assertThatIllegalArgumentException()
            .isThrownBy(() -> lifecycle.setActionConcurrency(0));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> lifecycle.setActionConcurrency(9));
    }
}
