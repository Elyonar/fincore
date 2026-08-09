package org.elyonar.fincore.identity.internal;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Migrations, run explicitly as the schema owner — the same arrangement Core and Notification
 * use, for the same reason: two datasources exist and only the owner may run DDL, which
 * autoconfiguration cannot know. The seeder is ordered against this bean, because seeding an
 * unmigrated database is the first thing a fresh deployment would otherwise try.
 */
@Configuration
public class Migrations {

    /**
     * The schema this deployable owns: {@code auth}, inside the {@code identity} database.
     *
     * <p>Named for what it holds rather than for the service that holds it. {@code identity.identity}
     * says the same word twice and neither time usefully; {@code auth.credentials} reads as what it
     * is. The deployable is identity, the database is identity, the schema is its subject.
     */
    public static final String SCHEMA = "auth";

    @Bean(initMethod = "migrate")
    public Flyway flyway(@Qualifier("dataSource") DataSource ownerDataSource) {
        return Flyway.configure()
                .dataSource(ownerDataSource)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .locations("classpath:db/migration")
                .validateOnMigrate(true)
                .cleanDisabled(true)
                .load();
    }
}
