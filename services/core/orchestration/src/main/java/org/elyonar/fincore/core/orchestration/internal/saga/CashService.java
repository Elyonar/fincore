package org.elyonar.fincore.core.orchestration.internal.saga;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.elyonar.fincore.core.customer.api.CustomerEligibility;
import org.elyonar.fincore.core.customer.api.EligibilityResult;
import org.elyonar.fincore.core.orchestration.api.CashCommand;
import org.elyonar.fincore.core.orchestration.api.LedgerOutcome;
import org.elyonar.fincore.core.orchestration.api.LedgerPosting;
import org.elyonar.fincore.core.orchestration.api.TransferResult;
import org.elyonar.fincore.core.orchestration.internal.ledger.LedgerClient;
import org.elyonar.fincore.core.product.api.ProductDecision;
import org.elyonar.fincore.core.product.api.ProductDecisions;
import org.elyonar.fincore.core.product.api.ProductRequest;
import org.springframework.stereotype.Service;
import org.elyonar.fincore.core.orchestration.api.ErrorCode;

/**
 * Cash over the counter: deposits and withdrawals.
 *
 * <p>The same three phases a transfer uses, differing only in the entries. What makes these
 * interesting is the till: cash physically changes hands, and the till is a ledger account, so the
 * movement is double-entry like everything else rather than a special case (PRD §4.7.4).
 *
 * <p>Direction follows the platform's convention for cash (PRD §4.7.2): <strong>cash in debits the
 * till and credits the customer</strong>; cash out is the mirror. Stating it here because getting
 * it backwards produces a system that balances perfectly and is wrong in every till.
 */
@Service
public class CashService {

    private static final String POST_STEP = "post";

    private final SagaRecords sagas;
    private final TillRecords tills;
    private final CustomerEligibility customers;
    private final ProductDecisions products;
    private final LedgerClient ledger;

    public CashService(
            SagaRecords sagas,
            TillRecords tills,
            CustomerEligibility customers,
            ProductDecisions products,
            LedgerClient ledger) {
        this.sagas = sagas;
        this.tills = tills;
        this.customers = customers;
        this.products = products;
        this.ledger = ledger;
    }

    public TransferResult execute(CashCommand command) {
        SagaRecords.Existing existing = sagas.findByKey(command.tenantId(), command.idempotencyKey());
        if (existing != null) {
            if (!existing.fingerprint().equals(command.fingerprint())) {
                throw new TransferService.IdempotencyKeyReused(command.idempotencyKey());
            }
            return existing.result();
        }

        // ---- Phase A ---------------------------------------------------------
        EligibilityResult eligibility = customers.check(command.tenantId(), command.customerId());
        if (!eligibility.eligible()) {
            throw new TransferService.TransferRefused(
                    eligibility.reason() == EligibilityResult.Reason.NOT_FOUND
                            ? ErrorCode.CUSTOMER_NOT_FOUND
                            : ErrorCode.CUSTOMER_NOT_ACTIVE);
        }
        if (!customers.holdsAccount(command.tenantId(), command.customerId(), command.customerAccountId())) {
            throw new TransferService.TransferRefused(ErrorCode.ACCOUNT_NOT_LINKED);
        }

        TillRecords.Till till = tills.openTill(command.tenantId(), command.tillId());
        if (till == null) {
            // Closed, unknown, or another tenant's. Cash cannot move through a till that is not
            // open — that is the whole point of opening and closing one.
            throw new TransferService.TransferRefused(ErrorCode.TILL_NOT_OPEN);
        }
        if (!till.currency().equals(command.currency())) {
            throw new TransferService.TransferRefused(ErrorCode.CURRENCY_MISMATCH);
        }

        ProductDecision decision =
                products.evaluate(
                        new ProductRequest(
                                command.tenantId(),
                                command.productCode(),
                                command.operation() == CashCommand.Operation.DEPOSIT
                                        ? ProductRequest.Operation.DEPOSIT
                                        : ProductRequest.Operation.WITHDRAWAL,
                                eligibility.kycTier(),
                                command.channel(),
                                command.amountMinor(),
                                command.currency()));
        if (!decision.permitted()) {
            throw TransferService.refusalOf(decision);
        }
        if (command.operation() == CashCommand.Operation.DEPOSIT
                && decision.feeMinor() >= command.amountMinor()) {
            // The customer would be credited nothing, or less than nothing. That is a product
            // misconfiguration rather than a transaction, and it should say so instead of failing
            // downstream on a zero-amount entry.
            throw new TransferService.TransferRefused(ErrorCode.FEE_EXCEEDS_DEPOSIT);
        }

        UUID sagaId =
                sagas.openCash(
                        command, decision, till, eligibility.kycTier(),
                        "daily:" + LocalDate.now(command.businessZone()));

        // ---- Phase B ---------------------------------------------------------
        LedgerOutcome outcome =
                ledger.post(
                        command.tenantId(),
                        postingFor(command, decision, till, IdempotencyKeys.forStep(sagaId, POST_STEP)));

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

    /**
     * The entries.
     *
     * <p>The fee is taken out of the credited amount on a deposit and added to the debited amount on
     * a withdrawal, rather than posted as a second entry against the customer. That is not a
     * stylistic choice: the Ledger rejects a transaction where one account appears on both the
     * debit and the credit side, so a customer credited the principal and debited the fee would be
     * a wash transaction and refused outright.
     */
    private LedgerPosting postingFor(
            CashCommand command, ProductDecision decision, TillRecords.Till till, String key) {

        long amount = command.amountMinor();
        long fee = decision.feeMinor();

        List<LedgerPosting.Entry> entries;
        if (command.operation() == CashCommand.Operation.DEPOSIT) {
            // Cash in: the till takes the notes, the customer is credited what is left after the fee.
            entries =
                    new java.util.ArrayList<>(
                            List.of(
                                    entry(till.ledgerAccountId(), LedgerPosting.Direction.DEBIT, amount, command),
                                    entry(command.customerAccountId(), LedgerPosting.Direction.CREDIT, amount - fee, command)));
        } else {
            // Cash out: the customer is debited the amount plus the fee, the till pays out the amount.
            entries =
                    new java.util.ArrayList<>(
                            List.of(
                                    entry(command.customerAccountId(), LedgerPosting.Direction.DEBIT, amount + fee, command),
                                    entry(till.ledgerAccountId(), LedgerPosting.Direction.CREDIT, amount, command)));
        }
        if (fee > 0) {
            // Configuration first, caller fallback — same resolution the saga row records.
            entries.add(
                    entry(
                            decision.feeAccountId() != null ? decision.feeAccountId() : command.feeAccountId(),
                            LedgerPosting.Direction.CREDIT,
                            fee,
                            command));
        }
        return new LedgerPosting(key, command.initiatedBy(), command.description(), entries);
    }

    private static LedgerPosting.Entry entry(
            UUID accountId, LedgerPosting.Direction direction, long amountMinor, CashCommand command) {
        return new LedgerPosting.Entry(accountId, direction, amountMinor, command.currency());
    }
}
