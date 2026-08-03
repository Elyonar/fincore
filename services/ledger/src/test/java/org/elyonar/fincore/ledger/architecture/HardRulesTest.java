package org.elyonar.fincore.ledger.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The platform's hard rules, enforced by the build rather than by review.
 *
 * <p>Every rule here corresponds to a numbered rule in {@code AGENTS.md} or a boundary in
 * {@code services/ledger/docs/architecture.md}. They are deliberately written before the
 * implementation exists: a rule encoded while it is still trivially true costs nothing, and a
 * rule added after the first violation is a negotiation.
 *
 * <p>Most of these pass vacuously today because the service is a skeleton. That is the point —
 * they fail on the commit that would have broken them, not at review time, and not in
 * production.
 */
@AnalyzeClasses(
        packages = "org.elyonar.fincore.ledger",
        importOptions = ImportOption.DoNotIncludeTests.class)
class HardRulesTest {

    private static final String LEDGER = "org.elyonar.fincore.ledger..";

    /**
     * Canary. Every other rule here is a {@code no...should...} rule, and such a rule passes
     * trivially when nothing was imported. ArchUnit reads bytecode through a bundled ASM, so a
     * future JDK bump can silently make every rule below vacuous — exactly what happened on the
     * first run against Java 25 with ArchUnit 1.3. This rule fails when the import set is empty,
     * so that failure surfaces as a red build instead of a green one that checks nothing.
     */
    @ArchTest
    static final ArchRule classes_are_actually_being_imported =
            classes()
                    .should()
                    .resideInAPackage(LEDGER)
                    .because(
                            "an empty import set would make every rule in this class pass while"
                                + " enforcing nothing");

    /**
     * AGENTS.md hard rule 1 — money amounts are integer minor units; no float or double ever
     * touches a money value. Binary floating point cannot represent 0.10 exactly, so a single
     * double in a posting path is a rounding error that compounds silently across millions of
     * entries and is invisible to every invariant, because the integers never disagree.
     */
    @ArchTest
    static final ArchRule no_floating_point_fields =
            noFields()
                    .should()
                    .haveRawType("double")
                    .orShould()
                    .haveRawType("float")
                    .orShould()
                    .haveRawType(Double.class.getName())
                    .orShould()
                    .haveRawType(Float.class.getName())
                    .because(
                            "AGENTS.md hard rule 1: money is integer minor units; floating point"
                                + " never touches a money value")
                    // The skeleton declares no fields yet. Deliberately vacuous today; the
                    // canary above proves the import set is non-empty, so this cannot hide a
                    // broken rule.
                    .allowEmptyShould(true);

    /** Same rule, applied to anything a method hands back. */
    @ArchTest
    static final ArchRule no_floating_point_return_types =
            noMethods()
                    .should()
                    .haveRawReturnType("double")
                    .orShould()
                    .haveRawReturnType("float")
                    .orShould()
                    .haveRawReturnType(Double.class.getName())
                    .orShould()
                    .haveRawReturnType(Float.class.getName())
                    .because(
                            "AGENTS.md hard rule 1: a money value must not be able to leave a"
                                + " method as floating point");

    /**
     * AGENTS.md hard rule 5 — database per service, no cross-service imports. Services talk via
     * APIs and events only. A monorepo makes this import a single keystroke, which is exactly why
     * it needs a test rather than a convention.
     */
    @ArchTest
    static final ArchRule no_cross_service_imports =
            noClasses()
                    .should()
                    .dependOnClassesThat(
                            resideInAPackage("org.elyonar.fincore..")
                                    .and(not(resideInAPackage(LEDGER))))
                    .because(
                            "AGENTS.md hard rule 5: a monorepo is not a monolith; services"
                                + " integrate over APIs and events, never over the classpath");

    /**
     * architecture.md — "synchronous outbound calls: NONE, EVER". The design says a reviewer
     * verifies this by reading {@code pom.xml}; a test verifies it on every push. The ledger is
     * command-driven: its behaviour must be fully determined by the API calls it receives, which
     * is what makes it replayable and auditable.
     */
    @ArchTest
    static final ArchRule no_synchronous_outbound_http =
            noClasses()
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.springframework.web.client..",
                            "org.springframework.web.reactive.function.client..",
                            "java.net.http..",
                            "okhttp3..",
                            "retrofit2..",
                            "feign..")
                    .because(
                            "architecture.md: the ledger makes no synchronous outbound calls,"
                                + " ever — an outbound call is a way for someone else's outage to"
                                + " become a failed posting");

    /**
     * architecture.md — "events consumed: NONE, EVER". The ledger subscribes to nothing. Guarding
     * the listener annotations keeps the boundary from eroding one convenience at a time.
     */
    @ArchTest
    static final ArchRule no_event_consumption =
            noClasses()
                    .should()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName("org.springframework.kafka.annotation.KafkaListener")
                    .orShould()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName("org.springframework.amqp.rabbit.annotation.RabbitListener")
                    .orShould()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName("org.springframework.jms.annotation.JmsListener")
                    .because(
                            "architecture.md: the ledger is command-driven and consumes no events"
                                + " — its behaviour is determined solely by the API calls it"
                                + " receives");

    /**
     * Value dating, business dates, and hold expiry are all timezone-sensitive (the tenant's
     * business timezone decides which day a 00:30 WAT deposit books to). {@code java.util.Date}
     * and {@code Calendar} have no zone semantics worth trusting; {@code java.time} does.
     */
    @ArchTest
    static final ArchRule no_legacy_date_api =
            noClasses()
                    .should()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName("java.util.Date")
                    .orShould()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName("java.util.Calendar")
                    .orShould()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName("java.sql.Date")
                    .because(
                            "posting-algorithm.md: business dates are tenant-timezone dates;"
                                + " java.time is the only API with defensible zone semantics");
}
