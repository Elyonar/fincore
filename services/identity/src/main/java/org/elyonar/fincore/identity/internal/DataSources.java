package org.elyonar.fincore.identity.internal;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two identities, two jobs (service-scaffold §4): the owner runs migrations and never serves a
 * request; {@code identity_app} serves traffic, restricted and NOBYPASSRLS, always scoped by
 * {@code SET LOCAL} where a tenant applies.
 */
@Configuration
@EnableConfigurationProperties(IdentityProperties.class)
public class DataSources {

    /** Migrations only. Owns the schema; never serves a request. */
    @Bean
    @Primary
    public DataSource dataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password) {
        HikariDataSource ds = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(url)
                .username(username)
                .password(password)
                .build();
        ds.setMaximumPoolSize(1);
        ds.setMinimumIdle(0);
        return ds;
    }

    @Bean
    @ConfigurationProperties("fincore.identity.datasource.app")
    public HikariDataSource appDataSource() {
        return new HikariDataSource();
    }

    @Bean
    public JdbcTemplate appJdbcTemplate(@Qualifier("appDataSource") DataSource appDataSource) {
        return new JdbcTemplate(appDataSource);
    }

    @Bean
    public PlatformTransactionManager transactionManager(
            @Qualifier("appDataSource") DataSource appDataSource) {
        return new DataSourceTransactionManager(appDataSource);
    }

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager tm) {
        return new TransactionTemplate(tm);
    }
}
