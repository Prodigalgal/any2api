package com.any2api.provider;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class JdbcProviderInstallationCatalog implements ProviderInstallationCatalog {

    private final JdbcClient jdbc;
    private final AtomicReference<Set<String>> enabled = new AtomicReference<>();

    public JdbcProviderInstallationCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void requireInstalled(String providerId) {
        var installed = jdbc.sql("""
            SELECT EXISTS(
                SELECT 1 FROM providers WHERE id = :providerId AND installed = TRUE
            )
            """)
            .param("providerId", providerId)
            .query(Boolean.class)
            .single();
        if (!installed) {
            throw new IllegalArgumentException("unknown provider: " + providerId);
        }
    }

    @Override
    public boolean isEnabled(String providerId) {
        var snapshot = enabled.get();
        if (snapshot == null) {
            refresh();
            snapshot = enabled.get();
        }
        return snapshot.contains(providerId);
    }

    @Override
    @Scheduled(fixedDelayString = "${any2api.providers.refresh-interval:1s}")
    public void refresh() {
        enabled.set(Set.copyOf(jdbc.sql("""
            SELECT id FROM providers
            WHERE installed = TRUE AND enabled = TRUE
            """).query(String.class).list()));
    }
}
