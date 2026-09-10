package com.any2api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class Any2ApiPropertiesTest {

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
