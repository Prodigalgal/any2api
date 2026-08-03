package com.any2api.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PostgresResultValuesTest {

    @Test
    void convertsPostgresTimestampWithTimeZoneToInstant() throws Exception {
        var result = mock(ResultSet.class);
        var timestamp = OffsetDateTime.parse("2026-07-30T11:30:00+08:00");
        when(result.getObject("created_at", OffsetDateTime.class)).thenReturn(timestamp);

        assertThat(PostgresResultValues.instant(result, "created_at"))
            .isEqualTo(timestamp.toInstant());
    }

    @Test
    void convertsInstantToPostgresTimestampWithTimeZone() {
        var instant = Instant.parse("2026-07-30T04:30:00Z");

        assertThat(PostgresResultValues.timestamp(instant))
            .isEqualTo(OffsetDateTime.ofInstant(instant, ZoneOffset.UTC));
        assertThat(PostgresResultValues.timestamp(null)).isNull();
    }

    @Test
    void instantIsNotRequestedDirectlyFromJdbcResultSets() throws Exception {
        try (var sources = Files.walk(Path.of("src/main/java"))) {
            var violations = sources
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> {
                    try {
                        return Files.readString(path).contains(", Instant.class)");
                    } catch (java.io.IOException error) {
                        throw new IllegalStateException("failed to inspect " + path, error);
                    }
                })
                .toList();

            assertThat(violations).isEmpty();
        }
    }
}
