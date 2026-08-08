package org.elyonar.fincore.core.orchestration.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.elyonar.fincore.core.customer.api.CustomerErrorCode;
import org.elyonar.fincore.core.organization.api.OrganizationErrorCode;
import org.elyonar.fincore.core.product.api.ProductDecision;
import org.elyonar.fincore.core.product.api.ProductErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Core's published error catalog must match the code that produces it.
 *
 * <p>The Ledger's twin of this test found four miscatalogued entries on its first run. Core needs
 * it more, not less: its catalog listed fifteen codes while the source threw a different set, and
 * the HTTP layer emitted a sixteenth kind of thing entirely — {@code IllegalArgumentException}'s
 * English message, in the {@code code} field.
 *
 * <p>See {@code docs/conventions/error-contract.md}.
 */
@DisplayName("error catalog — every Core code and reason is documented, and every documented one exists")
class ErrorCodeCatalogTest {

    private static final Path API_DOC = Path.of("../docs/api.md");

    private String doc() throws IOException {
        assertThat(API_DOC).as("Core's API doc must be readable from the module directory").exists();
        return Files.readString(API_DOC);
    }

    @Test
    @DisplayName("every ErrorCode appears in the catalog table")
    void everyCodeIsDocumented() throws IOException {
        String doc = doc();
        Set<String> undocumented =
                allCoreCodes().stream()
                        .filter(code -> !doc.contains("`" + code + "`"))
                        .collect(Collectors.toCollection(TreeSet::new));

        assertThat(undocumented)
                .as(
                        "these codes can reach a caller but are not in api.md's catalog, so nobody"
                                + " can branch on them or translate them: %s",
                        undocumented)
                .isEmpty();
    }

    @Test
    @DisplayName("every ErrorReason appears in the reasons table")
    void everyReasonIsDocumented() throws IOException {
        String doc = doc();
        Set<String> undocumented =
                reasonConstants().stream()
                        .filter(reason -> !doc.contains("`" + reason + "`"))
                        .collect(Collectors.toCollection(TreeSet::new));

        assertThat(undocumented).as("undocumented reasons: %s", undocumented).isEmpty();
    }

    @Test
    @DisplayName("the catalog documents nothing that no longer exists")
    void noPhantomEntries() throws IOException {
        Set<String> known = new TreeSet<>(reasonConstants());
        known.addAll(allCoreCodes());

        // Non-error vocabulary comes from the enums that define it, never a hand-kept list, so
        // adding a saga state or a product refusal can never read as an undocumented error code.
        Stream.of(ProductDecision.Refusal.class, LedgerPosting.Direction.class, CashCommand.Operation.class)
                .flatMap(t -> Arrays.stream(t.getEnumConstants()))
                .map(Enum::name)
                .forEach(known::add);

        // Saga states and HTTP vocabulary the prose uses. Not modelled as Java enums today; when
        // they are, they belong in the stream above rather than here.
        known.addAll(
                Set.of(
                        "PENDING_RESOLUTION", "COMPLETED", "POSTING", "FAILED", "RESERVED",
                        "RELEASED", "DEFINITE_FAILURE", "NOT_FOUND", "BAD_REQUEST", "SET_LOCAL",
                        "FOR_UPDATE", "SKIP_LOCKED", "NOT_VALID", "ON_CONFLICT", "DO_NOTHING"));

        Set<String> phantom =
                Pattern.compile("`([A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+)`")
                        .matcher(doc())
                        .results()
                        .map(m -> m.group(1))
                        .filter(token -> !known.contains(token))
                        .collect(Collectors.toCollection(TreeSet::new));

        assertThat(phantom)
                .as(
                        "api.md documents these as codes or reasons but no such constant exists —"
                                + " a caller would handle a rejection that can never happen: %s",
                        phantom)
                .isEmpty();
    }

    @Test
    @DisplayName("every product refusal is a Core error code")
    void productRefusalsAreCodes() {
        // TransferService maps these with ErrorCode.valueOf(refusal.name()), which throws at
        // runtime if the two drift. Catching that here rather than in production is the point.
        assertThat(Arrays.stream(ProductDecision.Refusal.values()).map(Enum::name))
                .allSatisfy(
                        name ->
                                assertThat(Arrays.stream(ErrorCode.values()).map(Enum::name))
                                        .as("product refusal %s has no matching ErrorCode", name)
                                        .contains(name));
    }

    /**
     * Every code Core can return, across all four modules.
     *
     * <p>Core is one deployable holding several modules, and each owns its own catalog — a shared
     * enum would make every module compile against Orchestration (ADR 0006). The published API is
     * still one surface, so the doc is one table and this test unions the four.
     */
    private static Set<String> allCoreCodes() {
        return Stream.of(
                        ErrorCode.values(),
                        CustomerErrorCode.values(),
                        ProductErrorCode.values(),
                        OrganizationErrorCode.values())
                .flatMap(Arrays::stream)
                .map(Enum::name)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    @DisplayName("the catalog is not vacuously satisfied")
    void notVacuous() {
        assertThat(allCoreCodes()).hasSizeGreaterThan(20);
        assertThat(reasonConstants()).hasSizeGreaterThan(10);
    }

    private static Set<String> reasonConstants() {
        return Arrays.stream(ErrorReason.class.getDeclaredFields())
                .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == String.class)
                .map(
                        f -> {
                            try {
                                return (String) f.get(null);
                            } catch (IllegalAccessException e) {
                                throw new AssertionError("ErrorReason constants must be public", e);
                            }
                        })
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
