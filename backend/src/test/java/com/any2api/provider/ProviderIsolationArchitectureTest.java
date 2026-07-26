package com.any2api.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ProviderIsolationArchitectureTest {

    private static final Path JAVA_ROOT = Path.of("src/main/java");
    private static final Path PROVIDER_ROOT = JAVA_ROOT.resolve("com/any2api/provider");

    @Test
    void providerIdsDoNotLeakIntoCoreJavaCode() throws IOException {
        try (var directories = Files.list(PROVIDER_ROOT)) {
            for (var providerDirectory : directories.filter(Files::isDirectory).toList()) {
                assertProviderIdIsIsolated(providerDirectory);
            }
        }
    }

    private void assertProviderIdIsIsolated(Path providerDirectory) throws IOException {
        var providerId = providerDirectory.getFileName().toString();
        var providerToken = Pattern.compile("\\b" + Pattern.quote(providerId) + "\\b");
        try (var sources = Files.walk(JAVA_ROOT)) {
            var leaks = sources
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> !path.startsWith(providerDirectory))
                .filter(path -> contains(path, providerToken))
                .toList();
            assertThat(leaks)
                .as("provider id %s must stay inside %s", providerId, providerDirectory)
                .isEmpty();
        }
    }

    private boolean contains(Path path, Pattern pattern) {
        try {
            return pattern.matcher(Files.readString(path)).find();
        } catch (IOException error) {
            throw new IllegalStateException("failed to inspect " + path, error);
        }
    }
}
