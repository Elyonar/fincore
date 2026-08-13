package org.elyonar.fincore.core.orchestration.api;

import java.util.UUID;

/**
 * The question put to the Product module.
 *
 * @param tenantId from the validated token, never from a request body
 * @param productCode the product the account is held under, e.g. {@code AJO_DAILY}
 * @param operation what is being attempted
 * @param kycTier the customer's tier, which drives limits (Customer's answer, passed through)
 * @param channel where the request came from, e.g. {@code TELLER}
 * @param amountMinor the principal, in integer minor units. Never a decimal, never a float.
 * @param currency ISO 4217
 */
public record ProductRequest(
        UUID tenantId,
        String productCode,
        Operation operation,
        String kycTier,
        String channel,
        long amountMinor,
        String currency) {

    /** The operations v1 prices and limits. Rails and standing orders are not here yet. */
    public enum Operation {
        DEPOSIT,
        WITHDRAWAL,
        TRANSFER
    }

    public ProductRequest {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be positive");
        }
    }
}
