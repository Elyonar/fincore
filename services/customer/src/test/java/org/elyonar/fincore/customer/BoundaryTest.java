package org.elyonar.fincore.customer;

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
 * <p>This service carries the only personal data on the platform, which makes one of the rules
 * below load-bearing beyond architecture: it holds no ledger client and no money type, so a defect
 * here cannot move money, and the blast radius of a compromise is a disclosure rather than a
 * transfer.
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
                .importPackages("org.elyonar.fincore.customer");
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
        // two questions on every transaction — is this customer eligible, and which product governs
        // the account they hold. A call back into Core from here would be a cycle on the money path.
        noClasses()
                .should()
                .accessClassesThat()
                .resideInAnyPackage("org.elyonar.fincore.core..")
                .because("Core calls Customer; Customer does not call Core (ADR 0020, obligation 3)")
                .check(classesUnderTest);
    }

    @Test
    @DisplayName("no ledger client — this service owns people, never money")
    void this_deployable_cannot_reach_the_ledger() {
        // Only core/orchestration may call the ledger's write API (AGENTS.md hard rule 3), and this
        // service has no reason to read it either: balances, entries and history are the ledger's,
        // and a customer profile that also knew a balance would be a second place to be wrong about
        // one. Unlike Product, which verifies fee accounts, nothing here needs the ledger at all.
        noClasses()
                .should()
                .accessClassesThat()
                .resideInAnyPackage("org.elyonar.fincore.ledger..")
                .because("this service owns identity, never money (AGENTS.md hard rule 3)")
                .check(classesUnderTest);
    }

    @Test
    @DisplayName("no reach into another deployable's code — they are separate processes")
    void this_deployable_imports_no_sibling() {
        // A deployable owns its database and integrates over APIs and events (AGENTS.md hard rule
        // 5). libs/auth and libs/events are excluded on purpose: they are libraries, owning neither
        // a process nor a database (PRD §3.4).
        noClasses()
                .should()
                .accessClassesThat()
                .resideInAnyPackage(
                        "org.elyonar.fincore.product..",
                        "org.elyonar.fincore.notification..",
                        "org.elyonar.fincore.identity..")
                .because("deployables integrate over APIs and events, never over a shared classpath"
                        + " (AGENTS.md hard rule 5)")
                .check(classesUnderTest);
    }

    @Test
    @DisplayName("no money type reaches this service")
    void this_deployable_holds_no_money() {
        noClasses()
                .should()
                .accessClassesThat()
                .haveFullyQualifiedName("java.math.BigDecimal")
                .because("balances live in the ledger; a money type here would be a second place to"
                        + " be wrong about one (AGENTS.md hard rule 1)")
                .check(classesUnderTest);
    }

    @Test
    @DisplayName("java.time only — the legacy date API has no zone semantics worth trusting")
    void no_legacy_date_api() {
        noClasses()
                .should()
                .accessClassesThat()
                .haveFullyQualifiedName("java.util.Date")
                .orShould()
                .accessClassesThat()
                .haveFullyQualifiedName("java.util.Calendar")
                .because("a consent change and a tier change are instants, recorded append-only")
                .check(classesUnderTest);
    }

    @Test
    @DisplayName("the internals stay internal")
    void nothing_outside_may_reach_the_internals() {
        classes()
                .that()
                .resideInAPackage("org.elyonar.fincore.customer.internal..")
                .should()
                .onlyBeAccessed()
                .byAnyPackage("org.elyonar.fincore.customer..")
                .because("the published surface is `api`; the PII behind it is not anyone else's business")
                .check(classesUnderTest);
    }
}
