package org.elyonar.fincore.core.lending.internal;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.elyonar.fincore.core.customer.api.CustomerEligibility;
import org.elyonar.fincore.core.customer.api.EligibilityResult;
import org.elyonar.fincore.core.lending.api.LendingErrorCode;
import org.elyonar.fincore.core.lending.internal.LoanRecords.Allocation;
import org.elyonar.fincore.core.lending.internal.LoanRecords.Application;
import org.elyonar.fincore.core.lending.internal.LoanRecords.Loan;
import org.elyonar.fincore.core.orchestration.api.FundingCommand;
import org.elyonar.fincore.core.orchestration.api.MoneyMovements;
import org.elyonar.fincore.core.orchestration.api.TransferResult;
import org.elyonar.fincore.core.product.api.LoanProducts;
import org.springframework.stereotype.Service;

/**
 * The lending lifecycle (lending.md §2): origination through closure, with money moving at
 * exactly one kind of edge — a funding saga through Orchestration's published surface.
 *
 * <p>Every refusal is a typed exception the module's advice maps; every uncertain outcome
 * surfaces as the saga's own {@code OutcomeUnknown} (503, retry the same key), because Lending
 * deliberately owns no second copy of the outcome protocol.
 */
@Service
public class LoanService {

    /** Offers hold for 14 days. Product-configurable when a tenant asks; a constant until then. */
    private static final int OFFER_DAYS = 14;

    private final LoanRecords records;
    private final CustomerEligibility customers;
    private final LoanProducts products;
    private final MoneyMovements movements;

    public LoanService(
            LoanRecords records, CustomerEligibility customers, LoanProducts products, MoneyMovements movements) {
        this.records = records;
        this.customers = customers;
        this.products = products;
        this.movements = movements;
    }

    // ---------------------------------------------------------------- origination

    public Application apply(
            UUID tenantId, UUID customerId, String productCode, long amountMinor, int termMonths,
            String purpose, String appliedBy, String appliedInUnit) {
        EligibilityResult eligibility = customers.check(tenantId, customerId);
        if (!eligibility.eligible()) {
            throw new Refused(LendingErrorCode.LOAN_NOT_FOUND, "customer not eligible");
        }
        LoanProducts.LoanTerms terms = products.termsFor(tenantId, productCode);
        if (terms == null) {
            throw new Refused(LendingErrorCode.PRODUCT_NOT_LENDABLE, productCode);
        }
        if (amountMinor < terms.minAmountMinor() || amountMinor > terms.maxAmountMinor()) {
            throw new Refused(LendingErrorCode.AMOUNT_OUT_OF_BOUNDS, "amount outside product bounds");
        }
        if (termMonths < terms.minTermMonths() || termMonths > terms.maxTermMonths()) {
            throw new Refused(LendingErrorCode.TERM_OUT_OF_BOUNDS, "term outside product bounds");
        }

        int required = records.requiredApprovals(tenantId, amountMinor);
        UUID id =
                records.createApplication(
                        tenantId, customerId, productCode, terms.version(), amountMinor, termMonths,
                        terms.currency(), purpose, required, appliedBy, appliedInUnit);

        if (required == 0) {
            // The zero tier: the deterministic policy approves, attributed — "nobody approved"
            // and "the policy approved" must never read alike (lending.md §2).
            records.recordPolicyApproval(tenantId, id);
            offer(tenantId, id, amountMinor, termMonths, terms);
        }
        return records.application(tenantId, id);
    }

    public Application approve(UUID tenantId, UUID applicationId, String approvedBy, String approvedInUnit) {
        Application app = require(tenantId, applicationId);
        if (!"APPLIED".equals(app.state())) {
            throw new Refused(LendingErrorCode.APPLICATION_STATE_INVALID, "not awaiting approval");
        }
        int count = records.approve(tenantId, applicationId, approvedBy, approvedInUnit);
        if (count < 0) {
            // Duplicate signer, the applicant signing, or a state that moved — one refusal, per
            // the catalog; naming which would map the control for a prober.
            throw new Refused(LendingErrorCode.APPROVAL_SEQUENCE_INVALID, "signature refused");
        }
        if (count >= app.approvalsRequired()) {
            LoanProducts.LoanTerms terms = products.termsFor(tenantId, app.productCode());
            if (terms != null) {
                offer(tenantId, applicationId, app.amountMinor(), app.termMonths(), terms);
            }
        }
        return records.application(tenantId, applicationId);
    }

