package org.elyonar.fincore.core.orchestration.internal.saga;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.elyonar.fincore.core.orchestration.api.CustomerEligibility;
import org.elyonar.fincore.core.orchestration.api.EligibilityResult;
import org.elyonar.fincore.core.orchestration.api.CoreException;
import org.elyonar.fincore.core.orchestration.api.LedgerOutcome;
import org.elyonar.fincore.core.orchestration.api.LedgerPosting;
import org.elyonar.fincore.core.orchestration.api.TransferCommand;
import org.elyonar.fincore.core.orchestration.api.TransferResult;
import org.elyonar.fincore.core.orchestration.internal.ledger.LedgerClient;
import org.elyonar.fincore.core.orchestration.api.ProductDecision;
import org.elyonar.fincore.core.orchestration.api.ProductDecisions;
import org.elyonar.fincore.core.orchestration.api.ProductRequest;
import org.springframework.stereotype.Service;
import org.elyonar.fincore.core.orchestration.api.DetailKey;
import org.elyonar.fincore.core.orchestration.api.ErrorCode;

/**
 * A transfer, executed in the three phases {@code saga-protocol.md} specifies.
 *
 * <p>The shape is the point. Phase A decides and persists in one local transaction; Phase B calls
 * the Ledger holding <em>no</em> transaction; Phase C records the outcome in another. A transaction
 * spanning the network call would hold locks and a pooled connection for the duration of someone
 * else's outage.
 */
@Service
public class TransferService {

    private final SagaRecords sagas;
    private final CustomerEligibility customers;
    private final ProductDecisions products;
    private final LedgerClient ledger;

    public TransferService(
            SagaRecords sagas,
            CustomerEligibility customers,
            ProductDecisions products,
            LedgerClient ledger) {
        this.sagas = sagas;
        this.customers = customers;
        this.products = products;
        this.ledger = ledger;
    }

    public TransferResult transfer(TransferCommand command) {
        // ---- replay ---------------------------------------------------------
        // Before anything else: has this key been seen? A replay returns the original result, and
        // the same key with different economics is a loud 409 rather than a silent wrong answer.
        SagaRecords.Existing existing = sagas.findByKey(command.tenantId(), command.idempotencyKey());
        if (existing != null) {
            if (!existing.fingerprint().equals(command.fingerprint())) {
                throw new IdempotencyKeyReused(command.idempotencyKey());
            }
            return existing.result();
        }

        // ---- Phase A: decide and persist, one local transaction --------------
        EligibilityResult eligibility = customers.check(command.tenantId(), command.customerId());
        if (!eligibility.eligible()) {
            throw new TransferRefused(
                    eligibility.reason() == EligibilityResult.Reason.NOT_FOUND
                            ? ErrorCode.CUSTOMER_NOT_FOUND
                            : ErrorCode.CUSTOMER_NOT_ACTIVE);
        }
        /*
         * The product comes from the paying account, never from the request.
         *
         * It used to be `command.productCode()` — whatever the caller put in the body — which is the
         * hole the cash path closed and this one kept: the product decides the fee charged and the
         * limits applied, so a caller able to name it is a caller choosing which rules judge its own
         * transaction. Naming a cheaper product, or one with a higher ceiling, was a body field
         * away. The Cash path resolves it from the account and accepts-and-ignores the field; a
         * transfer is judged the same way now.
         *
         * The practical effect was worse than theoretical: the portal sends no product code at all,
         * because there is nothing legitimate for it to send — so every transfer from the counter
         * was refused with PRODUCT_NOT_FOUND while deposits on the same account went through.
         *
         * One read answers both questions. Null means either that the customer does not hold the
         * paying account or that the account predates the column; both refuse, the second with its
         * own code, because "you do not hold this" would be a lie about a real account.
         */
        String productCode =
                customers.productOfHeldAccount(
                        command.tenantId(), command.customerId(), command.fromAccountId());
        if (productCode == null) {
            throw new TransferRefused(
                    customers.holdsAccount(command.tenantId(), command.customerId(), command.fromAccountId())
                            ? ErrorCode.ACCOUNT_HAS_NO_PRODUCT
                            : ErrorCode.ACCOUNT_NOT_LINKED);
        }

        ProductDecision decision =
                products.evaluate(
                        new ProductRequest(
                                command.tenantId(),
                                productCode,
                                ProductRequest.Operation.TRANSFER,
                                eligibility.kycTier(),
                                command.channel(),
                                command.amountMinor(),
                                command.currency()));
        if (!decision.permitted()) {
            throw refusalOf(decision);
        }

        UUID sagaId =
                sagas.open(
                        command,
                        decision,
                        eligibility.kycTier(),
                        productCode,
                        // Calendar day in the tenant's business timezone — regulatory tier limits
                        // mean calendar days, and a customer understands a limit that resets at
                        // midnight (design.md).
                        "daily:" + LocalDate.now(command.businessZone()));

        // ---- Phase B: call the Ledger, holding no transaction ----------------
        String key = IdempotencyKeys.forStep(sagaId, IdempotencyKeys.POST_STEP);
        LedgerOutcome outcome = ledger.post(command.tenantId(), postingFor(command, decision, key));

        // ---- Phase C: record what happened, one local transaction ------------
        return switch (outcome) {
            case LedgerOutcome.Success success -> sagas.complete(command.tenantId(), sagaId, success.ledgerTransactionId());

            case LedgerOutcome.DefiniteFailure failure -> {
                // Provably did not happen, so the reservation is released and the saga fails. This
                // is the only outcome from which compensation is legal.
                sagas.fail(command.tenantId(), sagaId, failure.errorCode());
                throw TransferRefused.fromLedger(failure.errorCode());
            }

            // Not known, and possibly true. The reservation is untouched, the saga stays claimable,
            // and the caller is told 5xx — which under Core's own retry rule obliges it to retry
            // the same key until the answer is definitive. Never compensate here.
            case LedgerOutcome.Unknown unknown -> {
                sagas.recordUnknownAttempt(command.tenantId(), sagaId, unknown.reason());
                throw new OutcomeUnknown(sagaId, unknown.reason());
            }
        };
    }

