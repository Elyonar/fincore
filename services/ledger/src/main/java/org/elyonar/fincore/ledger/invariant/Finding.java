package org.elyonar.fincore.ledger.invariant;

/**
 * One thing an invariant check noticed.
 *
 * <p>{@link Kind#VIOLATION} means a bug: something the ledger guarantees is not true, and someone
 * should be woken up. {@link Kind#AUTHORIZED_EXPOSURE} means a known, explained consequence of a
 * deliberate bypass — a reversal driving a guarded account negative — which is tracked and aged
 * rather than alarmed on.
 *
 * <p>The distinction is the whole reason "zero violations in production, ever" is a usable target.
 * Without it, routine reversals would trip the alarm constantly, and an alarm that fires routinely
 * is one people learn to ignore precisely before the day it matters.
 */
public record Finding(Kind kind, String invariant, String subject, String detail) {

    public enum Kind {
        VIOLATION,
        AUTHORIZED_EXPOSURE
    }

    public static Finding violation(String invariant, String subject, String detail) {
        return new Finding(Kind.VIOLATION, invariant, subject, detail);
    }

    public static Finding exposure(String invariant, String subject, String detail) {
        return new Finding(Kind.AUTHORIZED_EXPOSURE, invariant, subject, detail);
    }
}