    private void offer(UUID tenantId, UUID applicationId, long amount, int term, LoanProducts.LoanTerms terms) {
        List<ScheduleEngine.Installment> preview =
                ScheduleEngine.generate(
                        terms.scheduleKind(), amount, terms.interestRateBp(), term, terms.graceMonths(),
                        LocalDate.now(ZoneOffset.UTC));
        long totalInterest = ScheduleEngine.totalInterest(preview);
        records.transition(tenantId, applicationId, "APPLIED", "APPROVED");
        records.recordOffer(
                tenantId, applicationId, "APPROVED", totalInterest, amount + totalInterest,
                ScheduleEngine.effectiveAnnualRateBp(amount, totalInterest, term),
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(OFFER_DAYS));
    }

    public Application reject(UUID tenantId, UUID applicationId, String reason) {
        Application app = require(tenantId, applicationId);
        boolean rejectable =
                "APPLIED".equals(app.state()) || "APPROVED".equals(app.state()) || "OFFERED".equals(app.state());
        if (!rejectable || !records.transition(tenantId, applicationId, app.state(), "REJECTED")) {
            throw new Refused(LendingErrorCode.APPLICATION_STATE_INVALID, "not rejectable from " + app.state());
        }
        return records.application(tenantId, applicationId);
    }

    public Application acceptOffer(UUID tenantId, UUID applicationId, String acceptedBy) {
        Application app = require(tenantId, applicationId);
        if (!"OFFERED".equals(app.state())) {
            throw new Refused(LendingErrorCode.APPLICATION_STATE_INVALID, "no offer to accept");
        }
        if (app.offerExpiresAt() != null && app.offerExpiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            records.transition(tenantId, applicationId, "OFFERED", "EXPIRED");
            throw new Refused(LendingErrorCode.OFFER_EXPIRED, "offer expired");
        }
        records.transition(tenantId, applicationId, "OFFERED", "ACCEPTED");
        return records.application(tenantId, applicationId);
    }

    // ---------------------------------------------------------------- disbursement

    /**
     * The one edge where money moves. Idempotent per application: the funding saga's key derives
     * from the application id, so a retry — caller or worker — converges on the same movement.
     */
    public Application disburse(
            UUID tenantId, UUID applicationId, UUID fundingAccountId, UUID destinationAccountId,
            String initiatedBy, String executedBy) {
        Application app = require(tenantId, applicationId);
        if ("DISBURSING".equals(app.state())) {
            return converge(tenantId, app); // the retry path — ask the saga, don't guess
        }
        if (!"ACCEPTED".equals(app.state())) {
            if ("ACTIVE".equals(app.state())) {
                return app; // already done; replay answers
            }
            throw new Refused(LendingErrorCode.APPLICATION_STATE_INVALID, "not accepted");
        }
        // Configuration-first (v1.17): the product's funding account overrides the caller's,
        // which stays only as the fallback for versions predating the column — the fee-account
        // pattern. Resolved before DISBURSING is recorded, so retries and the convergence job
        // re-drive from the same resolved account.
        LoanProducts.LoanTerms disburseTerms = products.termsFor(tenantId, app.productCode());
        if (disburseTerms != null && disburseTerms.fundingAccountId() != null) {
            fundingAccountId = disburseTerms.fundingAccountId();
        }
        records.setDisbursing(tenantId, applicationId, null, fundingAccountId, destinationAccountId);

        TransferResult outcome;
        try {
            outcome =
                    movements.fund(
                            new FundingCommand(
                                    tenantId,
                                    FundingCommand.Kind.DISBURSEMENT,
                                    "lending:disburse:" + applicationId,
                                    null,
                                    fundingAccountId,
                                    destinationAccountId,
                                    app.amountMinor(),
                                    app.currency(),
                                    "loan disbursement " + applicationId,
                                    initiatedBy,
                                    executedBy));
        } catch (org.elyonar.fincore.core.orchestration.api.CoreException refused) {
            // Provably did not happen: the application returns to ACCEPTED and the refusal
            // travels to the caller unchanged.
            records.backToAccepted(tenantId, applicationId, refused.errorCode().name());
            throw refused;
        } catch (RuntimeException unknown) {
            // The saga's OutcomeUnknown (or a transport surprise): DISBURSING stands, the derived
            // key converges — caller retry or the convergence job, whichever comes first.
            throw unknown;
        }
        finalizeDisbursement(tenantId, records.application(tenantId, applicationId), outcome.transactionId());
        return records.application(tenantId, applicationId);
    }

