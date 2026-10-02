package com.any2api.interop;

import com.any2api.protocol.state.GatewayResponseStore;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.util.UUID;
import javax.sql.DataSource;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.databind.ObjectMapper;

public final class InteropDatabase implements AutoCloseable {
    public static final UUID PRIMARY_KEY = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID OTHER_KEY = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final EmbeddedPostgres postgres;
    public final DataSource dataSource;
    public final JdbcClient jdbc;

    public InteropDatabase() throws Exception {
        postgres = EmbeddedPostgres.builder().setServerConfig("listen_addresses", "127.0.0.1").start();
        dataSource = postgres.getPostgresDatabase();
        jdbc = JdbcClient.create(dataSource);
        try (var liquibase = migration()) {
            liquibase.update(new liquibase.Contexts(), new liquibase.LabelExpression());
        }
        for (var id : java.util.List.of(PRIMARY_KEY, OTHER_KEY)) {
            jdbc.sql("INSERT INTO api_keys(id,name,prefix,key_hash) VALUES(:id,'interop','fixture',:hash)")
                .param("id", id).param("hash", id.toString()).update();
        }
    }

    public Liquibase migration() throws Exception {
        return new Liquibase("db/changelog/db.changelog-master.yaml", new ClassLoaderResourceAccessor(),
            new JdbcConnection(dataSource.getConnection()));
    }

    public GatewayResponseStore store(ObjectMapper mapper) {
        var target = new GatewayResponseStore(jdbc, mapper, new com.any2api.coordination.PostgresAdvisoryLocks(jdbc));
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
            new AnnotationTransactionAttributeSource()));
        return (GatewayResponseStore) proxy.getProxy();
    }

    @Override public void close() throws Exception { postgres.close(); }
}
