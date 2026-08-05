package org.elyonar.fincore.ledger.posting;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.stream.Collectors;

/**
 * SHA-256 over the canonical <em>economic</em> content of a posting request.
 *
 * <p>The fingerprint is what makes idempotency-key reuse loud instead of silently wrong: the same
 * key with a different payload is rejected rather than answered with the original result.
 *
 * <p>Two rules decide what goes in, and both exist to stop the fingerprint contradicting the retry
 * rule:
 *
 * <ul>
 *   <li><strong>{@code description} and {@code initiatedBy} are excluded.</strong> Two requests
 *       that move the same money identically are the same request; prose is not economics. A
 *       legitimate retry from a different pod or user session must replay, not 409.
 *   <li><strong>The hash covers the request as received.</strong> An omitted {@code valueDate}
 *       canonicalises as absent, never as the resolved default. Hashing the resolved value would
 *       give a mandated same-key retry that crossed the tenant's midnight a different fingerprint
 *       — a 409, which is terminal for that key, pushing the caller to mint a new key for an
 *       operation that may already have committed. That is the double post both rules exist to
 *       prevent.
 * </ul>
 *
 * <p>Entries are sorted before hashing so that reordering the same lines yields the same
 * fingerprint: the order entries arrive in is not an economic fact.
 */
public final class RequestFingerprint {

    private RequestFingerprint() {}

    private static final Comparator<EntryLine> CANONICAL_ORDER =
            Comparator.comparing((EntryLine e) -> e.accountId().toString())
                    .thenComparing(e -> e.direction().name())
                    .thenComparingLong(EntryLine::amountMinor)
                    .thenComparing(EntryLine::currency)
                    .thenComparing(e -> e.valueDate() == null ? "" : e.valueDate().toString());

    public static String of(PostTransactionCommand command) {
        String canonical =
                command.tenantId()
                        + "|"
                        + (command.consumeHoldId() == null ? "" : command.consumeHoldId())
                        + "|"
                        + command.entries().stream()
                                .sorted(CANONICAL_ORDER)
                                .map(RequestFingerprint::canonicalise)
                                .collect(Collectors.joining(";"));
        return sha256(canonical);
    }

    private static String canonicalise(EntryLine entry) {
        return entry.accountId()
                + ","
                + entry.direction()
                + ","
                + entry.amountMinor()
                + ","
                + entry.currency()
                // Absent stays absent. See the class comment: resolving here would break retries.
                + ","
                + (entry.valueDate() == null ? "" : entry.valueDate());
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JRE", e);
        }
    }
}
