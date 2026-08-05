package org.elyonar.fincore.ledger.posting;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One side of a posting: an amount, in a direction, against an account.
 *
 * <p>{@code amountMinor} is an integer count of minor units — kobo, not naira. No floating point
 * ever touches a money value (ArchUnit enforces this), because binary floating point cannot
 * represent 0.10 exactly and the resulting drift is invisible to every invariant: the integers
 * never disagree with themselves.
 *
 * <p>{@code valueDate} is nullable and means "not supplied". It is resolved to the tenant's
 * business date during validation, never before the fingerprint is taken.
 */
public record EntryLine(UUID accountId, Direction direction, long amountMinor, String currency, LocalDate valueDate) {

    public EntryLine {
        if (accountId == null) throw new IllegalArgumentException("accountId is required");
        if (direction == null) throw new IllegalArgumentException("direction is required");
        if (currency == null) throw new IllegalArgumentException("currency is required");
    }

    /** Signed contribution to a balance under the credit-positive convention. */
    public long signedMinor() {
        return direction == Direction.CREDIT ? amountMinor : -amountMinor;
    }

    public enum Direction {
        DEBIT,
        CREDIT
    }
}
