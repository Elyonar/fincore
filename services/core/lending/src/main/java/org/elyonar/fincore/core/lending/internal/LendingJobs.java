package org.elyonar.fincore.core.lending.internal;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lending's background truths, run daily as the worker (cross-tenant, like the saga worker):
 *
 * <ul>
 *   <li><strong>Accrual</strong> — every active loan's interest advances ACT/365 on outstanding
 *       principal, integer arithmetic, idempotent by construction (the days advanced are the days
 *       between {@code accrual_through} and today; a rerun advances zero).
 *   <li><strong>Delinquency</strong> — the oldest unsettled installment decides days past due;
 *       transitions are append-only events, one per loan per day, and a rerun is a no-op by the
 *       unique key. Recovery (settling up) transitions back to CURRENT the same way.
 *   <li><strong>Convergence</strong> — applications stuck DISBURSING and repayments left PENDING
 *       whose sagas have since resolved get their lending-side effects applied, so a lost caller
 *       is never the difference between a loan existing and not.
 * </ul>
 *
 * <p>Batch claims use {@code FOR UPDATE SKIP LOCKED}, so several instances share the work without
 * sharing rows. Each gauge in {@code CoreMetrics}' family reads these tables directly — a stalled
 * job is caught by its lag, not by silence.
 */
