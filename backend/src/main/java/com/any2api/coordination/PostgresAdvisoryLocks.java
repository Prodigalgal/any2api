package com.any2api.coordination;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public final class PostgresAdvisoryLocks {

    private final JdbcClient jdbc;

    public PostgresAdvisoryLocks(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void lockTransaction(String key) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:lockKey))")
            .param("lockKey", key)
            .query((result, rowNumber) -> Boolean.TRUE)
            .single();
    }

    public boolean tryLockTransaction(String key) {
        return Boolean.TRUE.equals(jdbc.sql(
                "SELECT pg_try_advisory_xact_lock(hashtext(:lockKey))")
            .param("lockKey", key)
            .query(Boolean.class)
            .single());
    }
}
