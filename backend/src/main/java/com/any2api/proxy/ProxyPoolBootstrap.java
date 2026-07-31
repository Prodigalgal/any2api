package com.any2api.proxy;

import com.any2api.config.Any2ApiProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class ProxyPoolBootstrap {
    private static final Logger log = LoggerFactory.getLogger(ProxyPoolBootstrap.class);

    private final ProxyPoolService pools;
    private final Any2ApiProperties properties;

    public ProxyPoolBootstrap(ProxyPoolService pools, Any2ApiProperties properties) {
        this.pools = pools;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void importMountedNodes() {
        var config = properties.getProxyBootstrap();
        if (config.getDirectory() == null || config.getDirectory().isBlank()) return;
        var directory = Path.of(config.getDirectory()).toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("proxy bootstrap directory is unavailable");
        }
        try (var files = Files.list(directory)) {
            var values = files.filter(Files::isRegularFile).sorted(Comparator.naturalOrder())
                .map(ProxyPoolBootstrap::readSecretFile)
                .flatMap(value -> value.lines())
                .map(String::trim)
                .filter(value -> !value.isBlank() && !value.startsWith("#"))
                .toList();
            if (values.isEmpty()) throw new IllegalStateException("proxy bootstrap has no nodes");
            pools.upsertBootstrapNodePool(config.getPoolName(), String.join("\n", values));
            log.info("Imported {} mounted proxy nodes into pool {}", values.size(),
                config.getPoolName());
        } catch (IOException error) {
            throw new IllegalStateException("proxy bootstrap directory could not be read", error);
        }
    }

    private static String readSecretFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException error) {
            throw new IllegalStateException("proxy bootstrap file could not be read", error);
        }
    }
}
