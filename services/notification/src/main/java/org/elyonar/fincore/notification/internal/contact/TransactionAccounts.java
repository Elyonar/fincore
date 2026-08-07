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
     */
    record Accounts(UUID from, UUID to) {}
}
