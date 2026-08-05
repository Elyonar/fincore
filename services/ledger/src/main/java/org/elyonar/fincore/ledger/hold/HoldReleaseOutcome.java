package org.elyonar.fincore.ledger.hold;

/**
 * What a release attempt actually did.
 *
 * <p>Deliberately not a boolean. A caller whose reservation expired before it decided to capture
 * must <em>learn</em> that, rather than receive a success-shaped no-op while the funds it believes
 * are reserved get spent by someone else. Collapsing these into "ok" is how double-spends happen
 * upstream, in a system that reported everything fine.
 */
public enum HoldReleaseOutcome {

    /** This call performed the release; the reservation is now returned to available balance. */
    RELEASED_NOW,

    /** Someone already released it. Idempotent, and the caller's intent is satisfied. */
    ALREADY_RELEASED,

    /** It expired first. The funds are available again, but not because of this caller. */
    ALREADY_EXPIRED,

    /** It was captured by a posting. The money moved — releasing it is not possible. */
    ALREADY_CONSUMED
}
