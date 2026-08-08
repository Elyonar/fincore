package org.elyonar.fincore.core.orchestration.internal.saga;

import java.util.List;
import java.util.UUID;
import org.elyonar.fincore.core.customer.api.CustomerEligibility;
import org.elyonar.fincore.core.customer.api.EligibilityResult;
import org.elyonar.fincore.core.orchestration.api.ErrorCode;
import org.elyonar.fincore.core.orchestration.api.FundingCommand;
import org.elyonar.fincore.core.orchestration.api.LedgerOutcome;
import org.elyonar.fincore.core.orchestration.api.LedgerPosting;
import org.elyonar.fincore.core.orchestration.api.TransferResult;
import org.elyonar.fincore.core.orchestration.internal.ledger.LedgerClient;
import org.springframework.stereotype.Service;

/**
 * Institution-initiated movement: the three phases, minus the customer-channel gates.
 *
 * <p>A {@code DISBURSEMENT} draws on the tenant's own funding account, so there is no
 * account-holding check on the source; the authorization happened in Lending's approval chain. A
 * {@code REPAYMENT} debits a customer, so the customer must exist, be active, and hold the source
 * account — the same three questions a transfer asks, without the product/limit machinery that
 * protects customer-*channel* traffic (lending.md records why).
 */
@Service
public class FundingService {

    private static final String POST_STEP = "post";

    private final SagaRecords sagas;
    private final CustomerEligibility customers;
    private final LedgerClient ledger;

    public FundingService(SagaRecords sagas, CustomerEligibility customers, LedgerClient ledger) {
        this.sagas = sagas;
        this.customers = customers;
        this.ledger = ledger;
    }

    public TransferResult execute(FundingCommand command) {
        SagaRecords.Existing existing = sagas.findByKey(command.tenantId(), command.idempotencyKey());
        if (existing != null) {
            if (!existing.fingerprint().equals(command.fingerprint())) {
                throw new TransferService.IdempotencyKeyReused(command.idempotencyKey());
            }
            return existing.result();
        }

        // ---- Phase A ---------------------------------------------------------
        if (command.kind() == FundingCommand.Kind.REPAYMENT) {
            EligibilityResult eligibility = customers.check(command.tenantId(), command.customerId());
            if (!eligibility.eligible()) {
                throw new TransferService.TransferRefused(
                        eligibility.reason() == EligibilityResult.Reason.NOT_FOUND
                                ? ErrorCode.CUSTOMER_NOT_FOUND
                                : ErrorCode.CUSTOMER_NOT_ACTIVE);
            }
            if (!customers.holdsAccount(command.tenantId(), command.customerId(), command.sourceAccountId())) {
                throw new TransferService.TransferRefused(ErrorCode.ACCOUNT_NOT_LINKED);
            }
        }

        UUID sagaId = sagas.openFunding(command);

        // ---- Phase B ---------------------------------------------------------
        LedgerOutcome outcome =
                ledger.post(
                        command.tenantId(),
                        postingFor(command, IdempotencyKeys.forStep(sagaId, POST_STEP)));

        // ---- Phase C ---------------------------------------------------------
        return switch (outcome) {
            case LedgerOutcome.Success success ->
                    sagas.complete(command.tenantId(), sagaId, success.ledgerTransactionId());
            case LedgerOutcome.DefiniteFailure failure -> {
                sagas.fail(command.tenantId(), sagaId, failure.errorCode());
                throw TransferService.TransferRefused.fromLedger(failure.errorCode());
            }
            case LedgerOutcome.Unknown unknown -> {
                sagas.recordUnknownAttempt(command.tenantId(), sagaId, unknown.reason());
                throw new TransferService.OutcomeUnknown(sagaId, unknown.reason());
            }
        };
    }

    private LedgerPosting postingFor(FundingCommand command, String key) {
        return new LedgerPosting(
                key,
                command.initiatedBy(),
                command.reference(),
                List.of(
                        new LedgerPosting.Entry(
                                command.sourceAccountId(),
                                LedgerPosting.Direction.DEBIT,
                                command.amountMinor(),
                                command.currency()),
                        new LedgerPosting.Entry(
                                command.destinationAccountId(),
                                LedgerPosting.Direction.CREDIT,
                                command.amountMinor(),
                                command.currency())));
    }
}
