package com.any2api.coordination;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PostgresAdvisoryLockArchitectureTest {

    private static final Path JAVA_ROOT = Path.of("src/main/java");
    private static final Path LOCKS = JAVA_ROOT.resolve(
        "com/any2api/coordination/PostgresAdvisoryLocks.java");

    @Test
    void postgresAdvisoryLockSqlStaysInsideTheLockAdapter() throws IOException {
        try (var sources = Files.walk(JAVA_ROOT)) {
            var violations = sources
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> !path.equals(LOCKS))
                .filter(this::containsAdvisoryLockSql)
                .toList();

            assertThat(violations).isEmpty();
        }
    }

    private boolean containsAdvisoryLockSql(Path path) {
        try {
            return Files.readString(path).contains("advisory_xact_lock");
        } catch (IOException error) {
            throw new IllegalStateException("failed to inspect " + path, error);
        }
    }
}
