package org.elyonar.fincore.core.orchestration.internal.approval;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.elyonar.fincore.core.orchestration.api.CoreProperties;

/**
 * Maker-checker approvals for business reversals.
 *
 * <p>An approval is <strong>bound and single-use</strong>: it names one target and one amount, and
 * it can be spent once. Both are enforced by the schema rather than here — an approval that could
 * be replayed, or applied to a different amount, is not maker-checker at all. It is a token that
 * happens to have two names on it, and the control it appears to provide is worthless.
 *
 * <p>Identity decides <em>who</em> may make and check; this decides <em>whether the workflow was
 * followed</em>. That split is the platform's: definitions centralized, enforcement by the owning
 * service.
 */
@Repository
public class ApprovalRecords {

    private final JdbcTemplate jdbc;

    public ApprovalRecords(@Qualifier(CoreProperties.Beans.ORCHESTRATION_JDBC) JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private void scopeTo(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }

    /**
     * Raises a pending approval. The maker cannot also check it — the schema refuses.
     *
     * <p>{@code madeInUnit} is a snapshot of the maker's organizational scope at the moment of
     * signature (ADR 0012) — attribution for the audit trail, not an authorization input, and
     * null for callers without one.
     */
    @Transactional
    public UUID raise(UUID tenantId, UUID targetSagaId, long amountMinor, String madeBy, String madeInUnit) {
        scopeTo(tenantId);
        return jdbc.queryForObject(
                """
                INSERT INTO orchestration.approvals
                    (tenant_id, action, target_saga_id, amount_minor, status, made_by, made_in_unit)
                VALUES (?, 'REVERSAL', ?, ?, 'PENDING', ?, ?)
                RETURNING id
                """,
                UUID.class, tenantId, targetSagaId, amountMinor, madeBy, madeInUnit);
    }

    /**
     * Records a checker's decision.
     *
     * @throws ApprovalRejected when the checker is the maker — the one thing maker-checker exists
     *     to prevent, and caught by a CHECK constraint rather than by this method being careful
     */
    @Transactional
    public void check(
            UUID tenantId, UUID approvalId, boolean approved, String checkedBy, String checkedInUnit) {
        scopeTo(tenantId);
        int updated =
                jdbc.update(
                        """
                        UPDATE orchestration.approvals
                           SET status = ?, checked_by = ?, checked_in_unit = ?, checked_at = now()
                         WHERE id = ? AND status = 'PENDING'
                        """,
                        approved ? "APPROVED" : "REJECTED", checkedBy, checkedInUnit, approvalId);
        if (updated == 0) {
            throw new ApprovalRejected("approval is not pending");
        }
    }

    /**
     * Spends an approval, if it is spendable for exactly this target and amount.
     *
     * <p>The conditional UPDATE is the check: its affected-row count decides. Reading first and
     * writing after would leave a window in which two reversals both saw an approved approval.
     */
    @Transactional
    public void consume(UUID tenantId, UUID approvalId, UUID targetSagaId, long amountMinor) {
        scopeTo(tenantId);
        int spent =
                jdbc.update(
                        """
                        UPDATE orchestration.approvals
                           SET status = 'CONSUMED', consumed_at = now()
                         WHERE id = ? AND status = 'APPROVED'
                           AND target_saga_id = ? AND amount_minor = ?
                        """,
                        approvalId, targetSagaId, amountMinor);
        if (spent == 0) {
            // Covers every way this can fail: absent, unapproved, already spent, or bound to a
            // different transaction or amount. Deliberately one message — telling a caller which
            // of those it was maps the control for someone probing it.
            throw new ApprovalRejected("no approval authorizes this reversal");
        }
    }

    /** The approval does not authorize this. */
    public static class ApprovalRejected extends RuntimeException {
        public ApprovalRejected(String message) {
            super(message);
        }
    }
}
