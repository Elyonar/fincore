package org.elyonar.fincore.notification.internal.template;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything a template is allowed to say, assembled in one place.
 *
 * <p>Until this existed a template could see the event payload and nothing else — four fields:
 * a transaction id, an amount in minor units, a fee, and a currency. No account, no reference, no
 * date, no direction. The only message writable against that was a sentence with no facts in it
 * ("your account has been credited"), which is what every template on the platform said, and which
 * a customer cannot reconcile, cannot query, and cannot use to spot a fraud.
 *
 * <p>The variables are a **platform vocabulary**, not a tenant's. A tenant writes the sentence; the
 * set of things a sentence may refer to is the same everywhere, because it is bounded by what the
 * services actually know. That is the same split ADR 0017 made for permissions and roles.
 *
 * <p>Nothing here is read from the event bus beyond the moment's identity. The facts come from
 * Core's read API on a call this service already makes — ADR 0008's rule that a consumer reacts to
 * an event by fetching current state, and the reason a customer's transaction detail is not sitting
 * in every consumer's Kafka retention window.
 */
public final class RenderContext {

    private RenderContext() {}

    /** Which way the money went, from the recipient's point of view — not the ledger's. */
    public static final String CREDIT = "CR";
    public static final String DEBIT = "DR";

    /**
     * @param institution the tenant's display name, so a message says who it is from — an
     *     unattributed SMS about money is indistinguishable from a scam
     * @param accountNumber the recipient's account, masked by the {@code mask} filter at use
     * @param direction {@link #CREDIT} or {@link #DEBIT}, from this recipient's side
     */
    public static Map<String, String> of(
            String institution,
            String accountNumber,
            String direction,
            String reference,
            long amountMinor,
            long feeMinor,
            String currency,
            String channel,
            OffsetDateTime occurredAt) {

        Map<String, String> context = new LinkedHashMap<>();
        put(context, "institution", institution);
        put(context, "accountNumber", accountNumber);
        put(context, "drCr", direction);
        put(context, "reference", reference);
        put(context, "currency", currency);
        put(context, "channel", channel);
        // Minor units, unformatted. The `money` filter is what turns 250000 into 2,500.00 — kept
        // separate so a template can still do arithmetic-free things with the raw value, and so the
        // formatting rule lives in one place rather than in a hundred tenants' copies of it.
        put(context, "amountMinor", Long.toString(amountMinor));
        put(context, "feeMinor", Long.toString(feeMinor));
        put(context, "totalMinor", Long.toString(amountMinor + feeMinor));
        if (occurredAt != null) {
            put(context, "occurredAt", occurredAt.toString());
        }
        return context;
    }

    /**
     * Absent rather than empty.
     *
     * <p>A null that lands in the map as "" renders as a blank in the middle of a sentence and
     * sends. Left absent, the renderer reports it as a missing variable and the intake suppresses
     * with {@code MISSING_VARIABLE} — a message nobody received and a reason somebody can fix,
     * which is the better of the two failures.
     */
    private static void put(Map<String, String> context, String name, String value) {
        if (value != null && !value.isBlank()) {
            context.put(name, value);
        }
    }
}
