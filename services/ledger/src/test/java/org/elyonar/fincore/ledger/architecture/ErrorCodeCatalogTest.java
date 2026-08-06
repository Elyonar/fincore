package org.elyonar.fincore.ledger.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.elyonar.fincore.ledger.hold.HoldReleaseOutcome;
import org.elyonar.fincore.ledger.shared.AccountStatus;
import org.elyonar.fincore.ledger.shared.AccountType;
import org.elyonar.fincore.ledger.shared.HoldStatus;
import org.elyonar.fincore.ledger.shared.TransactionStatus;
import org.elyonar.fincore.ledger.shared.VerificationScope;
import org.elyonar.fincore.ledger.tenant.TenantStatus;
import org.elyonar.fincore.ledger.shared.ErrorCode;
import org.elyonar.fincore.ledger.shared.ErrorReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The published error catalog must match the code that produces it.
 *
 * <p>An error contract is only useful if a caller can read it. A code that exists in the source
 * but not in {@code api.md} is undocumented — a caller meets it in production having never been
 * told it could happen, and a translator has nothing to translate. A code documented but deleted
 * is worse: someone writes a French message for a rejection that can no longer occur, and trusts
 * a table that is lying.
 *
 * <p>Prose asking contributors to keep the table current would be obeyed until the first hurried
 * afternoon. This test makes the build the thing that asks.
 *
 * <p>See {@code docs/conventions/error-contract.md}.
 */
@DisplayName("error catalog — every code and reason is documented, and every documented one exists")
class ErrorCodeCatalogTest {

    private static final Path API_DOC = Path.of("docs/api.md");

    private String doc() throws IOException {
        assertThat(API_DOC)
                .as("the ledger's API doc must be readable from the module directory")
                .exists();
        return Files.readString(API_DOC);
    }

    @Test
    @DisplayName("every ErrorCode appears in the catalog table")
    void everyCodeIsDocumented() throws IOException {
        String doc = doc();
        Set<String> undocumented =
                Arrays.stream(ErrorCode.values())
                        .map(ErrorCode::code)
                        .filter(code -> !doc.contains("`" + code + "`"))
                        .collect(Collectors.toCollection(TreeSet::new));

        assertThat(undocumented)
                .as(
                        "these error codes can reach a caller but are not in api.md's catalog, so"
                            + " nobody can branch on them or translate them: %s",
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

        assertThat(undocumented)
                .as("these error reasons are not documented in api.md: %s", undocumented)
                .isEmpty();
    }

    @Test
    @DisplayName("the catalog documents nothing that no longer exists")
    void noPhantomEntries() throws IOException {
        String doc = doc();
        Set<String> known = new TreeSet<>(reasonConstants());
        Arrays.stream(ErrorCode.values()).map(ErrorCode::code).forEach(known::add);

        // A doc about a ledger is full of SCREAMING_SNAKE tokens that are not errors: account
        // statuses, hold outcomes, entry directions. Those come from their own enums rather than
        // a hand-kept list, so adding a status can never be mistaken for an undocumented error
        // code — and no list here has to be edited when one is added.
        Stream.of(
                        AccountStatus.class,
                        AccountType.class,
                        HoldStatus.class,
                        TransactionStatus.class,
                        VerificationScope.class,
                        TenantStatus.class,
                        HoldReleaseOutcome.class)
                .flatMap(type -> Arrays.stream(type.getEnumConstants()))
                .map(Enum::name)
                .forEach(known::add);

        // What is left is genuinely not modelled in Java: HTTP and SQL vocabulary the prose uses.
        known.addAll(
                Set.of(
                        "NOT_FOUND", "BAD_REQUEST", "OUTCOME_UNKNOWN", "SET_LOCAL", "FOR_UPDATE",
                        "SKIP_LOCKED", "READ_COMMITTED", "ON_CONFLICT", "DO_NOTHING", "NOT_VALID"));

        Set<String> phantom =
                java.util.regex.Pattern.compile("`([A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+)`")
                        .matcher(doc)
                        .results()
                        .map(m -> m.group(1))
                        .filter(token -> !known.contains(token))
                        .collect(Collectors.toCollection(TreeSet::new));

        assertThat(phantom)
                .as(
                        "api.md documents these as error codes or reasons but no such constant"
                            + " exists — a caller would handle a rejection that can never"
                            + " happen: %s",
                        phantom)
                .isEmpty();
    }

    @Test
    @DisplayName("the catalog is not vacuously satisfied")
    void catalogIsNotEmpty() {
        // The same canary HardRulesTest carries: three assertions over an empty set all pass.
        assertThat(ErrorCode.values()).hasSizeGreaterThan(10);
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
