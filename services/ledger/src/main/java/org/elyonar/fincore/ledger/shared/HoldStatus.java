package org.elyonar.fincore.ledger.shared;

/**
 * A hold's lifecycle state, matching the {@code holds.status} CHECK constraint.
 *
 * <p>{@link #ACTIVE} is the only non-terminal state; the rest never transition again, which the
 * schema enforces by trigger. {@link #isTerminal()} exists so that rule reads the same everywhere
 * rather than being re-expressed as a different string comparison in each caller.
 */
public enum HoldStatus {
    ACTIVE,
    RELEASED,
    EXPIRED,
    CONSUMED;

    public boolean isActive() {
        return this == ACTIVE;
    }

    public boolean isTerminal() {
        return this != ACTIVE;
    }

    public static HoldStatus of(String value) {
        return valueOf(value);
    }
}
