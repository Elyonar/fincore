package org.elyonar.fincore.core.app;

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
 * The module boundaries, enforced by the build rather than by review.
 *
 * <p>These rules carry more weight in Core than the equivalent ones do in the ledger. Core's
 * modules share a classpath, so the compiler will happily let orchestration import product's
 * persistence — the api/internal split is a convention until something checks it, and this is that
 * something (CHANGELOG 1.1.0 records the trade deliberately).
 *
 * <p>The other half of the boundary is the database: one role per schema, with no grants on its
 * neighbours, so a cross-module query fails at runtime too. Two mechanisms, because a boundary
 * defended one way is a boundary that erodes.
 */
@AnalyzeClasses(
        packages = "org.elyonar.fincore.core",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

    private static final String CUSTOMER = "org.elyonar.fincore.core.customer..";
    private static final String PRODUCT = "org.elyonar.fincore.core.product..";
    private static final String ORGANIZATION = "org.elyonar.fincore.core.organization..";
    private static final String LENDING = "org.elyonar.fincore.core.lending..";
    private static final String ORCHESTRATION = "org.elyonar.fincore.core.orchestration..";

    /**
     * Canary. Every rule below is a {@code no...should...} rule, and such a rule passes trivially
     * when nothing was imported — which is exactly what a JDK or ArchUnit bump can silently cause.
     * This one fails on an empty import set, so that shows up as a red build rather than a green
     * one enforcing nothing. The ledger learned this the hard way on Java 25.
     */
    @ArchTest
    static final ArchRule classes_are_actually_being_imported =
            classes()
                    .should()
                    .resideInAPackage("org.elyonar.fincore.core..")
                    .because(
                            "an empty import set would make every rule in this class pass while"
                                + " enforcing nothing");

    /**
     * ADR 0006 — a module is reached through its published interface, never its internals. This is
     * what keeps extraction a move rather than a rewrite: everything a neighbour depends on is
     * already the thing that would become a client.
     */
    @ArchTest
    static final ArchRule internals_are_private_to_their_module =
            noClasses()
                    .that()
                    .resideOutsideOfPackage(CUSTOMER)
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("org.elyonar.fincore.core.customer.internal..")
                    .because("customer's internals belong to customer; use its api package")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule product_internals_are_private =
            noClasses()
                    .that()
                    .resideOutsideOfPackage(PRODUCT)
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("org.elyonar.fincore.core.product.internal..")
                    .because("product's internals belong to product; use its api package")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule orchestration_internals_are_private =
            noClasses()
                    .that()
                    .resideOutsideOfPackage(ORCHESTRATION)
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("org.elyonar.fincore.core.orchestration.internal..")
                    .because("orchestration's internals belong to orchestration")
                    .allowEmptyShould(true);

    /**
     * Dependency direction. Orchestration asks the other two; neither asks it, and they do not ask
     * each other. A cycle here would mean extraction has to move two modules at once.
     */
    /**
     * ADR 0013 amended the order: lending sits above orchestration, consuming its published api.
     * Everything else still may not ask it.
     */
    @ArchTest
    static final ArchRule only_lending_and_app_depend_on_orchestration =
            noClasses()
                    .that()
                    .resideOutsideOfPackages(ORCHESTRATION, LENDING, "org.elyonar.fincore.core.app..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage(ORCHESTRATION)
                    .because("lending is the one module above orchestration (ADR 0013)")
                    .allowEmptyShould(true);

    /** And never the reverse: a cycle would make both unextractable. */
    @ArchTest
    static final ArchRule orchestration_does_not_know_lending =
            noClasses()
                    .that()
                    .resideInAPackage(ORCHESTRATION)
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage(LENDING)
                    .because("the dependency points up only (ADR 0013)")
                    .allowEmptyShould(true);

    /** Lending consumes orchestration's published api, never its internals. */
    @ArchTest
    static final ArchRule lending_uses_only_the_published_orchestration_surface =
            noClasses()
                    .that()
                    .resideInAPackage(LENDING)
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("org.elyonar.fincore.core.orchestration.internal..")
                    .because("the boundary is the api package — the same one HTTP callers get")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule lending_internals_are_private =
            noClasses()
                    .that()
                    .resideOutsideOfPackage(LENDING)
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("org.elyonar.fincore.core.lending.internal..")
                    .because("lending's internals belong to lending; use its api package")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule customer_and_product_do_not_know_each_other =
            noClasses()
                    .that()
                    .resideInAPackage(CUSTOMER)
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage(PRODUCT)
                    .because("a customer's identity and a product's pricing are unrelated concerns")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule organization_internals_are_private =
            noClasses()
                    .that()
                    .resideOutsideOfPackage(ORGANIZATION)
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("org.elyonar.fincore.core.organization.internal..")
                    .because("organization's internals belong to organization; use its api package")
                    .allowEmptyShould(true);

    /**
     * Organization is reference structure, not a consumer: it answers "does this unit exist" and
     * asks its neighbours nothing. Orchestration may depend on it (a till names a branch); the
     * reverse would make the operational tree load-bearing for money movement, which ADR 0012
     * deliberately avoids.
     */
    @ArchTest
    static final ArchRule organization_depends_on_no_other_module =
            noClasses()
                    .that()
                    .resideInAPackage(ORGANIZATION)
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(CUSTOMER, PRODUCT, ORCHESTRATION, LENDING)
                    .because("organization is a leaf: neighbours ask it, it asks nobody")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule customer_and_product_do_not_know_organization =
            noClasses()
                    .that()
                    .resideInAnyPackage(CUSTOMER, PRODUCT)
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage(ORGANIZATION)
                    .because(
                            "only orchestration consults the organizational tree today; a new"
                                + " consumer is a design amendment, not an import")
                    .allowEmptyShould(true);

    /**
     * AGENTS.md hard rule 3 — only Orchestration calls the ledger's write API. Inside one
     * deployable that rule is satisfied by *any* Core code unless it names the module, so this is
     * where it is actually enforced: no HTTP client anywhere but orchestration.
     */
    @ArchTest
    static final ArchRule only_orchestration_may_hold_an_http_client =
            noClasses()
                    .that()
                    .resideOutsideOfPackage(ORCHESTRATION)
                    // The one exemption, named rather than packaged, so a second client anywhere
                    // else still fails the build. Hard rule 3 protects the *Ledger* boundary:
                    // orchestration is the only module that may post to it, because a second
                    // caller is a second definition of what a balanced entry means. ADR 0018 then
                    // required Core's administration surface to call the identity service — a
                    // different upstream, holding no money and enforcing no invariant of ours —
                    // and this rule, written before that existed, forbade every client rather than
                    // every Ledger client. Widening it to a package would let the next one in
                    // silently; exempting one class means the next one is argued for.
                    .and()
                    .doNotHaveFullyQualifiedName(
                            "org.elyonar.fincore.core.app.admin.IdentityDirectory")
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
                            "AGENTS.md hard rule 3: core-orchestration is the only module that may"
                                + " call the Ledger, so it is the only one that may hold a client."
                                + " IdentityDirectory is exempt by name: it calls the identity"
                                + " service (ADR 0018), never the Ledger.")
                    .allowEmptyShould(true);

    /** AGENTS.md hard rule 1 — money is integer minor units, everywhere, always. */
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
                    .because("AGENTS.md hard rule 1: floating point never touches a money value")
                    .allowEmptyShould(true);

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
                    .because("AGENTS.md hard rule 1: money must not leave a method as a float")
                    .allowEmptyShould(true);

    /** Business dates are tenant-timezone dates; only java.time has defensible zone semantics. */
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
                    .because("business dates are tenant-timezone dates")
                    .allowEmptyShould(true);

    /** AGENTS.md hard rule 5 — no imports across a deployable boundary. */
    @ArchTest
    static final ArchRule no_cross_deployable_imports =
            noClasses()
                    .should()
                    .dependOnClassesThat(
                            resideInAPackage("org.elyonar.fincore..")
                                    .and(not(resideInAPackage("org.elyonar.fincore.core..")))
                                    // Shared libraries, not deployables. The distinction is the
                                    // one PRD §3.4 draws: a deployable owns a database and a
                                    // process, a library owns neither and is linked in.
                                    .and(not(resideInAPackage("org.elyonar.fincore.auth..")))
                                    .and(not(resideInAPackage("org.elyonar.fincore.events.."))))
                    .because(
                            "AGENTS.md hard rule 5: deployables integrate over APIs and events,"
                                + " never over the classpath. libs/auth and libs/events are"
                                + " shared libraries, not other deployables.")
                    .allowEmptyShould(true);
}
