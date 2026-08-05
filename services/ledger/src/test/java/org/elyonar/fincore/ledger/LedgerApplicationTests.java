package org.elyonar.fincore.ledger;

import org.elyonar.fincore.ledger.support.LedgerPostgresTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The application context starts, which now means: the datasource connects, Flyway migrates a
 * fresh database to the current version, and the actuator health contributors resolve.
 *
 * <p>It extends {@link LedgerPostgresTest} because the ledger has a database as of V1 — a
 * context test that stubbed it out would pass while the real service failed to boot.
 */
@DisplayName("the application context starts against a migrated database")
class LedgerApplicationTests extends LedgerPostgresTest {

    @Test
    void contextLoads() {}
}
