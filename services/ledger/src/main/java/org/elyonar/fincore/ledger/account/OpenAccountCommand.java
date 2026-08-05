package org.elyonar.fincore.ledger.account;

import java.util.UUID;

/** A request to open an account. {@code customerRef} is opaque: the ledger stores no PII. */
public record OpenAccountCommand(
        UUID tenantId,
        String idempotencyKey,
        String type,
        String currency,
        String customerRef,
        String groupRef,
        boolean allowNegative) {}
