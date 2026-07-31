package com.any2api.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class SpringProxyArchitectureTest {

    private static final Path JAVA_ROOT = Path.of("src/main/java");
    private static final Pattern FINAL_CLASS = Pattern.compile("\\bfinal\\s+class\\b");

    @Test
    void transactionalSpringBeansRemainProxyable() throws IOException {
        try (var sources = Files.walk(JAVA_ROOT)) {
            var violations = sources
                .filter(path -> path.toString().endsWith(".java"))
                .filter(this::isFinalTransactionalSpringBean)
                .toList();

            assertThat(violations)
                .as("transactional Spring beans must be subclass-proxyable")
                .isEmpty();
        }
    }

    private boolean isFinalTransactionalSpringBean(Path path) {
        try {
            var source = Files.readString(path);
            return source.contains("@Transactional")
                && (source.contains("@Component")
                    || source.contains("@Service")
                    || source.contains("@Repository"))
                && FINAL_CLASS.matcher(source).find();
        } catch (IOException error) {
            throw new IllegalStateException("failed to inspect " + path, error);
        }
    }
}
