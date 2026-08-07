package org.elyonar.fincore.core.orchestration.internal.saga;

import java.util.UUID;
import org.elyonar.fincore.core.orchestration.api.LedgerOutcome;
import org.elyonar.fincore.core.orchestration.api.TransferResult;
import org.elyonar.fincore.core.orchestration.internal.approval.ApprovalRecords;
import org.elyonar.fincore.core.orchestration.internal.ledger.LedgerClient;
import org.springframework.stereotype.Service;
import org.elyonar.fincore.core.orchestration.api.CoreException;
import org.elyonar.fincore.core.orchestration.api.ErrorCode;

/**
 * Business reversal: a human undoing a transaction the system considers correct.
 *
 * <p>Distinct from a compensating reversal in every way that matters, and deliberately not sharing
 * a code path with one. A compensation is a saga undoing a posting <em>it made itself</em> after a
 * downstream {@code DEFINITE_FAILURE} — automated, self-targeted, zero discretion. This is somebody
 * deciding that a correct transaction should not stand, which is a judgement, and judgement is what
 * maker-checker gates.
 *
 * <p>A reversal is <strong>its own saga</strong> rather than a state change on the original. That is
 * what keeps terminal states terminal and the audit trail additive: the original stays
 * {@code COMPLETED}, and the reversal is a separate, linked record.
 */
@Service
public class ReversalService {

    private static final String REVERSE_STEP = "reverse";

    private final SagaRecords sagas;
    private final ApprovalRecords approvals;
    private final LedgerClient ledger;

    public ReversalService(SagaRecords sagas, ApprovalRecords approvals, LedgerClient ledger) {
        this.sagas = sagas;
        this.approvals = approvals;
        this.ledger = ledger;
    }

    /**
     * Reverses a completed transaction.
     *
     * @param approvalId a maker-checker approval bound to this transaction and amount. Spent here,
     *     and spendable once.
     */
    public TransferResult reverse(
            UUID tenantId, UUID originalSagaId, UUID approvalId, String idempotencyKey, String initiatedBy) {

        // Replay first, as everywhere: a repeated reversal request returns the original answer
        // rather than raising a second reversal.
        SagaRecords.Existing existing = sagas.findByKey(tenantId, idempotencyKey);
        if (existing != null) {
            return existing.result();
        }

        SagaRecords.Reversible original = sagas.loadReversible(tenantId, originalSagaId);
        if (original == null) {
            // Not COMPLETED, already a reversal, or another tenant's. One answer for all three:
            // distinguishing them tells a caller what exists elsewhere.
            throw new NotReversible();
        }

        // Spending the approval before the call, not after. An approval spent only on success
        // could authorize a second attempt after an unknown outcome — and the second attempt is
        // exactly where a double credit would come from.
        approvals.consume(tenantId, approvalId, originalSagaId, original.amountMinor());

        UUID reversalSagaId =
                sagas.openReversal(tenantId, originalSagaId, approvalId, idempotencyKey, original, initiatedBy);

        LedgerOutcome outcome =
                ledger.reverse(
                        tenantId,
                        original.ledgerTransactionId(),
                        IdempotencyKeys.forStep(reversalSagaId, REVERSE_STEP),
                        initiatedBy);

        return switch (outcome) {
            // Includes ALREADY_REVERSED: someone else's reversal won and the response carries its
            // id, so the saga converges on it rather than retry-looping against a settled outcome.
            case LedgerOutcome.Success success ->
                    sagas.complete(tenantId, reversalSagaId, success.ledgerTransactionId());

            case LedgerOutcome.DefiniteFailure failure -> {
                sagas.fail(tenantId, reversalSagaId, failure.errorCode());
                throw TransferService.TransferRefused.fromLedger(failure.errorCode());
            }

            case LedgerOutcome.Unknown unknown -> {
                sagas.recordUnknownAttempt(tenantId, reversalSagaId, unknown.reason());
                throw new TransferService.OutcomeUnknown(reversalSagaId, unknown.reason());
            }
        };
    }

    /** The target is not something that can be reversed. */
    public static class NotReversible extends CoreException {
        public NotReversible() {
            // One answer for "not COMPLETED", "already a reversal" and "another tenant's". No
            // reason is carried to the caller: distinguishing them would let a caller probe for the
            // existence of another tenant's saga, and that outranks precision.
            super(ErrorCode.NOT_REVERSIBLE, "target is not reversible");
        }
    }
}
