package org.elyonar.fincore.core.orchestration.api;

import java.util.List;
import java.util.UUID;

/**
 * A balanced transaction as Core asks the Ledger to record it.
 *
 * <p>Principal and fee travel as entries of <em>one</em> transaction rather than two calls: the
 * Ledger accepts 2..100 entries balanced per currency, so atomicity is free, there is one
 * idempotency key to reason about, and reversal semantics come out right by construction — undoing
 * the transfer undoes its fee.
 *
 * @param idempotencyKey derived from the saga and step; never random, never time-derived
 * @param initiatedBy the human principal or system job that asked
 * @param entries at least two, balanced per currency
 */
public record LedgerPosting(String idempotencyKey, String initiatedBy, String description, List<Entry> entries) {

    public LedgerPosting {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw CoreException.of(ErrorCode.COMMAND_INVALID, ErrorReason.IDEMPOTENCY_KEY_REQUIRED)
                    .with(DetailKey.FIELD, "idempotencyKey")
                    .message("a posting without an idempotency key is not retryable");
        }
        if (entries == null || entries.size() < 2) {
            throw CoreException.of(ErrorCode.COMMAND_INVALID, ErrorReason.TOO_FEW_ENTRIES)
                    .with(DetailKey.LIMIT, 2)
                    .with(DetailKey.SUPPLIED, entries == null ? 0 : entries.size())
                    .message("a transaction needs at least two entries");
        }
        entries = List.copyOf(entries);
    }

    /**
     * One side of one leg.
     *
     * @param amountMinor integer minor units. Serialized as a decimal string, because balances and
     *     sums elsewhere in the platform exceed exact JSON number range and one rule everywhere
     *     means no consumer ever silently loses precision.
     */
    public record Entry(UUID accountId, Direction direction, long amountMinor, String currency) {
        public Entry {
            if (amountMinor <= 0) {
                throw CoreException.of(ErrorCode.AMOUNT_INVALID, ErrorReason.AMOUNT_SIGN_ON_ENTRY)
                        .with(DetailKey.SUPPLIED, amountMinor)
                        .message("entry amounts are positive; direction carries the sign");
            }
        }
    }

    public enum Direction {
        DEBIT,
        CREDIT
    }

    /** Total per side, used to check the posting balances before it is sent. */
    public long totalFor(Direction direction) {
        return entries.stream().filter(e -> e.direction() == direction).mapToLong(Entry::amountMinor).sum();
    }

    /**
     * Whether debits equal credits.
     *
     * <p>Checked here as well as by the Ledger. Not because the Ledger might miss it — it will
     * reject an unbalanced transaction — but because an unbalanced posting is a Core bug, and
     * finding it before the call keeps a defect of ours out of the Ledger's error catalog.
     */
    public boolean balances() {
        return totalFor(Direction.DEBIT) == totalFor(Direction.CREDIT);
    }
}
