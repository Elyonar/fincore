package org.elyonar.fincore.core.app;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One migration history per module, three of them, run as the schema owner.
 *
 * <p>Spring Boot's autoconfiguration manages a single {@link Flyway}, which would give the three
 * modules one shared version sequence: adding a table to Product would collide with an unrelated
 * migration in Orchestration, and the histories could never be separated when a module is
 * extracted. So autoconfiguration is off ({@code spring.flyway.enabled=false}) and each module
 * gets its own instance, its own schema, its own history table, and its own location.
 *
 * <p>Migrations run as the owner. Traffic connects as the per-module roles, which hold DML only —
 * DDL and traffic are different jobs and must not share an identity (ADR 0007).
 */
@Configuration
public class FlywayConfiguration {

    /**
     * The schemas this deployable migrates, in a fixed order so a failure is reproducible rather
     * than arbitrary.
     *
     * <p>{@code platform} is not a module and owns no domain: it holds the tenant registry, which
     * is a fact about the deployable rather than about customer, product or orchestration. Putting
     * it in a module's schema would force the other two to read another module's table, which
     * ADR 0006 forbids outright. It migrates last because its backfill reads the three that
     * precede it.
     */
    private static final String[] MODULES = {"customer", "product", "orchestration", "platform"};

    @Bean
    public InitializingBean migrateEveryModule(DataSource ownerDataSource) {
        return () -> {
            for (String module : MODULES) {
                Flyway.configure()
                        .dataSource(ownerDataSource)
                        .schemas(module)
                        .defaultSchema(module)
                        // Per-module history, so extracting a module takes its migration record
                        // with it rather than leaving it interleaved with two others'.
                        .table("schema_history")
                        .locations("classpath:db/migration/" + module)
                        // Append-only: an applied migration is never edited, and a checksum
                        // mismatch is a build failure rather than something to repair away.
                        .validateOnMigrate(true)
                        .cleanDisabled(true)
                        .load()
                        .migrate();
            }
        };
    }
}