    /** DISBURSING and asked again: read the saga and apply whichever answer it has. */
    private Application converge(UUID tenantId, Application app) {
        TransferResult saga =
                app.disbursementSagaId() == null
                        ? null
                        : movements.status(tenantId, app.disbursementSagaId());
        if (saga == null) {
            // The crash window before the saga id was recorded: re-drive under the derived key.
            return disburseAgain(tenantId, app);
        }
        switch (saga.state()) {
            case "COMPLETED" -> finalizeDisbursement(tenantId, app, saga.transactionId());
            case "FAILED" -> records.backToAccepted(tenantId, app.id(), "disbursement failed");
            default -> throw new PendingDisbursement(app.disbursementSagaId());
        }
        return records.application(tenantId, app.id());
    }

    private Application disburseAgain(UUID tenantId, Application app) {
        TransferResult outcome =
                movements.fund(
                        new FundingCommand(
                                tenantId, FundingCommand.Kind.DISBURSEMENT,
                                "lending:disburse:" + app.id(), null,
                                app.fundingAccountId(), app.destinationAccountId(),
                                app.amountMinor(), app.currency(),
                                "loan disbursement " + app.id(), app.appliedBy(), "core"));
        finalizeDisbursement(tenantId, app, outcome.transactionId());
        return records.application(tenantId, app.id());
    }

    void finalizeDisbursement(UUID tenantId, Application app, UUID sagaId) {
        LoanProducts.LoanTerms terms = products.termsFor(tenantId, app.productCode());
        int rateBp = terms == null ? 0 : terms.interestRateBp();
        String kind = terms == null ? "FLAT" : terms.scheduleKind();
        int grace = terms == null ? 0 : terms.graceMonths();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<ScheduleEngine.Installment> schedule =
                ScheduleEngine.generate(kind, app.amountMinor(), rateBp, app.termMonths(), grace, today);
        // Record the saga id for the audit trail before activation.
        records.setDisbursingSaga(tenantId, app.id(), sagaId);
        records.activate(tenantId, app, rateBp, kind, today, schedule, app.appliedBy(), null);
    }

    // ---------------------------------------------------------------- repayment

    public Map<String, Object> repay(
            UUID tenantId, UUID loanId, long amountMinor, UUID sourceAccountId, String idempotencyKey,
            String initiatedBy, String executedBy) {
        Loan loan = requireLoan(tenantId, loanId);
        if (!"ACTIVE".equals(loan.state())) {
            throw new Refused(LendingErrorCode.LOAN_NOT_ACTIVE, loan.state());
        }
        long payoff =
                loan.principalOutstandingMinor() + loan.accruedInterestMinor() + loan.penaltyDueMinor();
        if (amountMinor > payoff) {
            throw new Refused(
                    LendingErrorCode.REPAYMENT_EXCEEDS_PAYOFF,
                    "payoff is " + payoff + " minor");
        }

        UUID repaymentId =
                records.createRepayment(
                        tenantId, loanId, amountMinor, sourceAccountId, idempotencyKey,
                        LocalDate.now(ZoneOffset.UTC));
        LoanRecords.Repayment repayment =
                repaymentId == null
                        ? records.repaymentByKey(tenantId, idempotencyKey) // replay
                        : new LoanRecords.Repayment(repaymentId, loanId, amountMinor, sourceAccountId, null, "PENDING");
        if ("ALLOCATED".equals(repayment.state())) {
            return Map.of("repaymentId", repayment.id().toString(), "state", "ALLOCATED");
        }

        TransferResult outcome =
                movements.fund(
                        new FundingCommand(
                                tenantId, FundingCommand.Kind.REPAYMENT,
                                "lending:repay:" + repayment.id(), loan.customerId(),
                                sourceAccountId, loan.fundingAccountId(), repayment.amountMinor(),
                                loan.currency(), "loan repayment " + loanId, initiatedBy, executedBy));
        records.recordRepaymentSaga(tenantId, repayment.id(), outcome.transactionId());
        allocate(tenantId, repayment.id(), requireLoan(tenantId, loanId), repayment.amountMinor());
        try {
            // Income recognition rides the allocation (lending.md v1.17) — best-effort inline;
            // a failure here changes nothing the daily catch-up can't converge under the same keys.
            recognize(tenantId, repayment.id());
        } catch (RuntimeException deferred) {
            // The catch-up pass owns it now.
        }
        return Map.of("repaymentId", repayment.id().toString(), "state", "ALLOCATED");
    }

