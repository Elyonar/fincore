package org.elyonar.fincore.ledger.shared;

/** How much history a verification run covered, matching {@code invariant_runs.scope}. */
public enum VerificationScope {
    /** Anchor plus the entries written since — the hourly check. */
    INCREMENTAL,

    /** Re-derived from every entry, so a wrong anchor cannot hide behind checks that trust it. */
    FULL
}
