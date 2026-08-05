package org.elyonar.fincore.ledger.posting;

import java.util.List;
import java.util.UUID;

/**
 * A request to post one balanced transaction.
 *
 * <p>The ledger is told what to record, never why. {@code description} and {@code initiatedBy} are
 * opaque strings it stores and does not interpret — and, deliberately, does not fingerprint.
 */
public record PostTransactionCommand(
        UUID tenantId,
        String idempotencyKey,
        String initiatedBy,
        String executedBy,
        String description,
        List<EntryLine> entries,
        UUID consumeHoldId) {

    public PostTransactionCommand {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /** A posting that consumes no hold — the common case. */
    public PostTransactionCommand(
            UUID tenantId,
            String idempotencyKey,
            String initiatedBy,
            String executedBy,
            String description,
            List<EntryLine> entries) {
        this(tenantId, idempotencyKey, initiatedBy, executedBy, description, entries, null);
    }
}