    /**
     * Income recognition for one allocated repayment (lending.md v1.17): the interest portion to
     * the product's interest income account, the penalty portion to the penalty income account
     * (falling back to the interest one) — each as a {@code RECOGNITION} funding saga under a key
     * derived from the repayment, replay-stable because the portions were fixed at allocation.
     * Versions without an income account resolve as an explicit no-op: marked, so the gap in the
     * books is a recorded fact that ages out on republish, and the pending gauge stays honest.
     */
    public void recognize(UUID tenantId, UUID repaymentId) {
        LoanRecords.RecognitionCandidate candidate = records.recognitionCandidate(tenantId, repaymentId);
        if (candidate == null || candidate.recognized() || !"ALLOCATED".equals(candidate.state())) {
            return; // nothing to do, or another path already converged it
        }
        Loan loan = records.loan(tenantId, candidate.loanId());
        if (loan == null) {
            return;
        }
        LoanProducts.LoanTerms terms = products.termsFor(tenantId, loan.productCode());
        UUID interestAccount = terms == null ? null : terms.interestIncomeAccountId();
        UUID penaltyAccount =
                terms == null
                        ? null
                        : terms.penaltyIncomeAccountId() != null
                                ? terms.penaltyIncomeAccountId()
                                : terms.interestIncomeAccountId();

        long postedInterest = 0;
        if (candidate.interestMinor() > 0 && interestAccount != null) {
            movements.fund(
                    new FundingCommand(
                            tenantId, FundingCommand.Kind.RECOGNITION,
                            "lending:recognize:" + repaymentId + ":interest", null,
                            loan.fundingAccountId(), interestAccount, candidate.interestMinor(),
                            loan.currency(), "interest recognition " + loan.id(),
                            "system:lending-recognition", "core"));
            postedInterest = candidate.interestMinor();
        }
        if (candidate.penaltyMinor() > 0 && penaltyAccount != null) {
            movements.fund(
                    new FundingCommand(
                            tenantId, FundingCommand.Kind.RECOGNITION,
                            "lending:recognize:" + repaymentId + ":penalty", null,
                            loan.fundingAccountId(), penaltyAccount, candidate.penaltyMinor(),
                            loan.currency(), "penalty recognition " + loan.id(),
                            "system:lending-recognition", "core"));
        }
        records.markRecognized(tenantId, repaymentId, candidate.loanId(), postedInterest);
    }

