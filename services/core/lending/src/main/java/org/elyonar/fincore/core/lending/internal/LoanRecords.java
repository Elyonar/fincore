package org.elyonar.fincore.core.lending.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.elyonar.fincore.core.lending.api.LendingBeans;
import org.elyonar.fincore.core.lending.internal.outbox.LendingOutbox;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lending's writes and reads: plain SQL, tenant-scoped per connection, races arbitrated by the
 * schema's unique indexes and conditional UPDATEs whose row counts are the check — the discipline
 * every money-adjacent repository here follows.
 */
@Repository
public class LoanRecords {

    private final JdbcTemplate jdbc;
    private final LendingOutbox outbox;

    public LoanRecords(@Qualifier(LendingBeans.JDBC) JdbcTemplate lendingJdbcTemplate, LendingOutbox outbox) {
        this.jdbc = lendingJdbcTemplate;
        this.outbox = outbox;
    }

    private void scopeTo(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }

    private static String money(long minor) {
        return Long.toString(minor);
    }

    // ---------------------------------------------------------------- tiers

    /**
     * How many approvals this amount needs: the lowest tier whose ceiling covers it. No tier
     * covering the amount — including no tiers at all — defaults to <strong>one</strong>: a
     * misconfigured tenant must degrade to "a human looks", never to "everything auto-approves".
     */
    @Transactional(readOnly = true, transactionManager = LendingBeans.TRANSACTION_MANAGER)
    public int requiredApprovals(UUID tenantId, long amountMinor) {
        scopeTo(tenantId);
        Integer required =
                jdbc.query(
                        "SELECT approvals_required FROM lending.approval_tiers"
                                + " WHERE ceiling_minor >= ? ORDER BY ceiling_minor LIMIT 1",
                        rs -> rs.next() ? rs.getInt(1) : null,
                        amountMinor);
        return required == null ? 1 : required;
    }

    @Transactional(transactionManager = LendingBeans.TRANSACTION_MANAGER)
    public void setTier(UUID tenantId, long ceilingMinor, int approvalsRequired) {
        scopeTo(tenantId);
        jdbc.update(
                """
                INSERT INTO lending.approval_tiers (tenant_id, ceiling_minor, approvals_required)
                VALUES (?,?,?)
                ON CONFLICT ON CONSTRAINT one_tier_per_ceiling
                DO UPDATE SET approvals_required = EXCLUDED.approvals_required
                """,
                tenantId, ceilingMinor, approvalsRequired);
    }

    @Transactional(readOnly = true, transactionManager = LendingBeans.TRANSACTION_MANAGER)
    public List<Map<String, Object>> tiers(UUID tenantId) {
        scopeTo(tenantId);
        return jdbc.query(
                "SELECT ceiling_minor, approvals_required FROM lending.approval_tiers ORDER BY ceiling_minor",
                (rs, i) ->
                        Map.of(
                                "ceilingMinor", money(rs.getLong("ceiling_minor")),
                                "approvalsRequired", rs.getInt("approvals_required")));
    }

    // ---------------------------------------------------------------- applications

    @Transactional(transactionManager = LendingBeans.TRANSACTION_MANAGER)
    public UUID createApplication(
            UUID tenantId, UUID customerId, String productCode, int productVersion, long amountMinor,
            int termMonths, String currency, String purpose, int approvalsRequired,
            String appliedBy, String appliedInUnit) {
        scopeTo(tenantId);
        UUID id =
                jdbc.queryForObject(
                        """
                        INSERT INTO lending.loan_applications
                            (tenant_id, customer_id, product_code, product_version, amount_minor,
                             term_months, currency, purpose, approvals_required, applied_by,
                             applied_in_unit, officer)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                        RETURNING id
                        """,
                        UUID.class,
                        tenantId, customerId, productCode, productVersion, amountMinor, termMonths,
                        currency, purpose, approvalsRequired, appliedBy, appliedInUnit, appliedBy);
        outbox.append(
                tenantId,
                "loan.application_received",
                id,
                "{\"applicationId\":\"%s\",\"amountMinor\":\"%d\",\"currency\":\"%s\"}"
                        .formatted(id, amountMinor, currency));
        return id;
    }

