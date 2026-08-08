package org.elyonar.fincore.notification.internal.send;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The send queue's health as metrics — the README's own known-limitations table said "no alarm in
 * the design has a measurement behind it", and this is that measurement.
 *
 * <p>Three gauges, scraped from {@code /actuator/prometheus}:
 *
 * <ul>
 *   <li>{@code notification.queue.depth} — messages owed and not yet sent
 *   <li>{@code notification.queue.oldest_pending_seconds} — the number a delivery-delay alert is
 *       written against
 *   <li>{@code notification.failed} — messages whose attempts are exhausted
 * </ul>
 *
 * <p>Reads run in worker scope ({@code SET LOCAL app.worker}) inside their own transaction — the
 * queue spans every tenant, and the worker's policy is the sanctioned way to see it, exactly as
 * the send worker itself does. Read from the table on every scrape, never counted in memory, so
 * a stalled worker still reports its backlog honestly. Never throws: a broken gauge reads zero
 * rather than taking the scrape endpoint down.
 */
@Component
public class SendMetrics {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public SendMetrics(
            @Qualifier("workerJdbcTemplate") JdbcTemplate jdbc,
            @Qualifier("workerTransactionManager") PlatformTransactionManager transactionManager,
            MeterRegistry registry) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);

        registry.gauge(
                "notification.queue.depth",
                this,
                self ->
                        self.scalar(
                                "SELECT count(*) FROM notification.notifications"
                                        + " WHERE state IN ('PENDING', 'SENDING')"));

        registry.gauge(
                "notification.queue.oldest_pending_seconds",
                this,
                self ->
                        self.scalar(
                                "SELECT COALESCE(FLOOR(EXTRACT(EPOCH FROM (now() - MIN(created_at))))::bigint, 0)"
                                        + " FROM notification.notifications WHERE state IN ('PENDING', 'SENDING')"));

        registry.gauge(
                "notification.failed",
                this,
                self ->
                        self.scalar(
                                "SELECT count(*) FROM notification.notifications WHERE state = 'FAILED'"));
    }

    /** One number, in worker scope, as {@code long}. Zero on any failure. */
    private long scalar(String sql) {
        try {
            Long value =
                    transaction.execute(
                            status -> {
                                jdbc.queryForObject("SELECT set_config('app.worker', 'on', true)", String.class);
                                return jdbc.queryForObject(sql, Long.class);
                            });
            return value == null ? 0 : value;
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
