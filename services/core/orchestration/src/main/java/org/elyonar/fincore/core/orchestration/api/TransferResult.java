package org.elyonar.fincore.core.orchestration.api;

import java.util.UUID;

/**
 * What the caller is told about a transfer.
 *
 * <p>Carries the fee that was applied and the product version that decided it — a fee is disclosed
 * with the thing it was charged for, not discoverable afterwards.
 *
 * <p>It also names the accounts the money moved between, and that is for a caller who was not the
 * one who asked. A channel knows the accounts because it supplied them; a <em>consumer</em> of
 * {@code transfer.completed} knows only a saga id, because event payloads carry identifiers and no
 * PII (ADR 0008) and are read back through the publisher's API rather than reconstructed. Without
 * these two fields there is no path from "a transfer happened" to "whose account moved", which is
 * the first question any notifier, statement or compliance consumer asks.
 *
 * @param fromAccountId debited. Null on a reversal, which targets a transaction rather than a pair
 *     of accounts
 * @param toAccountId credited. Null on a reversal, for the same reason
 */
public record TransferResult(
        UUID transactionId,
        String state,
        long amountMinor,
        long feeMinor,
        String currency,
        int productVersion,
        UUID ledgerTransactionId,
        UUID fromAccountId,
        UUID toAccountId) {}
