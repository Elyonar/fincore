package org.elyonar.fincore.core.app;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The numbers Core's documented alarms are written against — measured, not merely documented.
 *
 * <p>{@code architecture.md} names operational alarms (oldest unpublished event age, sagas
 * awaiting resolution, open ops cases) that until now had no measurement behind them: the
 * scaffold's own rule is that "a threshold documented but unmeasured is not an alarm". Same shape
 * as the ledger's {@code OutboxMetrics}: every gauge reads straight from the table on scrape, so
 * a component that has stopped running still reports honestly — an in-memory counter maintained
 * by the relay goes quiet exactly when the relay does.
 *
 * <p>Each gauge reads under the role whose job it mirrors — the relay's for the outbox, the
 * worker's for sagas and cases — because those are the identities whose policies can see every
 * tenant's rows. Never throws: a broken gauge reads zero rather than taking the scrape endpoint
 * down, since that endpoint is also how liveness is observed.
 */
@Component
public class CoreMetrics {

    private final JdbcTemplate relayJdbc;
    private final JdbcTemplate workerJdbc;

    public CoreMetrics(
            @Qualifier("relayJdbcTemplate") JdbcTemplate relayJdbc,
            @Qualifier("workerJdbcTemplate") JdbcTemplate workerJdbc,
            MeterRegistry registry) {
        this.relayJdbc = relayJdbc;
        this.workerJdbc = workerJdbc;

        registry.gauge(
                "core.outbox.pending",
                this,
                self ->
                        self.scalar(
                                self.relayJdbc,
                                "SELECT count(*) FROM orchestration.outbox_events WHERE published_at IS NULL"));

        registry.gauge(
                "core.outbox.oldest_pending_seconds",
                this,
                self ->
                        self.scalar(
                                self.relayJdbc,
                                "SELECT COALESCE(FLOOR(EXTRACT(EPOCH FROM (now() - MIN(created_at))))::bigint, 0)"
                                        + " FROM orchestration.outbox_events WHERE published_at IS NULL"));

        registry.gauge(
                "core.sagas.outstanding",
                this,
                self ->
                        self.scalar(
                                self.workerJdbc,
                                "SELECT count(*) FROM orchestration.sagas"
                                        + " WHERE state IN ('RECEIVED', 'POSTING')"));

        registry.gauge(
                "core.ops_cases.open",
                this,
                self ->
                        self.scalar(
                                self.workerJdbc,
                                "SELECT count(*) FROM orchestration.ops_cases WHERE status = 'OPEN'"));
    }

    /** One number, as {@code long} — the no-floating-point rule is service-wide, not money-only. */
    private long scalar(JdbcTemplate jdbc, String sql) {
        try {
            Long value = jdbc.queryForObject(sql, Long.class);
            return value == null ? 0 : value;
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
