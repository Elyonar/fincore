package org.elyonar.fincore.ledger.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.annotations.media.Schema;
import java.lang.reflect.AnnotatedElement;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.elyonar.fincore.ledger.account.AccountController;
import org.elyonar.fincore.ledger.hold.HoldController;
import org.elyonar.fincore.ledger.hold.HoldReleaseOutcome;
import org.elyonar.fincore.ledger.posting.TransactionController;
import org.elyonar.fincore.ledger.shared.HoldStatus;
import org.elyonar.fincore.ledger.shared.TransactionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OpenAPI {@code allowableValues} must match the enum they describe.
 *
 * <p>Java annotations take compile-time constants only, so {@code @Schema} cannot be handed
 * {@code HoldStatus.values()} — the literal list is unavoidable. What is avoidable is the list
 * being <em>wrong</em>. Adding a hold status and forgetting the annotation publishes a document
 * that tells integrators a legal value does not exist, and the compiler has nothing to object to.
 *
 * <p>So the duplication stays and the build checks it. That is the general answer wherever a
 * literal cannot be eliminated: not "be careful", but "fail if it drifts".
 */
@DisplayName("OpenAPI enums — documented values match the enum, in both directions")
class SchemaAllowableValuesTest {

    /** Every documented value set that mirrors an enum, and the enum it must mirror. */
    private static Map<String, Class<? extends Enum<?>>> mirroredSets() {
        Map<String, Class<? extends Enum<?>>> m = new LinkedHashMap<>();
        m.put("status", HoldStatus.class); // account + hold filters
        m.put("outcome", HoldReleaseOutcome.class);
        return m;
    }

    @Test
    @DisplayName("hold status filters list exactly the HoldStatus constants")
    void holdStatusMatches() {
        Set<String> expected = names(HoldStatus.class);
        assertThat(documented(HoldController.class, expected))
                .as(
                        "a @Schema listing hold statuses is out of step with HoldStatus — the"
                            + " published API would deny a value the ledger accepts")
                .isNotEmpty()
                .allSatisfy(values -> assertThat(values).isEqualTo(expected));
        assertThat(documented(AccountController.class, expected))
                .allSatisfy(values -> assertThat(values).isEqualTo(expected));
    }

    @Test
    @DisplayName("transaction status filters list exactly the TransactionStatus constants")
    void transactionStatusMatches() {
        Set<String> expected = names(TransactionStatus.class);
        assertThat(documented(TransactionController.class, expected))
                .as("a @Schema listing transaction statuses is out of step with TransactionStatus")
                .isNotEmpty()
                .allSatisfy(values -> assertThat(values).isEqualTo(expected));
    }

    @Test
    @DisplayName("hold release outcomes list exactly the HoldReleaseOutcome constants")
    void releaseOutcomeMatches() {
        Set<String> expected = names(HoldReleaseOutcome.class);
        assertThat(documented(HoldController.class, expected))
                .as("the release response documents outcomes HoldReleaseOutcome does not define")
                .isNotEmpty()
                .allSatisfy(values -> assertThat(values).isEqualTo(expected));
    }

    @Test
    @DisplayName("the check is not vacuous")
    void notVacuous() {
        // Every assertion above is over a filtered collection; an empty one passes all of them.
        assertThat(mirroredSets()).isNotEmpty();
        assertThat(names(HoldStatus.class)).hasSize(4);
        assertThat(annotations(HoldController.class)).isNotEmpty();
    }

    /**
     * The {@code allowableValues} sets on a controller that overlap the expected enum — a schema
     * describing something else entirely (a statement's FINAL/INTERIM) is not this enum's problem.
     */
    private static java.util.List<Set<String>> documented(Class<?> controller, Set<String> expected) {
        return annotations(controller).stream()
                .map(schema -> Arrays.stream(schema.allowableValues()).collect(Collectors.toCollection(TreeSet::new)))
                .filter(values -> !java.util.Collections.disjoint(values, expected))
                .map(values -> (Set<String>) values)
                .toList();
    }

    /**
     * Every {@code @Schema} carrying allowable values anywhere on a controller: its own methods and
     * parameters, and those of the response records nested inside it — which is where most of them
     * live, and where a scan of methods alone finds nothing at all.
     */
    private static java.util.List<Schema> annotations(Class<?> controller) {
        return Stream.concat(Stream.of(controller), Arrays.stream(controller.getDeclaredClasses()))
                .flatMap(SchemaAllowableValuesTest::annotatedElements)
                .map(element -> element.getAnnotation(Schema.class))
                .filter(schema -> schema != null && schema.allowableValues().length > 0)
                .toList();
    }

    private static Stream<AnnotatedElement> annotatedElements(Class<?> type) {
        Stream<AnnotatedElement> methods = Arrays.stream(type.getDeclaredMethods()).map(m -> m);
        Stream<AnnotatedElement> parameters =
                Stream.concat(
                        Arrays.stream(type.getDeclaredMethods()).flatMap(m -> Arrays.stream(m.getParameters())),
                        Arrays.stream(type.getDeclaredConstructors())
                                .flatMap(c -> Arrays.stream(c.getParameters())));
        Stream<AnnotatedElement> fields = Arrays.stream(type.getDeclaredFields()).map(f -> f);
        Stream<AnnotatedElement> components =
                type.isRecord()
                        ? Arrays.stream(type.getRecordComponents()).map(rc -> rc)
                        : Stream.empty();
        return Stream.of(methods, parameters, fields, components).flatMap(s -> s);
    }

    private static Set<String> names(Class<? extends Enum<?>> type) {
        return Arrays.stream(type.getEnumConstants())
                .map(Enum::name)
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
