package org.elyonar.fincore.ledger.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/**
 * Exposes the outbox's health as metrics, not just log lines.
 *
 * <p>{@code architecture.md} requires an alert when the oldest unpublished event is more than sixty
 * seconds old, because a dead relay is otherwise completely silent — postings keep succeeding,
 * nothing errors, and the first sign of trouble is a reconciliation failure at month end. That
 * requirement was implemented as {@code log.error}, which alerts whoever happens to be grepping.
 *
 * <p>Two gauges, scraped from {@code /actuator/prometheus}:
 *
 * <ul>
 *   <li>{@code ledger.outbox.pending} — how many events are waiting
 *   <li>{@code ledger.outbox.oldest_pending_seconds} — the number the alert is actually written
 *       against
 * </ul>
 *
 * <p>Both reads delegate to the <em>injected</em> {@link OutboxRelay} bean — that reference is the
 * Spring proxy, so its {@code @Transactional} relay-scope opt-out genuinely applies. Handing
 * Micrometer a raw {@code this} and querying directly was the previous shape, and it failed
 * silently: the gauge called an unproxied method, {@code set_config('app.relay', ...)} died with
 * its own statement, RLS matched no rows without a tenant context, and both gauges read zero
 * forever — the staleness alarm reporting healthy is exactly the failure it exists to catch.
 */
@Component
public class OutboxMetrics {

    public OutboxMetrics(OutboxRelay relay, MeterRegistry registry) {
        registry.gauge("ledger.outbox.pending", relay, r -> zeroOnFailure(r::pendingCount));

        registry.gauge(
                "ledger.outbox.oldest_pending_seconds",
                relay,
                r -> zeroOnFailure(() -> r.oldestPendingAgeSeconds().orElse(0L)));
    }

    /**
     * Never throws: a broken gauge must read zero rather than take the scrape endpoint down with
     * it, since that endpoint is also how liveness is observed.
     *
     * <p>Returns {@code long}, not {@code double}, even though Micrometer's gauge takes a
     * {@code ToDoubleFunction}: the no-floating-point rule is enforced across the whole service
     * rather than only on money, and an absolute rule needs no judgement at review time. The
     * widening happens at the lambda boundary above, so no method here declares a floating-point
     * return.
     */
    private static long zeroOnFailure(LongSupplier read) {
        try {
            return read.getAsLong();
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
