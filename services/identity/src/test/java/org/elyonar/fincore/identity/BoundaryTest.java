package org.elyonar.fincore.identity;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The boundaries this service claims, enforced rather than described. Its POM says what is absent —
 * no ledger client, no Core client, no client of any kind — and this is where that stops being a
 * claim a reviewer takes on trust.
 *
 * <p>The empty-import canary is not decoration (service-scaffold §7): every {@code no…should…}
 * rule passes vacuously on an empty import set, and that has broken a boundary suite here twice
 * when a toolchain bump could not read Java 25 bytecode.
 */
@DisplayName("boundaries — the arrow points at identity, and never leaves it")
class BoundaryTest {

    private static JavaClasses production;

    @BeforeAll
    static void importProductionCode() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("org.elyonar.fincore.identity");
    }

    @Test
    @DisplayName("the import set is not empty — every other rule depends on it")
    void canaryIsAlive() {
        assertThat(production)
                .as("ArchUnit imported nothing, so every rule below would pass while enforcing nothing")
                .isNotEmpty();
    }

    @Test
    @DisplayName("this service reaches for no other deployable")
    void callsNoOtherDeployable() {
        // Identity is the thing everything else points at; the arrow must never reverse. A client
        // onto the ledger, Core or Notification here would be a boundary violation, not a feature.
        //
        // Fully qualified, like the libs/auth rule below, and not `..core..`: ArchUnit's `..x..`
        // matches *any* package with a segment named `x`, so `..core..` also names
        // org.springframework.jdbc.core and org.springframework.core.env. Every JdbcTemplate on the
        // service reported as a deployable-boundary violation — 53 of them — which is a rule that
        // cannot pass rather than a rule that catches anything.
        noClasses()
                .should()
                .accessClassesThat()
                .resideInAnyPackage(
                        "org.elyonar.fincore.ledger..",
                        "org.elyonar.fincore.core..",
                        "org.elyonar.fincore.notification..")
                .because("identity is called; it calls no other deployable (ADR 0018, package-info)")
                .check(production);
    }

    @Test
    @DisplayName("libs/auth is a test-only dependency — this service is the issuer, not a verifier of others")
    void doesNotDependOnLibsAuthAtRuntime() {
        noClasses()
                .should()
                .accessClassesThat()
                .resideInAnyPackage("org.elyonar.fincore.auth..")
                .because("libs/auth is the parity suite's other half (test scope); at runtime this"
                        + " service verifies its own tokens locally, it does not import the resolver")
                .check(production);
    }
}
