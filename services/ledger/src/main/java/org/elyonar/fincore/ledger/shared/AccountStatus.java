package org.elyonar.fincore.ledger.shared;

/**
 * An account's lifecycle state, matching the {@code accounts.status} CHECK constraint.
 *
 * <p>An enum rather than a string because these values are compared in service logic, returned in
 * API responses, and listed in OpenAPI schemas. Held as a string they were three copies that had to
 * be changed together; held here, the compiler finds every use.
 *
 * <p>There is no reopen: an account leaves {@link #OPEN} once.
 */
public enum AccountStatus {
    OPEN,
    CLOSED;

    public boolean isOpen() {
        return this == OPEN;
    }

    public static AccountStatus of(String value) {
        return valueOf(value);
    }
}
