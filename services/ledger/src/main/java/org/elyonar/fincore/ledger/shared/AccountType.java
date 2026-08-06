package org.elyonar.fincore.ledger.shared;

/** The account kinds the schema permits, matching the {@code accounts.type} CHECK constraint. */
public enum AccountType {
    CUSTOMER,
    INTERNAL,
    FEE,

    /** The only permitted counterparty for a closed-account residue sweep. */
    SUSPENSE,

    AGENT_FLOAT,
    SETTLEMENT_MIRROR;

    public static AccountType of(String value) {
        return valueOf(value);
    }
}
