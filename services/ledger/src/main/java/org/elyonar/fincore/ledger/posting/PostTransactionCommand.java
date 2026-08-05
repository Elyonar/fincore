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
        UUID consumeHoldId,
        UUID relatesToTransactionId,
        String backdateReason,
        boolean closedAccountSweep) {

    public PostTransactionCommand {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /** A posting that consumes a hold but compensates nothing. */
    public PostTransactionCommand(
            UUID tenantId,
            String idempotencyKey,
            String initiatedBy,
            String executedBy,
            String description,
            List<EntryLine> entries,
            UUID consumeHoldId) {
        this(tenantId, idempotencyKey, initiatedBy, executedBy, description, entries, consumeHoldId, null, null, false);
    }

    /** A compensation, linked to the transaction it partially corrects. */
    public PostTransactionCommand(
            UUID tenantId,
            String idempotencyKey,
            String initiatedBy,
            String executedBy,
            String description,
            List<EntryLine> entries,
            UUID consumeHoldId,
            UUID relatesToTransactionId) {
        this(
                tenantId,
                idempotencyKey,
                initiatedBy,
                executedBy,
                description,
                entries,
                consumeHoldId,
                relatesToTransactionId,
                null,
                false);
    }

    /** A posting with full value-dating control but no closed-account sweep. */
    public PostTransactionCommand(
            UUID tenantId,
            String idempotencyKey,
            String initiatedBy,
            String executedBy,
            String description,
            List<EntryLine> entries,
            UUID consumeHoldId,
            UUID relatesToTransactionId,
            String backdateReason) {
        this(
                tenantId,
                idempotencyKey,
                initiatedBy,
                executedBy,
                description,
                entries,
                consumeHoldId,
                relatesToTransactionId,
                backdateReason,
                false);
    }

    /** A posting that consumes no hold — the common case. */
    public PostTransactionCommand(
            UUID tenantId,
            String idempotencyKey,
            String initiatedBy,
            String executedBy,
            String description,
            List<EntryLine> entries) {
        this(tenantId, idempotencyKey, initiatedBy, executedBy, description, entries, null, null, null, false);
    }
}
