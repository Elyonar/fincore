package org.elyonar.fincore.ledger.api;

import org.elyonar.fincore.ledger.shared.ErrorCode;
import org.elyonar.fincore.ledger.shared.LedgerException;

/**
 * Money on the wire: a decimal string, always.
 *
 * <p>Entry amounts are capped below 2^53 and would survive as JSON numbers, but balances and
 * group sums are uncapped aggregates that can exceed it — and a JavaScript consumer parsing
 * {@code 9007199254740993} silently gets a different number, with no error anywhere. One rule
 * applied to every monetary field beats a rule that holds for requests and fails for responses.
 *
 * <p>Requests accept either form, because a caller sending a JSON number for a small amount is not
 * wrong; responses only ever emit strings.
 */
public final class Money {

    private Money() {}

    public static String toWire(long minor) {
        return Long.toString(minor);
    }

    /**
     * Parses a request amount, rejecting anything that is not an exact integer.
     *
     * <p>A decimal point is refused rather than rounded: {@code "100.50"} means the caller is
     * thinking in naira while the field is kobo, and guessing which they meant is how a ledger
     * ends up off by a factor of a hundred.
     */
    public static long fromWire(Object raw, String field) {
        if (raw == null) {
            throw new LedgerException(ErrorCode.LIMIT_EXCEEDED, field + " is required");
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            throw new LedgerException(ErrorCode.LIMIT_EXCEEDED, field + " is required");
        }
        if (raw instanceof Double || raw instanceof Float || text.contains(".") || text.contains("e") || text.contains("E")) {
            throw new LedgerException(
                    ErrorCode.LIMIT_EXCEEDED,
                    field + " must be an integer count of minor units (kobo), not a decimal");
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new LedgerException(ErrorCode.LIMIT_EXCEEDED, field + " is not a valid integer amount");
        }
    }
}