@Component
@ConditionalOnProperty(name = "fincore.core.lending.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class LendingJobs {

    private static final Logger log = LoggerFactory.getLogger(LendingJobs.class);
    private static final MathContext MC = new MathContext(20, RoundingMode.DOWN);
    private static final BigDecimal DENOMINATOR = BigDecimal.valueOf(10_000L * 365L);

    private final JdbcTemplate workerJdbc;
    private final LoanService loans;
    private final LoanRecords records;

    public LendingJobs(
            @Qualifier("workerJdbcTemplate") JdbcTemplate workerJdbcTemplate,
            LoanService loans,
            LoanRecords records) {
        this.workerJdbc = workerJdbcTemplate;
        this.loans = loans;
        this.records = records;
    }

    @Scheduled(
            initialDelayString = "${fincore.core.lending.jobs.interval-ms:3600000}",
            fixedDelayString = "${fincore.core.lending.jobs.interval-ms:3600000}")
    public void tick() {
        try {
            accrue();
            classify();
            converge();
        } catch (RuntimeException e) {
            log.error("lending job pass failed; the next tick retries in full", e);
        }
    }

    /** ACT/365 on outstanding principal, truncating — never accruing a kobo nobody earned. */
    public void accrue() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<AccrualRow> due =
                workerJdbc.query(
                        """
                        SELECT id, principal_outstanding_minor, interest_rate_bp, accrual_through
                          FROM lending.loans
                         WHERE state = 'ACTIVE' AND accrual_through < ?
                         ORDER BY accrual_through
                         FOR UPDATE SKIP LOCKED
                        """,
                        (rs, i) ->
                                new AccrualRow(
                                        rs.getObject("id", UUID.class),
                                        rs.getLong("principal_outstanding_minor"),
                                        rs.getInt("interest_rate_bp"),
                                        rs.getObject("accrual_through", LocalDate.class)),
                        today);
        for (AccrualRow row : due) {
            long days = ChronoUnit.DAYS.between(row.through(), today);
            long accrued =
                    BigDecimal.valueOf(row.outstanding())
                            .multiply(BigDecimal.valueOf(row.rateBp()), MC)
                            .multiply(BigDecimal.valueOf(days), MC)
                            .divide(DENOMINATOR, MC)
                            .setScale(0, RoundingMode.DOWN)
                            .longValueExact();
            workerJdbc.update(
                    "UPDATE lending.loans SET accrued_interest_minor = accrued_interest_minor + ?,"
                            + " accrual_through = ? WHERE id = ?",
                    accrued, today, row.id());
        }
        if (!due.isEmpty()) {
            log.info("accrual advanced {} loan(s) to {}", due.size(), today);
        }
    }

    /** Buckets from the schedule; transitions as events, one per loan per day, idempotent. */
    public void classify() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<DelinquencyRow> rows =
                workerJdbc.query(
                        """
                        SELECT l.id, l.tenant_id, l.current_bucket,
                               COALESCE((SELECT MIN(s.due_date) FROM lending.loan_schedule s
                                          WHERE s.loan_id = l.id AND s.settled_at IS NULL
                                            AND s.due_date < ?), NULL) AS oldest_due
                          FROM lending.loans l
                         WHERE l.state = 'ACTIVE'
                        """,
                        (rs, i) ->
                                new DelinquencyRow(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("tenant_id", UUID.class),
                                        rs.getString("current_bucket"),
                                        rs.getObject("oldest_due", LocalDate.class)),
                        today);
        for (DelinquencyRow row : rows) {
            long dpd = row.oldestDue() == null ? 0 : ChronoUnit.DAYS.between(row.oldestDue(), today);
            String bucket = bucketFor(dpd);
            if (bucket.equals(row.bucket())) {
                continue;
            }
            int recorded =
                    workerJdbc.update(
                            """
                            INSERT INTO lending.delinquency_events
                                (tenant_id, loan_id, from_bucket, to_bucket, days_past_due, as_of)
                            VALUES (?,?,?,?,?,?)
                            ON CONFLICT ON CONSTRAINT one_transition_per_day DO NOTHING
                            """,
                            row.tenantId(), row.id(), row.bucket(), bucket, (int) dpd, today);
            if (recorded == 1) {
                workerJdbc.update(
                        "UPDATE lending.loans SET current_bucket = ? WHERE id = ?", bucket, row.id());
                workerJdbc.update(
                        """
                        INSERT INTO lending.outbox_events (tenant_id, event_type, aggregate_id, payload)
                        VALUES (?,?,?,?::jsonb)
                        """,
                        row.tenantId(),
                        "CURRENT".equals(bucket) ? "loan.recovered" : "loan.delinquent",
                        row.id().toString(),
                        "{\"loanId\":\"%s\",\"bucket\":\"%s\",\"daysPastDue\":%d}"
                                .formatted(row.id(), bucket, dpd));
            }
        }
    }

    public static String bucketFor(long dpd) {
        if (dpd <= 0) {
            return "CURRENT";
        }
        if (dpd <= 30) {
            return "DPD_1_30";
        }
        if (dpd <= 60) {
            return "DPD_31_60";
        }
        if (dpd <= 90) {
            return "DPD_61_90";
        }
        return "DPD_90_PLUS";
    }

    /**
     * Applications stuck DISBURSING and repayments left PENDING whose sagas resolved while the
     * caller was gone: apply the lending-side effect now. Runs through the same service paths a
     * caller retry would take, so there is exactly one convergence logic.
     */
    public void converge() {
        List<Stuck> disbursing =
                workerJdbc.query(
                        "SELECT id, tenant_id FROM lending.loan_applications WHERE state = 'DISBURSING'"
                                + " AND disbursement_saga_id IS NOT NULL",
                        (rs, i) -> new Stuck(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class)));
        for (Stuck app : disbursing) {
            try {
                loans.disburse(app.tenantId(), app.id(), null, null, "system:lending-jobs", "core");
            } catch (LoanService.PendingDisbursement stillPending) {
                // Genuinely undetermined; the saga worker owns it. Next tick asks again.
            } catch (RuntimeException e) {
                log.error("convergence failed for application {}", app.id(), e);
            }
        }

        List<PendingRepayment> pending =
                workerJdbc.query(
                        """
                        SELECT r.id, r.tenant_id, r.loan_id, r.amount_minor
                          FROM lending.repayments r
                         WHERE r.state = 'PENDING' AND r.repayment_saga_id IS NOT NULL
                        """,
                        (rs, i) ->
                                new PendingRepayment(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("tenant_id", UUID.class),
                                        rs.getObject("loan_id", UUID.class),
                                        rs.getLong("amount_minor")));
        for (PendingRepayment r : pending) {
            try {
                var loan = records.loan(r.tenantId(), r.loanId());
                if (loan != null) {
                    loans.allocate(r.tenantId(), r.id(), loan, r.amountMinor());
                }
            } catch (RuntimeException e) {
                log.error("allocation catch-up failed for repayment {}", r.id(), e);
            }
        }
    }

    private record AccrualRow(UUID id, long outstanding, int rateBp, LocalDate through) {}

    private record DelinquencyRow(UUID id, UUID tenantId, String bucket, LocalDate oldestDue) {}

    private record Stuck(UUID id, UUID tenantId) {}

    private record PendingRepayment(UUID id, UUID tenantId, UUID loanId, long amountMinor) {}
}
