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
    void providerRegistryKeepsASingleSpringInjectionConstructor() {
        assertThat(ProviderRegistry.class.getDeclaredConstructors()).hasSize(1);
    }

    @Test
    void providerIdsDoNotLeakIntoCoreJavaCode() throws IOException {
        try (var directories = Files.list(PROVIDER_ROOT)) {
            for (var providerDirectory : directories.filter(Files::isDirectory).toList()) {
                assertProviderIdDoesNotLeakIntoCore(providerDirectory);
                assertProviderDoesNotImportSiblingPackages(providerDirectory);
            }
        }
    }

    private void assertProviderIdDoesNotLeakIntoCore(Path providerDirectory) throws IOException {
        var providerId = providerDirectory.getFileName().toString();
        var providerToken = Pattern.compile(
            "\\b" + Pattern.quote(providerId) + "\\b", Pattern.CASE_INSENSITIVE);
        try (var sources = Files.walk(JAVA_ROOT)) {
            var leaks = sources
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> !path.startsWith(PROVIDER_ROOT)
                    || path.getParent().equals(PROVIDER_ROOT))
                .filter(path -> contains(path, providerToken))
                .toList();
            assertThat(leaks)
                .as("provider id %s must stay inside %s", providerId, providerDirectory)
                .isEmpty();
        }
    }

    @Test
    void providerParameterWhitelistsAreDeclaredAsContracts() throws IOException {
        try (var sources = Files.walk(PROVIDER_ROOT)) {
            var leaks = sources
                .filter(path -> path.toString().endsWith("Provider.java"))
                .filter(path -> {
                    try {
                        var content = Files.readString(path);
                        return content.contains("requireKnownOptions")
                            || content.contains("requireKnownGenerationParameters");
                    } catch (IOException error) {
                        throw new IllegalStateException(error);
                    }
                })
                .toList();
            assertThat(leaks)
                .as("provider parameter support must live in ProviderProtocolContract")
                .isEmpty();
        }
    }

    private void assertProviderDoesNotImportSiblingPackages(Path providerDirectory)
        throws IOException {
        var ownPackage = providerDirectory.getFileName().toString();
        var siblingImport = Pattern.compile(
            "import\\s+com\\.any2api\\.provider\\.(?!" + Pattern.quote(ownPackage) + "\\.)"
                + "[a-z][a-z0-9_]*\\.");
        try (var sources = Files.walk(providerDirectory)) {
            var imports = sources
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> contains(path, siblingImport))
                .toList();
            assertThat(imports)
                .as("provider %s must not import a sibling provider package", ownPackage)
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
