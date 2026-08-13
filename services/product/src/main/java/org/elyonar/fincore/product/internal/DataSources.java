package org.elyonar.fincore.product.internal;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.elyonar.fincore.product.api.ProductBeans;
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
 * Two identities: the owner that migrates, and the role that serves.
 *
 * <p>{@code product_app} serves every request, always scoped to one tenant by {@code SET LOCAL}.
 * There is no worker identity because there is no background work here — nothing is claimed from a
 * queue and nothing is drained. A catalogue answers questions.
 *
 * <p>The owner connection runs migrations at startup and never serves a request, so it holds one
 * connection rather than a pool's worth for the life of the process.
 *
 * <p>The bean names are the ones {@code ProductBeans} already declared when this was a module
 * inside Core, and they are kept deliberately: the persistence code moved unchanged, and renaming
 * its wiring at the same time as moving it would have made the diff impossible to review.
 */
@Configuration
public class DataSources {

    /**
     * Migrations only. Owns the schema; never serves a request.
     *
     * <p>Built explicitly rather than by binding {@code spring.datasource}: that block's
     * {@code url} does not map to Hikari's {@code jdbcUrl} through a manual builder, and the
     * resulting failure is a startup error naming neither property. Core hit this, Notification
     * wrote the reason down, and repeating it here would be a way of not reading either.
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

    @Bean(ProductBeans.DATA_SOURCE)
    @ConfigurationProperties("fincore.product.datasource.app")
    public HikariDataSource productDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    /*
     * Qualified by name, and that is not stylistic. The owner bean is @Primary and is a
     * HikariDataSource at runtime, so an unqualified by-type injection resolves to *it* — and the
     * owner is the database owner, which PostgreSQL exempts from row-level security entirely. The
     * result would be a service running every query as the one identity no policy applies to,
     * while the catalog still reports every policy enabled.
     */

    @Bean(ProductBeans.JDBC)
    public JdbcTemplate productJdbcTemplate(
            @Qualifier(ProductBeans.DATA_SOURCE) HikariDataSource productDataSource) {
        return new JdbcTemplate(productDataSource);
    }

    @Bean(ProductBeans.TRANSACTION_MANAGER)
    public PlatformTransactionManager productTransactionManager(
            @Qualifier(ProductBeans.DATA_SOURCE) HikariDataSource productDataSource) {
        return new DataSourceTransactionManager(productDataSource);
    }
}
