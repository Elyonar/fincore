package org.elyonar.fincore.core.app;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * One DataSource per module, each connecting as that module's own database role.
 *
 * <p>This is where the module boundary stops being a convention. Each role is granted on its own
 * schema and nothing else, so a query that reaches across modules fails at runtime — in the test
 * suite, on the first attempt — rather than surviving until someone tries to extract a module and
 * discovers years of quiet coupling (ADR 0006).
 *
 * <p>It is also the bulkhead: separate pools mean a slow customer search cannot consume the
 * connections a transfer needs.
 *
 * <p>The owner DataSource is separate again and used only for migrations. DDL and traffic are
 * different jobs and must not share an identity (ADR 0007).
 */
@Configuration
public class ModuleDataSources {

    /**
     * Migrations only. Owns the schemas; never serves a request.
     *
     * <p>Built explicitly rather than by binding {@code spring.datasource}: that block's {@code url}
     * does not map to Hikari's {@code jdbcUrl} through a manual builder, and the resulting failure
     * is a startup error that names neither property.
     */
    @Bean
    @Primary
    public DataSource ownerDataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password) {
        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(url)
                .username(username)
                .password(password)
                .build();
    }

    @Bean
    @ConfigurationProperties("fincore.core.datasource.customer")
    public DataSource customerDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean
    @ConfigurationProperties("fincore.core.datasource.product")
    public DataSource productDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean
    @ConfigurationProperties("fincore.core.datasource.orchestration")
    public DataSource orchestrationDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    /**
     * The saga worker's connection.
     *
     * <p>A separate role because the request path and the worker need different things from the
     * same tables: requests are tenant-scoped, while the worker must see every tenant's outstanding
     * work. One role cannot be both.
     */
    @Bean
    @ConfigurationProperties("fincore.core.datasource.worker")
    public DataSource workerDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean
    public JdbcTemplate workerJdbcTemplate(@Qualifier("workerDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    @Bean
    public PlatformTransactionManager workerTransactionManager(@Qualifier("workerDataSource") DataSource ds) {
        return new DataSourceTransactionManager(ds);
    }

    /**
     * The relay's connection.
     *
     * <p>Granted on the outbox tables only. It is delivery infrastructure rather than a module, and
     * it has no business reading a saga — which is also why it gets a policy rather than BYPASSRLS.
     */
    @Bean
    @ConfigurationProperties("fincore.core.datasource.relay")
    public DataSource relayDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean
    public JdbcTemplate relayJdbcTemplate(@Qualifier("relayDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    @Bean
    public PlatformTransactionManager relayTransactionManager(@Qualifier("relayDataSource") DataSource ds) {
        return new DataSourceTransactionManager(ds);
    }

    @Bean
    public JdbcTemplate customerJdbcTemplate(@Qualifier("customerDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    @Bean
    public JdbcTemplate productJdbcTemplate(@Qualifier("productDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    @Bean
    @Primary
    public JdbcTemplate orchestrationJdbcTemplate(@Qualifier("orchestrationDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    /**
     * A transaction manager per module.
     *
     * <p>Orchestration's is primary, because the transaction that matters — the one committing the
     * limit reservation and the saga row together — is its. Customer and Product are read-only
     * within Phase A, so their managers never participate in it, which is what keeps that
     * atomicity a property of one connection rather than a distributed protocol.
     */
    @Bean
    @Primary
    public PlatformTransactionManager orchestrationTransactionManager(
            @Qualifier("orchestrationDataSource") DataSource ds) {
        return new DataSourceTransactionManager(ds);
    }

    @Bean
    public PlatformTransactionManager customerTransactionManager(
            @Qualifier("customerDataSource") DataSource ds) {
        return new DataSourceTransactionManager(ds);
    }

    @Bean
    public PlatformTransactionManager productTransactionManager(
            @Qualifier("productDataSource") DataSource ds) {
        return new DataSourceTransactionManager(ds);
    }
}
