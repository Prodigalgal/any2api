package com.any2api.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.any2api.provider.ProviderInstallationCatalog;
import com.any2api.provider.ProviderRegistry;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ProxyPoolArchitectureTest {

    @Test
    void proxyPoolsDoNotDependOnRuntimeProviderInstances() {
        var constructorTypes = Arrays.stream(ProxyPoolService.class.getConstructors())
            .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
            .toList();

        assertThat(constructorTypes).doesNotContain(ProviderRegistry.class);
        assertThat(constructorTypes).contains(ProviderInstallationCatalog.class);
    }
}
