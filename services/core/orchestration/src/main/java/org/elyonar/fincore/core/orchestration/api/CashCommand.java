package org.elyonar.fincore.core.orchestration.api;

import java.time.ZoneId;
import java.util.UUID;

/**
 * Cash over the counter, as Core received it.
 *
 * <p>Tenant and principal come from the validated token, never the body.
 */
public record CashCommand(
        UUID tenantId,
        Operation operation,
        String idempotencyKey,
        String fingerprint,
        UUID customerId,
        UUID customerAccountId,
        UUID tillId,
        UUID feeAccountId,
        long amountMinor,
        String currency,
        String productCode,
        String channel,
        String description,
        String initiatedBy,
        String executedBy,
        ZoneId businessZone) {

    public enum Operation {
        DEPOSIT,
        WITHDRAWAL
    }

    public CashCommand {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be positive");
        }
    }
}
