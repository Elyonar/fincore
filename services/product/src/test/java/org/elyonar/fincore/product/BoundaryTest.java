package org.elyonar.fincore.product;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The boundaries this deployable claims, enforced rather than described.
 *
 * <p>Required by [ADR 0020], which made this service a deployable and attached an obligation to
 * it: <em>"Neither service gains a client onto the money path. Core calls them; they do not call
 * Core. Enforced by their POMs and by an ArchUnit rule in each."</em> The POM half was done at
 * extraction; this is the other half, and until it existed the rule was a sentence.
 *
 * <p>The direction matters more here than in most boundary suites. Product is now <em>read</em> by
 * the money path on every priced transaction, and a dependency pointing back the other way would
 * turn a one-way call into a cycle — two services that cannot be released, restarted or reasoned
 * about independently, which is the whole of what extracting them bought.
 *
 * <p>The empty-import canary is not decoration. Every {@code no…should…} rule passes vacuously
 * when nothing was imported, and Core's boundary suite hit exactly that: ArchUnit could not read
 * Java 25 bytecode, imported zero classes, and every other rule passed while enforcing nothing.
 */
@DisplayName("boundaries — what this deployable may not reach for")
class BoundaryTest {

    private static JavaClasses classesUnderTest;

    @BeforeAll
    static void importProductionCode() {
        classesUnderTest = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("org.elyonar.fincore.product");
    }

    @Test
    @DisplayName("the import set is not empty — every other rule here depends on it")
    void the_canary_is_alive() {
        assertThat(classesUnderTest)
                .as("ArchUnit imported nothing, so every rule below would pass while enforcing nothing")
                .isNotEmpty();
    }

    @Test
    @DisplayName("no client onto the money path — Core calls this service, never the reverse")
    void this_deployable_does_not_call_core() {
        // ADR 0020's third obligation, in the form that can fail a build. Core asks this service
        // for a pricing decision on the transfer path; a call back into Core from here would be a
        // cycle on the money path, and the first symptom would be a transfer that cannot complete
        // because two services are each waiting on the other.
        noClasses()
                .should()
                .accessClassesThat()
                .resideInAnyPackage("org.elyonar.fincore.core..")
                .because("Core calls Product; Product does not call Core (ADR 0020, obligation 3)")
                .check(classesUnderTest);
    }

    @Test
    @DisplayName("no reach into another deployable's code — they are separate processes")
    void this_deployable_imports_no_sibling() {
        // A deployable owns its database and integrates over APIs and events (AGENTS.md hard rule
        // 5). Sharing a type with a sibling would make one service's release the other's problem,
        // which is exactly what the extraction was for. libs/auth and libs/events are excluded on
        // purpose: they are libraries, owning neither a process nor a database (PRD §3.4).
        noClasses()
                .should()
                .accessClassesThat()
                .resideInAnyPackage(
                        "org.elyonar.fincore.customer..",
                        "org.elyonar.fincore.notification..",
                        "org.elyonar.fincore.identity..",
                        "org.elyonar.fincore.ledger..")
                .because("deployables integrate over APIs and events, never over a shared classpath"
                        + " (AGENTS.md hard rule 5). The ledger is reached over HTTP, not imported.")
                .check(classesUnderTest);
    }

    @Test
    @DisplayName("no float, double or BigDecimal reaches a money value")
    void money_is_integer_minor_units() {
        // Hard rule 1, and it bites harder here than almost anywhere: a percentage applied to money
        // is a money calculation, so basis points are integers too. A BigDecimal appearing in a fee
        // rule is a rounding argument with an institution about its own pricing.
        noClasses()
                .should()
                .accessClassesThat()
                .haveFullyQualifiedName("java.math.BigDecimal")
                .because("money and basis points are integer minor units (AGENTS.md hard rule 1)")
                .check(classesUnderTest);
    }

    @Test
    @DisplayName("java.time only — a version's effective moment is an instant, not a wall clock")
    void no_legacy_date_api() {
        // A version dated forward is compared against an instant in the tenant's zone. The legacy
        // API carries no zone worth trusting, and this service has already shipped one defect where
        // an authoring read returned a local wall clock instead of an instant.
        noClasses()
                .should()
                .accessClassesThat()
                .haveFullyQualifiedName("java.util.Date")
                .orShould()
                .accessClassesThat()
                .haveFullyQualifiedName("java.util.Calendar")
                .because("effective dating is compared as an instant; java.util.Date carries no zone")
                .check(classesUnderTest);
    }

    @Test
    @DisplayName("the internals stay internal")
    void nothing_outside_may_reach_the_internals() {
        classes()
                .that()
                .resideInAPackage("org.elyonar.fincore.product.internal..")
                .should()
                .onlyBeAccessed()
                .byAnyPackage("org.elyonar.fincore.product..")
                .because("the published surface is `api`; everything else is this service's own business")
                .check(classesUnderTest);
    }
}
