package org.elyonar.fincore.customer.internal;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Migrations, run explicitly as the schema owner.
 *
 * <p>Two datasources exist and exactly one may run DDL, which autoconfiguration has no way to know.
 * Naming the owner here is the same answer Core and Notification give, rather than a third
 * arrangement invented for this service.
 *
 * <p>Traffic connects as {@code customer_app}, which holds DML only. DDL and traffic are different
 * jobs and must not share an identity (ADR 0007, service-scaffold §4).
 *
 * <p>The schema is still called {@code customer} and the migration is the one Core applied. This
 * service inherited it rather than redefining it: a rewrite at the moment of extraction would have
 * meant reviewing a new schema and a new deployable at once, and this schema holds the only PII on
 * the platform — the worst place on the platform to combine two kinds of change.
 */
@Configuration
public class Migrations {

    @Bean(initMethod = "migrate")
    public Flyway flyway(@Qualifier("dataSource") DataSource ownerDataSource) {
        return Flyway.configure()
                .dataSource(ownerDataSource)
                .schemas("customer")
                .defaultSchema("customer")
                .locations("classpath:db/migration")
                // Append-only: an applied migration is never edited, and a checksum mismatch is a
                // build failure rather than something to repair away.
                .validateOnMigrate(true)
                .cleanDisabled(true)
                .load();
    }
}
