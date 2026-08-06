package org.elyonar.fincore.core.orchestration.api;

import java.time.ZoneId;
import java.util.UUID;

/**
 * A transfer as Core received it, with the tenant and principal already established from the
 * validated token rather than from the request body (ADR 0009).
 *
 * @param fingerprint a hash over the economic content only — amounts, accounts, currency,
 *     operation. Description and the initiating user are excluded, so a legitimate retry from a
 *     different pod or session replays rather than 409s: two requests that move the same money
 *     identically are the same request.
 */
public record TransferCommand(
        UUID tenantId,
        String idempotencyKey,
        String fingerprint,
        UUID customerId,
        UUID fromAccountId,
        UUID toAccountId,
        UUID feeAccountId,
        long amountMinor,
        String currency,
        String productCode,
        String channel,
        String description,
        String initiatedBy,
        String executedBy,
        ZoneId businessZone) {

    public TransferCommand {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be positive");
        }
        if (fromAccountId != null && fromAccountId.equals(toAccountId)) {
            // Caught here rather than by the Ledger: a wash transfer is a caller mistake, and the
            // error is more useful naming the request than naming an entry.
            throw new IllegalArgumentException("WASH_TRANSACTION");
        }
    }
}
