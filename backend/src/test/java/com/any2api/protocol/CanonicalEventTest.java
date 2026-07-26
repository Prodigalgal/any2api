package com.any2api.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CanonicalEventTest {

    @Test
    void eventCarriesStableSchemaAndSequence() {
        CanonicalEvent event = new CanonicalEvent.OutputTextDelta(1, "req-1", 4, "hello");
        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.requestId()).isEqualTo("req-1");
        assertThat(event.sequenceNumber()).isEqualTo(4);
    }
}

