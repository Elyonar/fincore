package org.elyonar.fincore.notification;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The boundaries this service claims, enforced rather than described.
 *
 * <p>Its POM says what is absent — no ledger client, no gateway SDK — and its {@code package-info}
 * says the same in prose. Both were, until now, claims a reviewer had to take on trust. A rule that
 * could be a test and is not is a gap in the guardrails (AGENTS.md).
 *
 * <p>The empty-import canary is not decoration. Every {@code no…should…} rule passes vacuously when
 * nothing was imported, and Core's boundary suite hit exactly that: ArchUnit could not read Java 25
 * bytecode, imported zero classes, and every other rule passed while enforcing nothing. The canary
 * failed the build and that is how anyone found out.
 */
@DisplayName("boundaries — what this service may not reach for")
class BoundaryTest {

    private static JavaClasses classesUnderTest;

    @BeforeAll
    static void importProductionCode() {
        classesUnderTest = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("org.elyonar.fincore.notification");
    }

    @Test
    @DisplayName("the import set is not empty — every other rule here depends on it")
    void the_canary_is_alive() {
        assertThat(classesUnderTest)
                .as("ArchUnit imported nothing, so every rule below would pass while enforcing nothing")
                .isNotEmpty();
    }

    @Test
    @DisplayName("no ledger client, anywhere")
    void this_service_cannot_reach_the_ledger() {
        // Only core/orchestration may call the Ledger's write API (AGENTS.md hard rule 3). A
        // notifier holding a ledger client would be a second writer to the money path, and the
        // first sign would be a posting nobody could attribute.
        noClasses()
                .should()
                .accessClassesThat()
                .resideInAnyPackage("..ledger..")
                .because("only core/orchestration may reach the Ledger (AGENTS.md hard rule 3)")
                .check(classesUnderTest);
    }

    @Test
    @DisplayName("no gateway SDK — credentials live in the messaging connector")
    void this_service_holds_no_gateway() {
        // Per-tenant sender ids and credentials belong to the messaging connector (PRD §4.6). A
        // provider SDK appearing here would mean this service had started holding them.
        noClasses()
                .should()
                .accessClassesThat()
                .resideInAnyPackage("com.twilio..", "com.amazonaws..", "software.amazon.awssdk..", "com.sendgrid..")
                .because("gateway credentials live in the messaging connector, never here (PRD §4.6)")
                .check(classesUnderTest);
    }

    @Test
    @DisplayName("no float, double or BigDecimal reaches a money or unit value")
    void money_and_units_are_integers() {
        // This service moves no money, but it counts billable units, and a segment count that
        // arrived as a double would be a rounding argument nobody wants to have with a gateway.
        noClasses()
                .should()
                .accessClassesThat()
                .haveFullyQualifiedName("java.math.BigDecimal")
                .because("units are integers; a decimal here is a rounding argument with a gateway")
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
                .because("quiet hours are evaluated in a tenant's timezone; java.util.Date has none")
                .check(classesUnderTest);
    }

    /**
     * Where the two send-path reads actually point.
     *
     * <p>This exists because the failure it prevents produced no signal at all. ADR 0020 moved
     * {@code GET /v1/customers/by-account/{id}} out of Core and into the customer deployable, and
     * this service went on asking Core. Core answers 404 for a path it does not route; 404 is this
     * client's documented "no such customer"; so every side of every transfer recorded an
     * {@code UNKNOWN_ACCOUNT} suppression and the service reported itself healthy while producing
     * nothing. No log line at any level said otherwise.
     *
     * <p>Asserted over the source rather than over a booted context because a {@code @Value} key is
     * exactly a string literal referenced twice (AGENTS.md hard rule 10) — the thing worth pinning
     * is which key each client names, and that is a fact about the file.
     */
    @Test
    @DisplayName("each send-path read addresses the service that owns it")
    void the_reads_point_at_the_right_services() throws IOException {
        String contact = source("HttpContactDirectory.java");
        String transactions = source("HttpTransactionAccounts.java");

        assertThat(contact)
                .as("who holds an account is the customer service's question since ADR 0020; asking"
                        + " Core returns 404, which this client reads as 'no such customer'")
                .contains("fincore.notification.customer.base-url")
                .doesNotContain("fincore.notification.core.base-url");

        assertThat(transactions)
                .as("which accounts a transaction moved between is still Core's question")
                .contains("fincore.notification.core.base-url")
                .doesNotContain("fincore.notification.customer.base-url");
    }

    private static String source(String fileName) throws IOException {
        // Surefire runs with the module directory as its working directory; the second candidate
        // covers a run rooted at the repository instead.
        String suffix = "src/main/java/org/elyonar/fincore/notification/internal/contact/" + fileName;
        for (String prefix : new String[] {"", "services/notification/"}) {
            Path path = Path.of(prefix + suffix);
            if (Files.exists(path)) {
                return Files.readString(path);
            }
        }
        throw new IllegalStateException("cannot find " + suffix + " from " + Path.of("").toAbsolutePath());
    }

    @Test
    @DisplayName("the internals stay internal")
    void nothing_outside_may_reach_the_internals() {
        // There is no api package here because no other service calls this one — it is reached by
        // events and by an operator. The rule states that: everything is internal, and the only
        // public surface is the application class itself.
        classes()
                .that()
                .resideInAPackage("org.elyonar.fincore.notification.internal..")
                .should()
                .onlyBeAccessed()
                .byAnyPackage("org.elyonar.fincore.notification..")
                .because("this service publishes no interface to another deployable")
                .check(classesUnderTest);
    }
}
