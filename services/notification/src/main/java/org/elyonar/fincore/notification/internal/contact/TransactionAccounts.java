package org.elyonar.fincore.notification.internal.contact;

import java.util.Optional;
import java.util.UUID;

/**
 * Which accounts a transaction moved money between.
 *
 * <p>Read back from Core rather than taken off the bus, which is ADR 0008's rule stated as code:
 * *"consumers are state-based — react to an event by fetching current state through the publisher's
 * read API"*. The event carries a saga id and an amount; it deliberately does not carry the
 * accounts, because a payload that grew a field for every consumer would become database-shaped
 * one addition at a time.
 *
 * <p>Both sides matter. An intra-tenant transfer owes an alert to the customer debited *and* the
 * customer credited — one business moment, two recipients — which is why this returns a pair rather
 * than "the account".
 */
public interface TransactionAccounts {

    /** @return empty when Core has no such transaction for this tenant */
    Optional<Accounts> forTransaction(UUID tenantId, UUID transactionId);

    /**
     * @param from debited. Null on a reversal, which targets a transaction rather than a pair of
     *     accounts.
     * @param to credited. Null on a reversal, for the same reason.
     * @param facts what a message may say about this transaction — see {@link Facts}.
     */
    record Accounts(UUID from, UUID to, Facts facts) {}

    /**
     * The presentation detail a template renders from.
     *
     * <p>Read from the same call as the accounts, off the same response, because it is the same
     * question asked once. It is deliberately *not* on the event: ADR 0008 keeps the payload a
     * published contract rather than a database row, and a customer's transaction detail on the
     * broker is customer data sitting in every consumer's retention window.
     *
     * <p>What is missing and why, so the next person does not go looking: there is no narration —
     * {@code sagas} does not persist the description a teller types — and no balance, which needs
     * a third call to the ledger per message and is the most sensitive field a stolen phone could
     * show. Both are additions, neither is an oversight.
     */
    record Facts(
            String reference,
            String type,
            long amountMinor,
            long feeMinor,
            String currency,
            String channel,
            java.time.OffsetDateTime occurredAt) {}
}
