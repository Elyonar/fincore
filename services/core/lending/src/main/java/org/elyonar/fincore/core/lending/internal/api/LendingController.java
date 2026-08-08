package org.elyonar.fincore.core.lending.internal.api;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.elyonar.fincore.auth.Authorization;
import org.elyonar.fincore.core.lending.internal.LoanRecords;
import org.elyonar.fincore.core.lending.internal.LoanService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The lending surface (lending.md §4). Every handler denies by default, the tenant comes from the
 * token, and the caller's organizational scope is snapshotted as attribution — the platform's
 * standing rules, applied to the fifth module.
 */
@Tag(name = "Lending", description = "Origination, disbursement, schedules, repayments and portfolio risk")
@RestController
@RequestMapping("/v1")
public class LendingController {

    private final LoanService loans;
    private final LoanRecords records;

    public LendingController(LoanService loans, LoanRecords records) {
        this.loans = loans;
        this.records = records;
    }

    private static String unitSnapshot() {
        var units = new TreeSet<>(Authorization.units());
        return units.isEmpty() ? null : String.join(",", units);
    }

    @PostMapping("/loan-applications")
    @ResponseStatus(HttpStatus.CREATED)
    public LoanRecords.Application apply(@RequestBody Apply request) {
        var identity = Authorization.require("loans:apply");
        return loans.apply(
                identity.tenantId(),
                request.customerId(),
                request.productCode(),
                request.amountMinor(),
                request.termMonths(),
                request.purpose(),
                Authorization.initiatedBy(),
                unitSnapshot());
    }

    @GetMapping("/loan-applications/{id}")
    public LoanRecords.Application application(@PathVariable UUID id) {
        var identity = Authorization.require("loans:read");
        return loans.require(identity.tenantId(), id);
    }

    /** One signature in the tiered chain. The approver is the token, never the body. */
    @PostMapping("/loan-applications/{id}/approve")
    public LoanRecords.Application approve(@PathVariable UUID id) {
        var identity = Authorization.require("loans:approve");
        return loans.approve(identity.tenantId(), id, Authorization.initiatedBy(), unitSnapshot());
    }

    @PostMapping("/loan-applications/{id}/reject")
    public LoanRecords.Application reject(@PathVariable UUID id, @RequestBody Reject request) {
        var identity = Authorization.require("loans:approve");
        return loans.reject(identity.tenantId(), id, request.reason());
    }

    @PostMapping("/loan-applications/{id}/accept-offer")
    public LoanRecords.Application acceptOffer(@PathVariable UUID id) {
        var identity = Authorization.require("loans:offer");
        return loans.acceptOffer(identity.tenantId(), id, Authorization.initiatedBy());
    }

    /** Opens the funding saga. Idempotent per application; an unknown outcome is a 503, same request. */
    @PostMapping("/loan-applications/{id}/disburse")
    public LoanRecords.Application disburse(@PathVariable UUID id, @RequestBody Disburse request) {
        var identity = Authorization.require("loans:disburse");
        return loans.disburse(
                identity.tenantId(),
                id,
                request.fundingAccountId(),
                request.destinationAccountId(),
                Authorization.initiatedBy(),
                identity.serviceIdentity() == null ? "core" : identity.serviceIdentity());
    }

    @GetMapping("/loans/{id}")
    public Map<String, Object> loan(@PathVariable UUID id) {
        var identity = Authorization.require("loans:read");
        LoanRecords.Loan loan = loans.requireLoan(identity.tenantId(), id);
        var view = new java.util.LinkedHashMap<String, Object>();
        view.put("loanId", loan.id().toString());
        view.put("applicationId", loan.applicationId().toString());
        view.put("state", loan.state());
        view.put("bucket", loan.currentBucket());
        view.put("principalMinor", Long.toString(loan.principalMinor()));
        view.put("principalOutstandingMinor", Long.toString(loan.principalOutstandingMinor()));
        view.put("accruedInterestMinor", Long.toString(loan.accruedInterestMinor()));
        view.put("payoffMinor", Long.toString(loan.principalOutstandingMinor() + loan.accruedInterestMinor()));
        view.put("currency", loan.currency());
        view.put("productCode", loan.productCode());
        view.put("productVersion", loan.productVersion());
        return view;
    }

    @GetMapping("/loans/{id}/schedule")
    public List<Map<String, Object>> schedule(@PathVariable UUID id) {
        var identity = Authorization.require("loans:read");
        loans.requireLoan(identity.tenantId(), id);
        return records.schedule(identity.tenantId(), id);
    }

    /** Intake opens the repayment saga; allocation follows its completion, exactly once. */
    @PostMapping("/loans/{id}/repayments")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> repay(@PathVariable UUID id, @RequestBody Repay request) {
        var identity = Authorization.require("loans:repay");
        return loans.repay(
                identity.tenantId(),
                id,
                request.amountMinor(),
                request.sourceAccountId(),
                request.idempotencyKey(),
                Authorization.initiatedBy(),
                identity.serviceIdentity() == null ? "core" : identity.serviceIdentity());
    }

    @GetMapping("/portfolio/par")
    public List<Map<String, Object>> portfolioAtRisk() {
        var identity = Authorization.require("loans:portfolio");
        return records.portfolioAtRisk(identity.tenantId());
    }

    /** Tenant configuration: ceiling → approvals required, zero permitted (lending.md §2). */
    @PostMapping("/lending/approval-tiers")
    public List<Map<String, Object>> setTier(@RequestBody Tier request) {
        var identity = Authorization.require("loans:tiers");
        records.setTier(identity.tenantId(), request.ceilingMinor(), request.approvalsRequired());
        return records.tiers(identity.tenantId());
    }

    @GetMapping("/lending/approval-tiers")
    public List<Map<String, Object>> tiers() {
        var identity = Authorization.require("loans:tiers");
        return records.tiers(identity.tenantId());
    }

    public record Apply(
            UUID customerId, String productCode, long amountMinor, int termMonths, String purpose) {}

    public record Reject(String reason) {}

    /**
     * @param fundingAccountId the tenant's loan funding account — operational reference like a
     *     till's; validated ownership arrives with account metadata on the ledger read
     */
    public record Disburse(UUID fundingAccountId, UUID destinationAccountId) {}

    public record Repay(String idempotencyKey, long amountMinor, UUID sourceAccountId) {}

    public record Tier(long ceilingMinor, int approvalsRequired) {}
}