    /**
     * The allocation as a pure computation over the loan's current dues, applied whole. Order per
     * the product; penalties are live dues since v1.17 (charged minus paid), fees still have no
     * dues, and the engine skips components with nothing due rather than special-casing the
     * vocabulary.
     */
    void allocate(UUID tenantId, UUID repaymentId, Loan loan, long amountMinor) {
        LoanProducts.LoanTerms terms = products.termsFor(tenantId, loan.productCode());
        List<String> order =
                terms == null ? List.of("PENALTY", "FEE", "INTEREST", "PRINCIPAL") : terms.allocationOrder();

        long remaining = amountMinor;
        long interestAllocated = 0;
        long principalAllocated = 0;
        long penaltyAllocated = 0;
        List<Allocation.Component> components = new ArrayList<>();
        List<Allocation.InstallmentUpdate> updates = new ArrayList<>();

        List<Map<String, Object>> schedule = records.schedule(tenantId, loan.id());
        for (String component : order) {
            if (remaining == 0) {
                break;
            }
            switch (component) {
                case "PENALTY" -> {
                    long due = loan.penaltyDueMinor();
                    long take = Math.min(remaining, due);
                    if (take > 0) {
                        penaltyAllocated = take;
                        remaining -= take;
                        components.add(new Allocation.Component("PENALTY", take, null));
                        // No installment spread: penalties are a loan-level due, not schedule rows.
                    }
                }
                case "INTEREST" -> {
                    long due = loan.accruedInterestMinor();
                    long take = Math.min(remaining, due);
                    if (take > 0) {
                        interestAllocated = take;
                        remaining -= take;
                        components.add(new Allocation.Component("INTEREST", take, null));
                        updates.addAll(spread(schedule, take, false));
                    }
                }
                case "PRINCIPAL" -> {
                    long due = loan.principalOutstandingMinor();
                    long take = Math.min(remaining, due);
                    if (take > 0) {
                        principalAllocated = take;
                        remaining -= take;
                        components.add(new Allocation.Component("PRINCIPAL", take, null));
                        updates.addAll(spread(schedule, take, true));
                    }
                }
                default -> {
                    // FEE has no dues in v1; the vocabulary is ready for it.
                }
            }
        }

        boolean closes =
                principalAllocated == loan.principalOutstandingMinor()
                        && interestAllocated == loan.accruedInterestMinor()
                        && penaltyAllocated == loan.penaltyDueMinor();
        records.allocate(
                tenantId, repaymentId,
                new Allocation(
                        loan.id(), principalAllocated, interestAllocated, penaltyAllocated, closes,
                        components, updates));
    }

    /** Oldest-first across unsettled installments, capped by each row's remaining due. */
    private static List<Allocation.InstallmentUpdate> spread(
            List<Map<String, Object>> schedule, long amount, boolean principal) {
        List<Allocation.InstallmentUpdate> updates = new ArrayList<>();
        long remaining = amount;
        for (Map<String, Object> row : schedule) {
            if (remaining == 0) {
                break;
            }
            long due =
                    Long.parseLong((String) row.get(principal ? "principalDueMinor" : "interestDueMinor"))
                            - Long.parseLong((String) row.get(principal ? "principalPaidMinor" : "interestPaidMinor"));
            long take = Math.min(remaining, due);
            if (take > 0) {
                int no = (int) row.get("installmentNo");
                updates.add(
                        principal
                                ? new Allocation.InstallmentUpdate(no, take, 0)
                                : new Allocation.InstallmentUpdate(no, 0, take));
                remaining -= take;
            }
        }
        return updates;
    }

    // ---------------------------------------------------------------- reads

    public Application require(UUID tenantId, UUID applicationId) {
        Application app = records.application(tenantId, applicationId);
        if (app == null) {
            throw new NotFound();
        }
        return app;
    }

    public Loan requireLoan(UUID tenantId, UUID loanId) {
        Loan loan = records.loan(tenantId, loanId);
        if (loan == null) {
            throw new NotFound();
        }
        return loan;
    }

    // ---------------------------------------------------------------- refusals

    /** Absent, or another tenant's. One shape for both. */
    public static class NotFound extends RuntimeException {}

    /** A catalogued refusal; the advice maps the code. */
    public static class Refused extends RuntimeException {
        private final LendingErrorCode code;

        public Refused(LendingErrorCode code, String message) {
            super(message);
            this.code = code;
        }

        public LendingErrorCode code() {
            return code;
        }
    }

    /** The disbursement saga is still undetermined: 503, retry the same request. */
    public static class PendingDisbursement extends RuntimeException {
        private final UUID sagaId;

        public PendingDisbursement(UUID sagaId) {
            super("disbursement outcome unknown");
            this.sagaId = sagaId;
        }

        public UUID sagaId() {
            return sagaId;
        }
    }
}
