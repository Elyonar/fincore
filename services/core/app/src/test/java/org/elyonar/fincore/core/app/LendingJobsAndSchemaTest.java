package org.elyonar.fincore.core.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.elyonar.fincore.core.lending.internal.LendingJobs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The daily truths and the schema's own guarantees: ACT/365 accrual to the kobo, delinquency
 * buckets at their edges, idempotent reruns, and evidence tables that refuse edits.
 */
@SpringBootTest
class LendingJobsAndSchemaTest {

    @Autowired private TenantRegistry tenantRegistry;
    @Autowired private LendingJobs jobs;
    @Autowired @Qualifier("workerJdbcTemplate") private JdbcTemplate workerDb;
    @Autowired @Qualifier("lendingJdbcTemplate") private JdbcTemplate lendingDb;
    @Autowired @Qualifier("lendingTransactionManager") private PlatformTransactionManager lendingTx;
    @Autowired @Qualifier("productJdbcTemplate") private JdbcTemplate productDb;
    @Autowired @Qualifier("productTransactionManager") private PlatformTransactionManager productTx;

    private UUID tenantId;
    private UUID loanId;
    private UUID applicationId;

    @DynamicPropertySource
    static void quiet(DynamicPropertyRegistry registry) {
        registry.add("fincore.core.worker.interval-ms", () -> "3600000");
        registry.add("fincore.core.outbox.relay.interval-ms", () -> "3600000");
        registry.add("fincore.core.lending.jobs.interval-ms", () -> "3600000");
        registry.add("fincore.test.context", () -> "lending-jobs");
    }

