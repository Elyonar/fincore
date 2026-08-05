package org.elyonar.fincore.ledger.hold;

import java.time.Instant;
import java.util.UUID;

/**
 * A request to reserve funds on an account.
 *
 * <p>{@code expiresAt} is mandatory. An unbounded hold is a permanent lien on a customer's money,
 * and the ledger refuses to be able to express one.
 */
public record PlaceHoldCommand(
        UUID tenantId, String idempotencyKey, UUID accountId, long amountMinor, String currency, Instant expiresAt) {}
