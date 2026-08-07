package org.elyonar.fincore.notification.internal;

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
 * Two identities, because they do different jobs under different tenancy rules.
 *
 * <p>{@code notification_app} serves requests and the intake pipeline, always scoped to one tenant
 * by {@code SET LOCAL}. {@code notification_worker} claims queued messages across every tenant and
 * therefore has no tenant to be scoped by — it opts into a narrow worker policy rather than holding
 * BYPASSRLS, which would exempt it from row-level security on every table at once.
 *
 * <p>The owner connection runs migrations at startup and never serves a request, so it holds one
 * connection rather than a pool's worth for the life of the process.
 */
@Configuration
public class DataSources {

    /**
     * Migrations only. Owns the schema; never serves a request.
     *
     * <p>Built explicitly rather than by binding {@code spring.datasource}: that block's
     * {@code url} does not map to Hikari's {@code jdbcUrl} through a manual builder, and the
     * resulting failure is a startup error naming neither property. Core hit this and wrote the
     * reason down; repeating the mistake would have been a way of not reading it.
     */
    @Bean
    @Primary
    public DataSource dataSource(
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
    @ConfigurationProperties("fincore.notification.datasource.app")
    public HikariDataSource appDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean
    @ConfigurationProperties("fincore.notification.datasource.worker")
    public HikariDataSource workerDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    // Every injection below is qualified by name, and that is not stylistic. The owner bean is
    // @Primary and is a HikariDataSource at runtime, so an unqualified by-type injection resolves
    // to *it* — and the owner is a superuser, which PostgreSQL exempts from row-level security
    // entirely. The result is a service that runs every query as the one identity no policy
    // applies to, while the catalog still reports every policy enabled. Caught here by the tenant
    // isolation test, which is exactly the failure that test exists for.

    @Bean
    public JdbcTemplate appJdbcTemplate(@Qualifier("appDataSource") HikariDataSource appDataSource) {
        return new JdbcTemplate(appDataSource);
    }

    @Bean
    public JdbcTemplate workerJdbcTemplate(@Qualifier("workerDataSource") HikariDataSource workerDataSource) {
        return new JdbcTemplate(workerDataSource);
    }

    @Bean
    public PlatformTransactionManager appTransactionManager(
            @Qualifier("appDataSource") HikariDataSource appDataSource) {
        return new DataSourceTransactionManager(appDataSource);
    }

    @Bean
    public PlatformTransactionManager workerTransactionManager(
            @Qualifier("workerDataSource") HikariDataSource workerDataSource) {
        return new DataSourceTransactionManager(workerDataSource);
    }
}