    /** The application, or null — absent and another tenant's indistinguishable. */
    @Transactional(readOnly = true, transactionManager = LendingBeans.TRANSACTION_MANAGER)
    public Application application(UUID tenantId, UUID id) {
        scopeTo(tenantId);
        return jdbc.query(
                """
                SELECT id, customer_id, product_code, product_version, amount_minor, term_months,
                       currency, state, approvals_required, applied_by, officer,
                       offer_total_interest_minor, offer_total_cost_minor, offer_effective_rate_bp,
                       offer_expires_at, disbursement_saga_id, funding_account_id,
                       destination_account_id,
                       (SELECT count(*) FROM lending.loan_approvals a WHERE a.application_id = la.id) AS approvals
                  FROM lending.loan_applications la WHERE id = ?
                """,
                rs -> rs.next() ? mapApplication(rs) : null,
                id);
    }

    private static Application mapApplication(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Application(
                rs.getObject("id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getString("product_code"),
                rs.getInt("product_version"),
                rs.getLong("amount_minor"),
                rs.getInt("term_months"),
                rs.getString("currency"),
                rs.getString("state"),
                rs.getInt("approvals_required"),
                rs.getInt("approvals"),
                rs.getString("applied_by"),
                rs.getObject("offer_total_interest_minor") == null ? null : rs.getLong("offer_total_interest_minor"),
                rs.getObject("offer_total_cost_minor") == null ? null : rs.getLong("offer_total_cost_minor"),
                rs.getObject("offer_effective_rate_bp") == null ? null : rs.getInt("offer_effective_rate_bp"),
                rs.getObject("offer_expires_at", java.time.OffsetDateTime.class),
                rs.getObject("disbursement_saga_id", UUID.class),
                rs.getObject("funding_account_id", UUID.class),
                rs.getObject("destination_account_id", UUID.class));
    }

    /**
     * One signature. The applicant may not sign ({@code WHERE applied_by <> ?} is the guard, not
     * an if), a duplicate signer hits the unique index, and the slot number is assigned from the
     * current count — two concurrent signers race to the same slot and one loses to the index,
     * which is the arbitration.
     */
    @Transactional(transactionManager = LendingBeans.TRANSACTION_MANAGER)
    public int approve(UUID tenantId, UUID applicationId, String approvedBy, String approvedInUnit) {
        scopeTo(tenantId);
        try {
            int inserted =
                    jdbc.update(
                            """
                            INSERT INTO lending.loan_approvals
                                (tenant_id, application_id, sequence_no, approved_by, approved_in_unit)
                            SELECT ?, la.id,
                                   (SELECT count(*) + 1 FROM lending.loan_approvals a WHERE a.application_id = la.id),
                                   ?, ?
                              FROM lending.loan_applications la
                             WHERE la.id = ? AND la.state = 'APPLIED' AND la.applied_by <> ?
                            """,
                            tenantId, approvedBy, approvedInUnit, applicationId, approvedBy);
            if (inserted == 0) {
                return -1;
            }
        } catch (DuplicateKeyException e) {
            return -1;
        }
        Integer count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM lending.loan_approvals WHERE application_id = ?",
                        Integer.class,
                        applicationId);
        return count == null ? 0 : count;
    }

    /** The zero-tier signature: the policy is the approver, attributed like anyone. */
    @Transactional(transactionManager = LendingBeans.TRANSACTION_MANAGER)
    public void recordPolicyApproval(UUID tenantId, UUID applicationId) {
        scopeTo(tenantId);
        jdbc.update(
                """
                INSERT INTO lending.loan_approvals (tenant_id, application_id, sequence_no, approved_by)
                VALUES (?, ?, 1, 'system:lending-policy')
                """,
                tenantId, applicationId);
    }

    /** Conditional transition; the row count is the check. Returns false when the state moved on. */
    @Transactional(transactionManager = LendingBeans.TRANSACTION_MANAGER)
    public boolean transition(UUID tenantId, UUID applicationId, String from, String to) {
        scopeTo(tenantId);
        boolean terminal = "REJECTED".equals(to) || "WITHDRAWN".equals(to) || "EXPIRED".equals(to);
        return jdbc.update(
                        "UPDATE lending.loan_applications SET state = ?"
                                + (terminal ? ", terminal_at = now()" : "")
                                + " WHERE id = ? AND state = ?",
                        to, applicationId, from)
                == 1;
    }

