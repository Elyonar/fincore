package org.elyonar.fincore.ledger.posting;

import java.util.UUID;

/**
 * A request to undo a posted transaction.
 *
 * <p>The reversal carries its own idempotency key rather than reusing the original's: it is a new
 * money-moving operation, and a caller retrying the reversal must be able to do so safely without
 * colliding with the transaction it is undoing.
 */
public record ReverseTransactionCommand(
        UUID tenantId, UUID originalTransactionId, String idempotencyKey, String initiatedBy, String executedBy) {}
