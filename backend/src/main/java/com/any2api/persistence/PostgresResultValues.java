package com.any2api.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public final class PostgresResultValues {

    private PostgresResultValues() {}

    public static Instant instant(ResultSet result, String column) throws SQLException {
        var value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public static OffsetDateTime timestamp(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
