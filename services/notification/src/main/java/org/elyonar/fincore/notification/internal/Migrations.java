package org.elyonar.fincore.notification.internal;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Migrations, run explicitly as the schema owner.
 *
 * <p>The ledger leaves this to autoconfiguration and is right to: it has one datasource, and Boot
 * migrates it. This service is shaped like Core instead — three datasources, of which exactly one
 * may run DDL — and autoconfiguration has no way to know which. Naming the owner here is the same
 * answer Core's {@code FlywayConfiguration} gives for the same reason, rather than a third
 * arrangement invented for this service.
 *
 * <p>Traffic connects as {@code notification_app} and {@code notification_worker}, which hold DML
 * only. DDL and traffic are different jobs and must not share an identity (ADR 0007,
 * service-scaffold §4).
 *
 * <p>Returning a {@code Flyway} bean with {@code initMethod} rather than an ad-hoc initializer
 * gives anything that reads a migrated table at startup a bean to be ordered against — the channel
 * registry needs exactly that, and an empty database is where a missing ordering shows up.
 */
@Configuration
public class Migrations {

    @Bean(initMethod = "migrate")
    public Flyway flyway(@Qualifier("dataSource") DataSource ownerDataSource) {
        return Flyway.configure()
                .dataSource(ownerDataSource)
                .schemas("notification")
                .defaultSchema("notification")
                .locations("classpath:db/migration")
                // Append-only: an applied migration is never edited, and a checksum mismatch is a
                // build failure rather than something to repair away.
                .validateOnMigrate(true)
                .cleanDisabled(true)
                .load();
    }
}
