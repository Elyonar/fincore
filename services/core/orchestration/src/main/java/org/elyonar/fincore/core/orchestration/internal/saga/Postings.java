package org.elyonar.fincore.core.orchestration.internal.saga;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.elyonar.fincore.core.orchestration.api.LedgerPosting;

/**
 * The one place a saga's entries are built.
 *
 * <p>It used to be two: the service composed the posting on the first attempt, and the worker
 * rebuilt it from the saga row on every retry. Two constructions of the same thing is a drift
 * waiting to happen, and it happened — the fee moved from being folded into the principal to being
 * its own entry, the service was changed, and the retry path was not. A retried withdrawal would
 * have rebuilt the older shape, and the Ledger would have refused it as
 * {@code IDEMPOTENCY_KEY_REUSED}: the same key presented with different entries. That turns a
 * recoverable unknown — the one situation the retry exists for — into a permanent one.
 *
 * <p>So both call this. A change to a posting's shape now happens once or not at all.
 *
 * <p><strong>Why the shapes differ.</strong> A withdrawal, a transfer and a charge debit the
 * customer twice — the principal and the fee — so the fee reads as its own line on their statement.
 * A deposit cannot: crediting the principal and debiting the fee puts one account on both sides of
 * one transaction, which the Ledger refuses as a wash. Its fee is netted into the credit instead,
 * which balances and reaches income correctly but leaves the charge invisible on the customer's
 * statement — a gap that closes only when the charge gets a posting of its own.
 */
final class Postings {

    private Postings() {}

    /** A saga's entries, identical whether this is the first attempt or the fiftieth. */
    static List<LedgerPosting.Entry> entriesFor(
            String type,
            UUID fromAccountId,
            UUID toAccountId,
            UUID feeAccountId,
            long amountMinor,
            long feeMinor,
            String currency) {

        boolean deposit = "DEPOSIT".equals(type);
        var entries = new ArrayList<LedgerPosting.Entry>();

        if (deposit) {
            // Cash in: the till hands over the notes, the customer is credited what is left after
            // the fee, and the fee reaches income directly. See the class note for why it cannot be
            // a second entry against the customer.
            entries.add(new LedgerPosting.Entry(fromAccountId, LedgerPosting.Direction.DEBIT, amountMinor, currency));
            entries.add(
                    new LedgerPosting.Entry(
                            toAccountId, LedgerPosting.Direction.CREDIT, amountMinor - feeMinor, currency));
            if (feeMinor > 0) {
                entries.add(
                        new LedgerPosting.Entry(
                                requireFeeAccount(feeAccountId, feeMinor),
                                LedgerPosting.Direction.CREDIT,
                                feeMinor,
                                currency));
            }
            return entries;
        }

        // Withdrawals and transfers: the payer is debited the principal, the counterparty credited
        // it, and the fee is a second debit against the payer so it reads as its own line.
        entries.add(new LedgerPosting.Entry(fromAccountId, LedgerPosting.Direction.DEBIT, amountMinor, currency));
        entries.add(new LedgerPosting.Entry(toAccountId, LedgerPosting.Direction.CREDIT, amountMinor, currency));
        if (feeMinor > 0) {
            entries.add(new LedgerPosting.Entry(fromAccountId, LedgerPosting.Direction.DEBIT, feeMinor, currency));
            entries.add(
                    new LedgerPosting.Entry(
                            requireFeeAccount(feeAccountId, feeMinor),
                            LedgerPosting.Direction.CREDIT,
                            feeMinor,
                            currency));
        }
        return entries;
    }

    /**
     * A fee with nowhere to go is unbuildable, and no amount of retrying changes that.
     *
     * <p>Raised as its own type so the worker can tell "try again later" from "this will never
     * work" — otherwise it loops forever on a saga it cannot possibly complete, which is what a
     * real deployment did until this was added.
     */
    private static UUID requireFeeAccount(UUID feeAccountId, long feeMinor) {
        if (feeAccountId == null) {
            throw new SagaRecords.Unretryable("saga carries a fee of " + feeMinor + " but names no fee account");
        }
        return feeAccountId;
    }
}