    @Transactional(transactionManager = LendingBeans.TRANSACTION_MANAGER)
    public boolean recordOffer(
            UUID tenantId, UUID applicationId, String from, long totalInterestMinor, long totalCostMinor,
            int effectiveRateBp, java.time.OffsetDateTime expiresAt) {
        scopeTo(tenantId);
        boolean moved =
                jdbc.update(
                                """
                                UPDATE lending.loan_applications
                                   SET state = 'OFFERED', offer_total_interest_minor = ?,
                                       offer_total_cost_minor = ?, offer_effective_rate_bp = ?,
                                       offer_expires_at = ?
                                 WHERE id = ? AND state = ?
                                """,
                                totalInterestMinor, totalCostMinor, effectiveRateBp, expiresAt, applicationId, from)
                        == 1;
        if (moved) {
            outbox.append(
                    tenantId,
                    "loan.approved",
                    applicationId,
                    "{\"applicationId\":\"%s\",\"totalCostMinor\":\"%d\"}".formatted(applicationId, totalCostMinor));
        }
        return moved;
    }

    @Transactional(transactionManager = LendingBeans.TRANSACTION_MANAGER)
    public boolean setDisbursing(
            UUID tenantId, UUID applicationId, UUID sagaId, UUID fundingAccountId, UUID destinationAccountId) {
        scopeTo(tenantId);
        return jdbc.update(
                        """
                        UPDATE lending.loan_applications
                           SET state = 'DISBURSING', disbursement_saga_id = ?,
                               funding_account_id = ?, destination_account_id = ?
                         WHERE id = ? AND state = 'ACCEPTED'
                        """,
                        sagaId, fundingAccountId, destinationAccountId, applicationId)
                == 1;
    }

    @Transactional(transactionManager = LendingBeans.TRANSACTION_MANAGER)
    public void setDisbursingSaga(UUID tenantId, UUID applicationId, UUID sagaId) {
        scopeTo(tenantId);
        jdbc.update(
                "UPDATE lending.loan_applications SET disbursement_saga_id = ? WHERE id = ?",
                sagaId, applicationId);
    }

    @Transactional(transactionManager = LendingBeans.TRANSACTION_MANAGER)
    public boolean backToAccepted(UUID tenantId, UUID applicationId, String error) {
        scopeTo(tenantId);
        return jdbc.update(
                        "UPDATE lending.loan_applications SET state = 'ACCEPTED', last_error = ?,"
                                + " disbursement_saga_id = NULL WHERE id = ? AND state = 'DISBURSING'",
                        error, applicationId)
                == 1;
    }

    // ---------------------------------------------------------------- loans + schedule

    /**
     * Activation: the loan, its schedule and the application's transition, one transaction. The
     * one-loan-per-application index makes a disbursement retry converge instead of duplicate.
     */
    @Transactional(transactionManager = LendingBeans.TRANSACTION_MANAGER)
    public UUID activate(
            UUID tenantId, Application app, int rateBp, String scheduleKind, LocalDate disbursedOn,
            List<ScheduleEngine.Installment> schedule, String officer, String unitCode) {
        scopeTo(tenantId);
        UUID loanId;
        try {
            loanId =
                    jdbc.queryForObject(
                            """
                            INSERT INTO lending.loans
                                (tenant_id, application_id, customer_id, product_code, product_version,
                                 principal_minor, principal_outstanding_minor, interest_rate_bp,
                                 schedule_kind, currency, accrual_through, disbursed_on, penalty_through,
                                 funding_account_id, customer_account_id, officer, unit_code)
                            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                            RETURNING id
                            """,
                            UUID.class,
                            tenantId, app.id(), app.customerId(), app.productCode(), app.productVersion(),
                            app.amountMinor(), app.amountMinor(), rateBp, scheduleKind, app.currency(),
                            disbursedOn, disbursedOn, disbursedOn, app.fundingAccountId(),
                            app.destinationAccountId(), officer, unitCode);
        } catch (DuplicateKeyException e) {
            // A concurrent activation won; converge on it.
            return jdbc.queryForObject(
                    "SELECT id FROM lending.loans WHERE application_id = ?", UUID.class, app.id());
        }
        for (ScheduleEngine.Installment row : schedule) {
            jdbc.update(
                    """
                    INSERT INTO lending.loan_schedule
                        (tenant_id, loan_id, installment_no, due_date, principal_due_minor, interest_due_minor)
                    VALUES (?,?,?,?,?,?)
                    """,
                    tenantId, loanId, row.no(), row.dueDate(), row.principalMinor(), row.interestMinor());
        }
        jdbc.update(
                "UPDATE lending.loan_applications SET state = 'ACTIVE' WHERE id = ? AND state = 'DISBURSING'",
                app.id());
        outbox.append(
                tenantId,
                "loan.disbursed",
                loanId,
                "{\"loanId\":\"%s\",\"applicationId\":\"%s\",\"principalMinor\":\"%d\",\"currency\":\"%s\"}"
                        .formatted(loanId, app.id(), app.amountMinor(), app.currency()));
        return loanId;
    }

