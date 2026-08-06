package org.elyonar.fincore.ledger.tenant;

/** A tenant's registry state, matching the {@code tenants.status} CHECK constraint. */
public enum TenantStatus {
    /** May transact. */
    ACTIVE,

    /** Registered but barred from transacting. Refused exactly like an unknown tenant. */
    SUSPENDED
}
