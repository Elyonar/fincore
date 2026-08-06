package org.elyonar.fincore.core.orchestration.api;

import java.util.UUID;

/**
 * What the caller is told about a transfer.
 *
 * <p>Carries the fee that was applied and the product version that decided it — a fee is disclosed
 * with the thing it was charged for, not discoverable afterwards.
 */
public record TransferResult(
        UUID transactionId,
        String state,
        long amountMinor,
        long feeMinor,
        String currency,
        int productVersion,
        UUID ledgerTransactionId) {}