    @Transactional(readOnly = true, transactionManager = LendingBeans.TRANSACTION_MANAGER)
    public Loan loan(UUID tenantId, UUID id) {
        scopeTo(tenantId);
        return jdbc.query(
                """
                SELECT id, application_id, customer_id, product_code, product_version, principal_minor,
                       principal_outstanding_minor, interest_rate_bp, schedule_kind, currency,
                       accrued_interest_minor, accrual_through, disbursed_on, funding_account_id,
                       customer_account_id, officer, unit_code, current_bucket, state,
                       interest_paid_minor, recognized_interest_minor,
                       penalty_charged_minor, penalty_paid_minor
                  FROM lending.loans WHERE id = ?
                """,
                rs -> rs.next() ? mapLoan(rs) : null,
                id);
    }

    static Loan mapLoan(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Loan(
                rs.getObject("id", UUID.class),
                rs.getObject("application_id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getString("product_code"),
                rs.getInt("product_version"),
                rs.getLong("principal_minor"),
                rs.getLong("principal_outstanding_minor"),
                rs.getInt("interest_rate_bp"),
                rs.getString("schedule_kind"),
                rs.getString("currency"),
                rs.getLong("accrued_interest_minor"),
                rs.getObject("accrual_through", LocalDate.class),
                rs.getObject("disbursed_on", LocalDate.class),
                rs.getObject("funding_account_id", UUID.class),
                rs.getObject("customer_account_id", UUID.class),
                rs.getString("officer"),
                rs.getString("unit_code"),
                rs.getString("current_bucket"),
                rs.getString("state"),
                rs.getLong("interest_paid_minor"),
                rs.getLong("recognized_interest_minor"),
                rs.getLong("penalty_charged_minor"),
                rs.getLong("penalty_paid_minor"));
    }

    @Transactional(readOnly = true, transactionManager = LendingBeans.TRANSACTION_MANAGER)
    public List<Map<String, Object>> schedule(UUID tenantId, UUID loanId) {
        scopeTo(tenantId);
        return jdbc.query(
                """
                SELECT installment_no, due_date, principal_due_minor, interest_due_minor,
                       principal_paid_minor, interest_paid_minor, settled_at
                  FROM lending.loan_schedule WHERE loan_id = ? ORDER BY installment_no
                """,
                (rs, i) -> {
                    var row = new java.util.LinkedHashMap<String, Object>();
                    row.put("installmentNo", rs.getInt("installment_no"));
                    row.put("dueDate", rs.getObject("due_date", LocalDate.class).toString());
                    row.put("principalDueMinor", money(rs.getLong("principal_due_minor")));
                    row.put("interestDueMinor", money(rs.getLong("interest_due_minor")));
                    row.put("principalPaidMinor", money(rs.getLong("principal_paid_minor")));
                    row.put("interestPaidMinor", money(rs.getLong("interest_paid_minor")));
                    row.put("settled", rs.getObject("settled_at") != null);
                    return row;
                },
                loanId);
    }

    // ---------------------------------------------------------------- repayments

    /** Intake: the row exists before the saga, so a lost response can always be reconciled. */
    @Transactional(transactionManager = LendingBeans.TRANSACTION_MANAGER)
    public UUID createRepayment(
            UUID tenantId, UUID loanId, long amountMinor, UUID sourceAccountId, String idempotencyKey,
            LocalDate receivedOn) {
        scopeTo(tenantId);
        try {
            return jdbc.queryForObject(
                    """
                    INSERT INTO lending.repayments
                        (tenant_id, loan_id, amount_minor, source_account_id, idempotency_key, received_on)
                    VALUES (?,?,?,?,?,?)
                    RETURNING id
                    """,
                    UUID.class,
                    tenantId, loanId, amountMinor, sourceAccountId, idempotencyKey, receivedOn);
        } catch (DuplicateKeyException e) {
            return null; // replay: the caller re-drives the existing row
        }
    }

    @Transactional(readOnly = true, transactionManager = LendingBeans.TRANSACTION_MANAGER)
    public Repayment repaymentByKey(UUID tenantId, String idempotencyKey) {
        scopeTo(tenantId);
        return jdbc.query(
                "SELECT id, loan_id, amount_minor, source_account_id, repayment_saga_id, state"
                        + " FROM lending.repayments WHERE idempotency_key = ?",
                rs ->
                        rs.next()
                                ? new Repayment(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("loan_id", UUID.class),
                                        rs.getLong("amount_minor"),
                                        rs.getObject("source_account_id", UUID.class),
                                        rs.getObject("repayment_saga_id", UUID.class),
                                        rs.getString("state"))
                                : null,
                idempotencyKey);
    }

    @Transactional(transactionManager = LendingBeans.TRANSACTION_MANAGER)
    public void recordRepaymentSaga(UUID tenantId, UUID repaymentId, UUID sagaId) {
        scopeTo(tenantId);
        jdbc.update(
                "UPDATE lending.repayments SET repayment_saga_id = ? WHERE id = ?", sagaId, repaymentId);
    }

    @Transactional(transactionManager = LendingBeans.TRANSACTION_MANAGER)
    public void failRepayment(UUID tenantId, UUID repaymentId) {
        scopeTo(tenantId);
        jdbc.update("UPDATE lending.repayments SET state = 'FAILED' WHERE id = ?", repaymentId);
    }

    /**
     * The allocation, whole: component rows, schedule paid columns, the loan's accrued interest
     * and outstanding principal, closure when payoff reached, and the event — one transaction,
     * guarded by the repayment's own state so it happens exactly once.
     */
    @Transactional(transactionManager = LendingBeans.TRANSACTION_MANAGER)
    public boolean allocate(UUID tenantId, UUID repaymentId, Allocation allocation) {
        scopeTo(tenantId);
        int claimed =
                jdbc.update(
                        "UPDATE lending.repayments SET state = 'ALLOCATED', allocated_at = now()"
                                + " WHERE id = ? AND state = 'PENDING'",
                        repaymentId);
        if (claimed == 0) {
            return false; // already allocated (or failed) by a racing path
        }
        for (Allocation.Component c : allocation.components()) {
            jdbc.update(
                    """
                    INSERT INTO lending.repayment_allocations
                        (tenant_id, repayment_id, component, amount_minor, installment_no)
                    VALUES (?,?,?,?,?)
                    """,
                    tenantId, repaymentId, c.component(), c.amountMinor(), c.installmentNo());
        }
        for (Allocation.InstallmentUpdate u : allocation.installmentUpdates()) {
            jdbc.update(
                    """
                    UPDATE lending.loan_schedule
                       SET principal_paid_minor = principal_paid_minor + ?,
                           interest_paid_minor = interest_paid_minor + ?,
                           settled_at = CASE WHEN principal_paid_minor + ? >= principal_due_minor
                                              AND interest_paid_minor + ? >= interest_due_minor
                                             THEN now() ELSE settled_at END
                     WHERE loan_id = ? AND installment_no = ?
                    """,
                    u.principalMinor(), u.interestMinor(), u.principalMinor(), u.interestMinor(),
                    allocation.loanId(), u.installmentNo());
        }
        jdbc.update(
                """
                UPDATE lending.loans
                   SET principal_outstanding_minor = principal_outstanding_minor - ?,
                       accrued_interest_minor = accrued_interest_minor - ?,
                       interest_paid_minor = interest_paid_minor + ?,
                       penalty_paid_minor = penalty_paid_minor + ?
                 WHERE id = ?
                """,
                allocation.principalMinor(), allocation.interestMinor(), allocation.interestMinor(),
                allocation.penaltyMinor(), allocation.loanId());
        outbox.append(
                tenantId,
                "loan.repayment_allocated",
                allocation.loanId(),
                "{\"loanId\":\"%s\",\"repaymentId\":\"%s\",\"principalMinor\":\"%d\",\"interestMinor\":\"%d\",\"penaltyMinor\":\"%d\"}"
                        .formatted(allocation.loanId(), repaymentId, allocation.principalMinor(),
                                allocation.interestMinor(), allocation.penaltyMinor()));
        if (allocation.closesLoan()) {
            jdbc.update(
                    "UPDATE lending.loans SET state = 'CLOSED', closed_at = now(), current_bucket = 'CURRENT'"
                            + " WHERE id = ? AND state = 'ACTIVE'",
                    allocation.loanId());
            jdbc.update(
                    "UPDATE lending.loan_applications SET state = 'CLOSED', terminal_at = now()"
                            + " WHERE id = (SELECT application_id FROM lending.loans WHERE id = ?) AND state = 'ACTIVE'",
                    allocation.loanId());
            outbox.append(
                    tenantId,
                    "loan.closed",
                    allocation.loanId(),
                    "{\"loanId\":\"%s\"}".formatted(allocation.loanId()));
        }
        return true;
    }

    // ---------------------------------------------------------------- recognition

    /**
     * What an allocated repayment has to recognize: its interest and penalty portions, read from
     * the allocation evidence — fixed forever the moment allocation committed, which is what
     * makes the per-repayment saga keys replay-stable (lending.md v1.17).
     */
    @Transactional(readOnly = true, transactionManager = LendingBeans.TRANSACTION_MANAGER)
    public RecognitionCandidate recognitionCandidate(UUID tenantId, UUID repaymentId) {
        scopeTo(tenantId);
        return jdbc.query(
                """
                SELECT r.loan_id, r.state, r.recognized_at,
                       COALESCE((SELECT SUM(a.amount_minor) FROM lending.repayment_allocations a
                                  WHERE a.repayment_id = r.id AND a.component = 'INTEREST'), 0) AS interest,
                       COALESCE((SELECT SUM(a.amount_minor) FROM lending.repayment_allocations a
                                  WHERE a.repayment_id = r.id AND a.component = 'PENALTY'), 0) AS penalty
                  FROM lending.repayments r WHERE r.id = ?
                """,
                rs ->
                        rs.next()
                                ? new RecognitionCandidate(
                                        rs.getObject("loan_id", UUID.class),
                                        rs.getString("state"),
                                        rs.getObject("recognized_at") != null,
                                        rs.getLong("interest"),
                                        rs.getLong("penalty"))
                                : null,
                repaymentId);
    }

    /**
     * Recognition resolved: the mark is claimed conditionally, so the loan's recognized counter
     * advances exactly once per repayment however many paths race to converge it.
     */
    @Transactional(transactionManager = LendingBeans.TRANSACTION_MANAGER)
    public boolean markRecognized(UUID tenantId, UUID repaymentId, UUID loanId, long postedInterestMinor) {
        scopeTo(tenantId);
        int claimed =
                jdbc.update(
                        "UPDATE lending.repayments SET recognized_at = now()"
                                + " WHERE id = ? AND state = 'ALLOCATED' AND recognized_at IS NULL",
                        repaymentId);
        if (claimed == 0) {
            return false;
        }
        if (postedInterestMinor > 0) {
            jdbc.update(
                    "UPDATE lending.loans SET recognized_interest_minor = recognized_interest_minor + ?"
                            + " WHERE id = ?",
                    postedInterestMinor, loanId);
        }
        return true;
    }

    public record RecognitionCandidate(
            UUID loanId, String state, boolean recognized, long interestMinor, long penaltyMinor) {}

    // ---------------------------------------------------------------- screen lists (ui-runway.md §3)

    /**
     * Applications, filterable by state and by "awaiting my signature" — the loan desk's opening
     * screen. Keyset-paginated on id; the awaiting filter reproduces the approve guard's own
     * conditions (applicant may not sign, one signature per principal, chain unsatisfied), so the
     * queue never lists an application the sign button would refuse.
     */
    @Transactional(readOnly = true, transactionManager = LendingBeans.TRANSACTION_MANAGER)
    public List<Map<String, Object>> applications(
            UUID tenantId, String state, String awaitingPrincipal, UUID afterId, int limit) {
        scopeTo(tenantId);
        return jdbc.query(
                """
                SELECT la.id, la.customer_id, la.product_code, la.amount_minor, la.term_months,
                       la.currency, la.state, la.approvals_required, la.applied_by, la.created_at,
                       (SELECT count(*) FROM lending.loan_approvals a WHERE a.application_id = la.id) AS approvals
                  FROM lending.loan_applications la
                 WHERE (?::text IS NULL OR la.state = ?)
                   AND (?::uuid IS NULL OR la.id > ?::uuid)
                   AND (?::text IS NULL OR (
                        la.state = 'APPLIED'
                        AND la.applied_by <> ?
                        AND NOT EXISTS (SELECT 1 FROM lending.loan_approvals a
                                         WHERE a.application_id = la.id AND a.approved_by = ?)
                        AND (SELECT count(*) FROM lending.loan_approvals a
                              WHERE a.application_id = la.id) < la.approvals_required))
                 ORDER BY la.id
                 LIMIT ?
                """,
                (rs, i) -> {
                    var row = new java.util.LinkedHashMap<String, Object>();
                    row.put("applicationId", rs.getObject("id", UUID.class).toString());
                    row.put("customerId", rs.getObject("customer_id", UUID.class).toString());
                    row.put("productCode", rs.getString("product_code"));
                    row.put("amountMinor", money(rs.getLong("amount_minor")));
                    row.put("termMonths", rs.getInt("term_months"));
                    row.put("currency", rs.getString("currency"));
                    row.put("state", rs.getString("state"));
                    row.put("approvals", rs.getInt("approvals"));
                    row.put("approvalsRequired", rs.getInt("approvals_required"));
                    row.put("appliedBy", rs.getString("applied_by"));
                    row.put("createdAt", rs.getObject("created_at", java.time.OffsetDateTime.class).toString());
                    return row;
                },
                state, state, afterId, afterId,
                awaitingPrincipal, awaitingPrincipal, awaitingPrincipal, limit);
    }

    /** A customer's loans — the 360 view's lending panel. */
    @Transactional(readOnly = true, transactionManager = LendingBeans.TRANSACTION_MANAGER)
    public List<Map<String, Object>> loansOf(UUID tenantId, UUID customerId) {
        scopeTo(tenantId);
        return jdbc.query(
                """
                SELECT id, product_code, principal_minor, principal_outstanding_minor,
                       accrued_interest_minor, penalty_charged_minor, penalty_paid_minor,
                       currency, current_bucket, state, disbursed_on
                  FROM lending.loans
                 WHERE customer_id = ?
                 ORDER BY disbursed_on DESC, id
                """,
                (rs, i) -> {
                    var row = new java.util.LinkedHashMap<String, Object>();
                    row.put("loanId", rs.getObject("id", UUID.class).toString());
                    row.put("productCode", rs.getString("product_code"));
                    row.put("principalMinor", money(rs.getLong("principal_minor")));
                    row.put("principalOutstandingMinor", money(rs.getLong("principal_outstanding_minor")));
                    row.put(
                            "payoffMinor",
                            money(
                                    rs.getLong("principal_outstanding_minor")
                                            + rs.getLong("accrued_interest_minor")
                                            + rs.getLong("penalty_charged_minor")
                                            - rs.getLong("penalty_paid_minor")));
                    row.put("currency", rs.getString("currency"));
                    row.put("bucket", rs.getString("current_bucket"));
                    row.put("state", rs.getString("state"));
                    row.put("disbursedOn", rs.getObject("disbursed_on", LocalDate.class).toString());
                    return row;
                },
                customerId);
    }

    /** A loan's repayment history — evidence, oldest first. */
    @Transactional(readOnly = true, transactionManager = LendingBeans.TRANSACTION_MANAGER)
    public List<Map<String, Object>> repaymentsOf(UUID tenantId, UUID loanId) {
        scopeTo(tenantId);
        return jdbc.query(
                """
                SELECT r.id, r.amount_minor, r.state, r.received_on, r.created_at,
                       COALESCE((SELECT SUM(a.amount_minor) FROM lending.repayment_allocations a
                                  WHERE a.repayment_id = r.id AND a.component = 'PRINCIPAL'), 0) AS principal,
                       COALESCE((SELECT SUM(a.amount_minor) FROM lending.repayment_allocations a
                                  WHERE a.repayment_id = r.id AND a.component = 'INTEREST'), 0) AS interest,
                       COALESCE((SELECT SUM(a.amount_minor) FROM lending.repayment_allocations a
                                  WHERE a.repayment_id = r.id AND a.component = 'PENALTY'), 0) AS penalty
                  FROM lending.repayments r
                 WHERE r.loan_id = ?
                 ORDER BY r.created_at, r.id
                """,
                (rs, i) -> {
                    var row = new java.util.LinkedHashMap<String, Object>();
                    row.put("repaymentId", rs.getObject("id", UUID.class).toString());
                    row.put("amountMinor", money(rs.getLong("amount_minor")));
                    row.put("state", rs.getString("state"));
                    row.put("receivedOn", rs.getObject("received_on", LocalDate.class).toString());
                    row.put("principalMinor", money(rs.getLong("principal")));
                    row.put("interestMinor", money(rs.getLong("interest")));
                    row.put("penaltyMinor", money(rs.getLong("penalty")));
                    return row;
                },
                loanId);
    }

    // ---------------------------------------------------------------- analytics

    /** PAR: outstanding principal per bucket × product × officer × unit. */
    @Transactional(readOnly = true, transactionManager = LendingBeans.TRANSACTION_MANAGER)
    public List<Map<String, Object>> portfolioAtRisk(UUID tenantId) {
        scopeTo(tenantId);
        return jdbc.query(
                """
                SELECT current_bucket, product_code, officer, COALESCE(unit_code, '') AS unit_code,
                       count(*) AS loans, SUM(principal_outstanding_minor) AS outstanding
                  FROM lending.loans
                 WHERE state = 'ACTIVE'
                 GROUP BY current_bucket, product_code, officer, unit_code
                 ORDER BY current_bucket, product_code
                """,
                (rs, i) -> {
                    var row = new java.util.LinkedHashMap<String, Object>();
                    row.put("bucket", rs.getString("current_bucket"));
                    row.put("productCode", rs.getString("product_code"));
                    row.put("officer", rs.getString("officer"));
                    row.put("unitCode", rs.getString("unit_code"));
                    row.put("loans", rs.getLong("loans"));
                    row.put("outstandingMinor", money(rs.getLong("outstanding")));
                    return row;
                });
    }

    // ---------------------------------------------------------------- records

    public record Application(
            UUID id, UUID customerId, String productCode, int productVersion, long amountMinor,
            int termMonths, String currency, String state, int approvalsRequired, int approvals,
            String appliedBy, Long offerTotalInterestMinor, Long offerTotalCostMinor,
            Integer offerEffectiveRateBp, java.time.OffsetDateTime offerExpiresAt,
            UUID disbursementSagaId, UUID fundingAccountId, UUID destinationAccountId) {}

    public record Loan(
            UUID id, UUID applicationId, UUID customerId, String productCode, int productVersion,
            long principalMinor, long principalOutstandingMinor, int interestRateBp, String scheduleKind,
            String currency, long accruedInterestMinor, LocalDate accrualThrough, LocalDate disbursedOn,
            UUID fundingAccountId, UUID customerAccountId, String officer, String unitCode,
            String currentBucket, String state, long interestPaidMinor, long recognizedInterestMinor,
            long penaltyChargedMinor, long penaltyPaidMinor) {

        /** Penalties owed right now — always the subtraction, never a third counter. */
        public long penaltyDueMinor() {
            return penaltyChargedMinor - penaltyPaidMinor;
        }
    }

    public record Repayment(
            UUID id, UUID loanId, long amountMinor, UUID sourceAccountId, UUID sagaId, String state) {}

    /** What an allocation did, computed by the service, applied here whole. */
    public record Allocation(
            UUID loanId, long principalMinor, long interestMinor, long penaltyMinor, boolean closesLoan,
            List<Component> components, List<InstallmentUpdate> installmentUpdates) {
        public record Component(String component, long amountMinor, Integer installmentNo) {}

        public record InstallmentUpdate(int installmentNo, long principalMinor, long interestMinor) {}
    }
}