    /**
     * Principal and fee as entries of one transaction.
     *
     * <p>Debit the sender the principal; credit the recipient the principal; debit the sender the
     * fee; credit fee income the fee. Balanced by construction, atomic for free, and reversing the
     * transfer reverses its fee — which is what a customer and a regulator both expect.
     *
     * <p><strong>The fee is its own entry.</strong> It used to be folded into the sender's debit, so
     * a ₦2,500 transfer charged ₦50 appeared on their statement as a single ₦2,550 debit with
     * nothing to say why. The books balanced either way; what did not work was the document the
     * customer is handed. A charge they cannot see is a charge they cannot query.
     */
    private LedgerPosting postingFor(TransferCommand command, ProductDecision decision, String key) {
        var entries =
                Postings.entriesFor(
                        "TRANSFER",
                        command.fromAccountId(),
                        command.toAccountId(),
                        decision.feeAccountId(),
                        command.amountMinor(),
                        decision.feeMinor(),
                        command.currency());
        return new LedgerPosting(key, command.initiatedBy(), command.description(), entries);
    }

    /**
     * A product refusal as a Core error, with the reason attached where one code spans causes:
     * {@code LIMIT_EXCEEDED} here always means the per-transaction limit — the daily window is
     * enforced later, against the day's reservations, and carries {@code DAILY_LIMIT}.
     */
    static TransferRefused refusalOf(ProductDecision decision) {
        ErrorCode code = ErrorCode.valueOf(decision.refusal().name());
        if (decision.refusal() == ProductDecision.Refusal.LIMIT_EXCEEDED) {
            return new TransferRefused(
                    code,
                    org.elyonar.fincore.core.orchestration.api.ErrorReason.PER_TRANSACTION_LIMIT,
                    "the amount exceeds the per-transaction limit",
                    java.util.Map.of());
        }
        return new TransferRefused(code);
    }

    /** Same key, different economics. A caller bug, and never answered with a silent success. */
    public static class IdempotencyKeyReused extends RuntimeException {
        public IdempotencyKeyReused(String key) {
            super("idempotency key reused with a different payload: " + key);
        }
    }

    /** A definite refusal. Terminal for this key; a new logical attempt mints a new one. */
    public static class TransferRefused extends CoreException {

        public TransferRefused(ErrorCode code) {
            this(code, null, code.name(), java.util.Map.of());
        }

        public TransferRefused(ErrorCode code, String reason, String message, java.util.Map<String, String> details) {
            super(code, reason, message, details);
        }

        /**
         * A refusal the Ledger decided.
         *
         * <p>The Ledger's code is mapped onto Core's catalog rather than forwarded, and travels in
         * {@code details} for an operator. Forwarding it would put codes in Core's responses that
         * Core's own {@code api.md} does not document, which is a contract nobody agreed to.
         */
        public static TransferRefused fromLedger(String ledgerCode) {
            return new TransferRefused(
                    ErrorCode.fromLedger(ledgerCode),
                    null,
                    "the Ledger refused: " + ledgerCode,
                    ledgerCode == null ? java.util.Map.of() : java.util.Map.of(DetailKey.LEDGER_CODE, ledgerCode));
        }

        public String code() {
            return errorCode().name();
        }
    }

    /** The outcome is not known. The caller must retry the same key. */
    public static class OutcomeUnknown extends RuntimeException {
        private final UUID transactionId;

        public OutcomeUnknown(UUID transactionId, String reason) {
            super("outcome unknown: " + reason);
            this.transactionId = transactionId;
        }

        public UUID transactionId() {
            return transactionId;
        }
    }
}