    @BeforeEach
    void seedLoan() {
        tenantId = UUID.randomUUID();
        tenantRegistry.register(tenantId, "test tenant", "test");
        applicationId = UUID.randomUUID();
        loanId = UUID.randomUUID();

        new TransactionTemplate(lendingTx)
                .executeWithoutResult(
                        s -> {
                            lendingDb.queryForObject(
                                    "SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
                            lendingDb.update(
                                    """
                                    INSERT INTO lending.loan_applications
                                        (id, tenant_id, customer_id, product_code, amount_minor, term_months,
                                         currency, state, approvals_required, applied_by, officer,
                                         offer_total_interest_minor, offer_total_cost_minor, offer_effective_rate_bp)
                                    VALUES (?,?,?, 'AJO_LOAN', 1000000, 12, 'NGN', 'ACTIVE', 0, 'user:o', 'user:o',
                                            240000, 1240000, 2400)
                                    """,
                                    applicationId, tenantId, UUID.randomUUID());
                            lendingDb.update(
                                    """
                                    INSERT INTO lending.loans
                                        (id, tenant_id, application_id, customer_id, product_code, product_version,
                                         principal_minor, principal_outstanding_minor, interest_rate_bp,
                                         schedule_kind, currency, accrual_through, disbursed_on, penalty_through,
                                         funding_account_id, customer_account_id, officer)
                                    VALUES (?,?,?,?, 'AJO_LOAN', 1, 1000000, 1000000, 3650, 'FLAT', 'NGN',
                                            ?, ?, ?, ?, ?, 'user:o')
                                    """,
                                    loanId, tenantId, applicationId, UUID.randomUUID(),
                                    LocalDate.now(ZoneOffset.UTC).minusDays(10),
                                    LocalDate.now(ZoneOffset.UTC).minusDays(40),
                                    LocalDate.now(ZoneOffset.UTC).minusDays(10),
                                    UUID.randomUUID(), UUID.randomUUID());
                            lendingDb.update(
                                    """
                                    INSERT INTO lending.loan_schedule
                                        (tenant_id, loan_id, installment_no, due_date, principal_due_minor,
                                         interest_due_minor)
                                    VALUES (?,?,1,?,500000,10000), (?,?,2,?,500000,10000)
                                    """,
                                    tenantId, loanId, LocalDate.now(ZoneOffset.UTC).minusDays(45),
                                    tenantId, loanId, LocalDate.now(ZoneOffset.UTC).plusMonths(1));
                        });
    }

    @Test
    void accrual_advances_act_365_to_the_kobo_and_reruns_advance_nothing() {
        jobs.accrue();

        // 36.50% on 1,000,000 minor: 1000 minor per day exactly; 10 days behind → 10,000.
        Long accrued =
                workerDb.queryForObject(
                        "SELECT accrued_interest_minor FROM lending.loans WHERE id = ?", Long.class, loanId);
        assertThat(accrued).isEqualTo(10_000L);

        jobs.accrue(); // same day: nothing more to accrue
        assertThat(
                        workerDb.queryForObject(
                                "SELECT accrued_interest_minor FROM lending.loans WHERE id = ?", Long.class, loanId))
                .isEqualTo(10_000L);
    }

    @Test
    void delinquency_classifies_the_bucket_emits_the_event_and_reruns_are_noops() {
        jobs.classify();

        // Oldest unsettled installment is 45 days past due → DPD_31_60.
        assertThat(
                        workerDb.queryForObject(
                                "SELECT current_bucket FROM lending.loans WHERE id = ?", String.class, loanId))
                .isEqualTo("DPD_31_60");
        assertThat(
                        workerDb.queryForObject(
                                "SELECT count(*) FROM lending.delinquency_events WHERE loan_id = ?", Long.class, loanId))
                .isEqualTo(1L);
        assertThat(
                        workerDb.queryForObject(
                                "SELECT count(*) FROM lending.outbox_events WHERE tenant_id = ?"
                                        + " AND event_type = 'loan.delinquent'",
                                Long.class,
                                tenantId))
                .isEqualTo(1L);

        jobs.classify(); // same day, same facts: the unique key makes it a no-op
        assertThat(
                        workerDb.queryForObject(
                                "SELECT count(*) FROM lending.delinquency_events WHERE loan_id = ?", Long.class, loanId))
                .isEqualTo(1L);
    }

    @Test
    void settling_up_recovers_the_loan_to_current() {
        // The loan entered a bucket on an earlier day (seeded directly — the events table is
        // append-only evidence and rightly refuses backdating by edit).
        workerDb.update("UPDATE lending.loans SET current_bucket = 'DPD_31_60' WHERE id = ?", loanId);
        new TransactionTemplate(lendingTx)
                .executeWithoutResult(
                        s -> {
                            lendingDb.queryForObject(
                                    "SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
                            lendingDb.update(
                                    "UPDATE lending.loan_schedule SET principal_paid_minor = principal_due_minor,"
                                            + " interest_paid_minor = interest_due_minor, settled_at = now()"
                                            + " WHERE loan_id = ? AND installment_no = 1",
                                    loanId);
                        });

        jobs.classify();
        assertThat(
                        workerDb.queryForObject(
                                "SELECT current_bucket FROM lending.loans WHERE id = ?", String.class, loanId))
                .isEqualTo("CURRENT");
        assertThat(
                        workerDb.queryForObject(
                                "SELECT count(*) FROM lending.outbox_events WHERE tenant_id = ?"
                                        + " AND event_type = 'loan.recovered'",
                                Long.class,
                                tenantId))
                .isEqualTo(1L);
    }

    @Test
    void the_penalty_pass_charges_flat_once_daily_on_overdue_and_the_cap_binds() {
        // Price penalties on the product: ₦200 flat per late installment, 10 bp/day on overdue
        // principal, capped at ₦240 lifetime. Uncapped arithmetic here would be
        // 20,000 + (500,000 × 10 × 10 / 10,000) = 25,000 minor — the cap binds at 24,000.
        new TransactionTemplate(productTx)
                .executeWithoutResult(
                        s -> {
                            productDb.queryForObject(
                                    "SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
                            UUID productId =
                                    productDb.queryForObject(
                                            "INSERT INTO product.products (tenant_id, code, name, type)"
                                                    + " VALUES (?, 'AJO_LOAN', 'Ajo Loan', 'LOAN') RETURNING id",
                                            UUID.class, tenantId);
                            UUID versionId =
                                    productDb.queryForObject(
                                            "INSERT INTO product.product_versions (tenant_id, product_id, version,"
                                                    + " status, created_by, published_by)"
                                                    + " VALUES (?,?,1,'PUBLISHED','user:author','user:publisher') RETURNING id",
                                            UUID.class, tenantId, productId);
                            productDb.update(
                                    """
                                    INSERT INTO product.loan_rules
                                        (tenant_id, product_version_id, interest_rate_bp, schedule_kind,
                                         min_amount_minor, max_amount_minor, min_term_months, max_term_months,
                                         currency, penalty_flat_minor, penalty_rate_bp, penalty_cap_minor)
                                    VALUES (?,?, 3650, 'FLAT', 10000, 100000000, 1, 36, 'NGN', 20000, 10, 24000)
                                    """,
                                    tenantId, versionId);
                        });

        jobs.penalize();

        assertThat(
                        workerDb.queryForObject(
                                "SELECT penalty_charged_minor FROM lending.loans WHERE id = ?", Long.class, loanId))
                .isEqualTo(24_000L);
        // The flat charge marked its installment; the daily charge advanced the date.
        assertThat(
                        workerDb.queryForObject(
                                "SELECT count(*) FROM lending.loan_schedule WHERE loan_id = ?"
                                        + " AND penalty_applied_at IS NOT NULL",
                                Long.class, loanId))
                .isEqualTo(1L);
        assertThat(
                        workerDb.queryForObject(
                                "SELECT penalty_through FROM lending.loans WHERE id = ?", LocalDate.class, loanId))
                .isEqualTo(LocalDate.now(ZoneOffset.UTC));
        assertThat(
                        workerDb.queryForObject(
                                "SELECT count(*) FROM lending.outbox_events WHERE tenant_id = ?"
                                        + " AND event_type = 'loan.penalty_applied'",
                                Long.class, tenantId))
                .isEqualTo(1L);

        // Same day, same facts: nothing unmarked, zero days advanced, cap already reached.
        jobs.penalize();
        assertThat(
                        workerDb.queryForObject(
                                "SELECT penalty_charged_minor FROM lending.loans WHERE id = ?", Long.class, loanId))
                .isEqualTo(24_000L);
    }

    @Test
    void without_penalty_pricing_the_pass_advances_the_date_and_charges_nothing() {
        jobs.penalize(); // no product rules seeded for this tenant at all
        assertThat(
                        workerDb.queryForObject(
                                "SELECT penalty_charged_minor FROM lending.loans WHERE id = ?", Long.class, loanId))
                .isZero();
        assertThat(
                        workerDb.queryForObject(
                                "SELECT penalty_through FROM lending.loans WHERE id = ?", LocalDate.class, loanId))
                .isEqualTo(LocalDate.now(ZoneOffset.UTC));
    }

    @Test
    void bucket_edges_are_exact() {
        assertThat(LendingJobs.bucketFor(0)).isEqualTo("CURRENT");
        assertThat(LendingJobs.bucketFor(1)).isEqualTo("DPD_1_30");
        assertThat(LendingJobs.bucketFor(30)).isEqualTo("DPD_1_30");
        assertThat(LendingJobs.bucketFor(31)).isEqualTo("DPD_31_60");
        assertThat(LendingJobs.bucketFor(60)).isEqualTo("DPD_31_60");
        assertThat(LendingJobs.bucketFor(61)).isEqualTo("DPD_61_90");
        assertThat(LendingJobs.bucketFor(90)).isEqualTo("DPD_61_90");
        assertThat(LendingJobs.bucketFor(91)).isEqualTo("DPD_90_PLUS");
    }

    @Test
    void the_schemas_evidence_tables_refuse_edits() {
        new TransactionTemplate(lendingTx)
                .executeWithoutResult(
                        s -> {
                            lendingDb.queryForObject(
                                    "SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
                            lendingDb.update(
                                    "INSERT INTO lending.loan_approvals (tenant_id, application_id, sequence_no, approved_by)"
                                            + " VALUES (?,?,1,'user:a')",
                                    tenantId, applicationId);
                        });
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(lendingTx)
                                        .executeWithoutResult(
                                                s -> {
                                                    lendingDb.queryForObject(
                                                            "SELECT set_config('app.tenant_id', ?, true)",
                                                            String.class,
                                                            tenantId.toString());
                                                    lendingDb.update(
                                                            "UPDATE lending.loan_approvals SET approved_by = 'user:b'"
                                                                    + " WHERE application_id = ?",
                                                            applicationId);
                                                }))
                .hasMessageContaining("append-only");

        // A terminal application cannot be reopened.
        new TransactionTemplate(lendingTx)
                .executeWithoutResult(
                        s -> {
                            lendingDb.queryForObject(
                                    "SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
                            lendingDb.update(
                                    "UPDATE lending.loan_applications SET state = 'CLOSED', terminal_at = now()"
                                            + " WHERE id = ?",
                                    applicationId);
                        });
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(lendingTx)
                                        .executeWithoutResult(
                                                s -> {
                                                    lendingDb.queryForObject(
                                                            "SELECT set_config('app.tenant_id', ?, true)",
                                                            String.class,
                                                            tenantId.toString());
                                                    lendingDb.update(
                                                            "UPDATE lending.loan_applications SET state = 'APPLIED'"
                                                                    + " WHERE id = ?",
                                                            applicationId);
                                                }))
                .hasMessageContaining("terminal");
    }
}
