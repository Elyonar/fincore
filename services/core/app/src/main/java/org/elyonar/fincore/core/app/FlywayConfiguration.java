package org.elyonar.fincore.core.app;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One migration history per module, run as the schema owner.
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
     * is a fact about the deployable rather than about orchestration or organization. Putting it in
     * a module's schema would force the other to read its neighbour's table, which the module
     * boundary forbids outright. It migrates last because its backfill reads what precedes it.
     *
     * <p>{@code customer} and {@code product} left this list with ADR 0020. Leaving them in did
     * something quietly wrong rather than failing: with no migrations on the classpath Flyway
     * still created the schema and an empty {@code schema_history} in it, so a freshly purged Core
     * database grew two empty schemas named after services that no longer live here — exactly the
     * kind of residue that later reads as evidence the extraction never happened.
     *
     * <p>The per-module history below is what made their removal clean. Because each module's
     * migrations were recorded in their own schema rather than interleaved in one table, taking
     * the module out took its record with it and left nothing to unpick.
     */
    private static final String[] MODULES = {"organization", "orchestration", "platform"};

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
