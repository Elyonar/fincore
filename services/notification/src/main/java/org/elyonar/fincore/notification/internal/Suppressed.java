package org.elyonar.fincore.notification.internal;

/**
 * Why a message was not sent.
 *
 * <p>This enum is the service's defining guarantee made concrete (design D-17): every consumed
 * event ends as a message or as a row carrying one of these. "Why did my customer not get an SMS?"
 * is answered by a query, never by reading logs and inferring.
 *
 * <p>A closed set rather than free text, for the reason hard rule 9 gives: an explanation that
 * lives only in an English sentence is one a caller has to parse, and a platform serving Lagos and
 * Abidjan cannot write that sentence for either. The facts a message would interpolate go in
 * {@code detail}.
 */
public enum Suppressed {

    /** The event was older than {@code max-event-age} — almost always a replayed topic (D-5). */
    STALE_EVENT,

    /** From a restore generation this consumer has been told to distrust (D-6). */
    EPOCH_FENCED,

    /** No live customer holds the account the event names. */
    UNKNOWN_ACCOUNT,

    /**
     * The event named a tenant this service has never been provisioned for.
     *
     * <p>Recorded rather than dropped, and deliberately not an exception: a misrouted topic or a
     * half-finished provisioning would otherwise stall the consumer on every poll, and the queue
     * behind it would stop for tenants that are perfectly real.
     */
    UNKNOWN_TENANT,

    /** The customer has no address for any channel the tenant configured for this category. */
    NO_ADDRESS,

    /** The customer said no, for this category and channel (D-20). */
    OPTED_OUT,

    /** Inside the tenant's quiet hours, for a category that respects them (D-9, D-10). */
    QUIET_HOURS,

    /** No published template for this key, channel and locale. */
    NO_TEMPLATE,

    /** The template wanted a variable the event did not carry (D-12). */
    MISSING_VARIABLE,

    /** Rendered, and over the channel's unit cap (D-14). */
    TOO_MANY_UNITS,

    /** The tenant has configured no channels for this category at all. */
    NO_POLICY,

    /** Every attempt was used and the outcome was still not definite (D-19). */
    ATTEMPTS_EXHAUSTED
}
