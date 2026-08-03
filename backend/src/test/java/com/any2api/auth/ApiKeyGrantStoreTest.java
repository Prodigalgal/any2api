package com.any2api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;

class ApiKeyGrantStoreTest {
    @Test
    void finalJdbcStoreDoesNotRequestRepositoryProxying() {
        assertThat(ApiKeyGrantStore.class).hasAnnotation(Component.class);
        assertThat(ApiKeyGrantStore.class.isAnnotationPresent(Repository.class)).isFalse();
    }
}
