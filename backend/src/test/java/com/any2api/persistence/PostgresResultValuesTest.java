package com.any2api.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PostgresResultValuesTest {

    @Test
    void convertsInstantToAnExplicitPostgresTimestampType() {
        var instant = Instant.parse("2026-08-03T09:00:00Z");

        var timestamp = PostgresResultValues.timestamp(instant);

        assertThat(timestamp)
            .isInstanceOf(OffsetDateTime.class)
            .isEqualTo(instant.atOffset(ZoneOffset.UTC));
    }
}
