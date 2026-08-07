package org.elyonar.fincore.ledger.shared;

/**
 * What a verification report says about the run behind it.
 *
 * <p>An enum rather than string literals because these five values were written out in four places
 * — the report builder, the queue response, the OpenAPI schema and {@code api.md} — and a fifth
 * would have had to be added to all four by hand.
 *
 * <p>The distinction that matters is {@link #RUNNING} versus {@link #CLEAN}: a run with no
 * completion time has not found nothing, it has not finished looking. Reporting it clean would be a
 * claim the ledger has not earned, and it is the kind of claim an operator acts on.
 */
public enum InvariantStatus {

    /** The run finished and found no violations. */
    CLEAN,

    /** The run finished and found at least one violation. */
    VIOLATIONS,

    /** Accepted and not yet started. */
    QUEUED,

    /** Started and still looking. Not an answer. */
    RUNNING,

    /** No run has ever completed for this tenant. */
    NO_RUN_YET;

    public String value() {
        return name();
    }
}
