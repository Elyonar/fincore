package org.elyonar.fincore.core.app.pricing;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.elyonar.fincore.auth.Authorization;
import org.elyonar.fincore.core.orchestration.api.InstitutionAccounts;
import org.elyonar.fincore.core.product.api.ProductAuthoring;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Product pricing (admin-surface §3) — fees, limits and loan terms.
 *
 * <p>The gap this closes was the widest one on the platform. All three rule tables have been fully
 * modelled and constrained since V2 and V5, and written by nothing outside the test suite. That is
 * worse than an unpriced product, because the limit evaluator denies by default: with no PER_TXN
 * rule a published product refuses every transaction. An institution could create a product,
 * publish it with a second signature, and still not take a deposit.
 *
 * <p><b>Why this lives in {@code app}.</b> The tables belong to Product and the accounts a rule
 * points at belong to Orchestration, which owns the ledger client. Product may not reach across
 * (ADR 0006), so the composition happens here — the same reason the staff administration surface
 * sits in this module rather than inside the directory.
 *
 * <p>And that composition is the point rather than plumbing. Every account named by a rule is
 * checked to be one the institution actually opened, of the right purpose, in the right currency.
 * {@code V4__fee_account_configuration.sql} was written to stop a caller routing the tenant's fee
 * income to any account it could name, and could not finish the job while nothing could write the
 * column. This finishes it.
 *
 * <p><b>Not maker-checked, and publishing still is.</b> Editing a draft changes nothing a customer
 * can be charged under: a draft prices nobody. The second signature belongs where it already is —
 * on {@code publish}, enforced by the database, which refuses a publisher who is the author.
 */
@Tag(name = "Pricing", description = "Fee, limit and loan rules per product version")
@RestController
@RequestMapping("/v1/products/{productId}/versions")
public class PricingController {

    private final ProductAuthoring authoring;
    private final InstitutionAccounts accounts;

    public PricingController(ProductAuthoring authoring, InstitutionAccounts accounts) {
        this.authoring = authoring;
        this.accounts = accounts;
    }

    /**
     * Drafts the next version.
     *
     * <p>{@code copyFrom} is the ordinary path: a repricing changes one number and keeps the rest,
     * and retyping the rest is how the rest changes by accident.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> draft(@PathVariable UUID productId, @RequestBody(required = false) Draft request) {
        var identity = Authorization.require("products:create");
        Integer copyFrom = request == null ? null : request.copyFrom();
        int version = authoring.draftNextVersion(identity.tenantId(), productId, copyFrom);
        return Map.of("version", version, "status", "DRAFT");
    }

    /** One version and everything priced under it. */
    @GetMapping("/{version}")
    public ProductAuthoring.VersionDetail read(@PathVariable UUID productId, @PathVariable int version) {
        var identity = Authorization.require("products:read");
        ProductAuthoring.VersionDetail found = authoring.read(identity.tenantId(), productId, version);
        if (found == null) {
            throw new ProductAuthoring.NoSuchVersion();
        }
        return found;
    }

    /** Replaces the draft's fee schedule. */
    @PutMapping("/{version}/fee-rules")
    public ProductAuthoring.VersionDetail setFeeRules(
            @PathVariable UUID productId, @PathVariable int version, @RequestBody FeeRules request) {
        var identity = Authorization.require("products:create");

        List<ProductAuthoring.FeeRule> rules = request.rules() == null ? List.of() : request.rules();
        for (ProductAuthoring.FeeRule rule : rules) {
            requireAccount(identity.tenantId(), rule.feeAccountId(), "FEE_INCOME", rule.currency(), "feeAccountId");
        }
        authoring.setFeeRules(identity.tenantId(), productId, version, rules);
        return authoring.read(identity.tenantId(), productId, version);
    }

    /** Replaces the draft's limits. Without a PER_TXN rule the product refuses everything. */
    @PutMapping("/{version}/limit-rules")
    public ProductAuthoring.VersionDetail setLimitRules(
            @PathVariable UUID productId, @PathVariable int version, @RequestBody LimitRules request) {
        var identity = Authorization.require("products:create");
        authoring.setLimitRules(
                identity.tenantId(), productId, version, request.rules() == null ? List.of() : request.rules());
        return authoring.read(identity.tenantId(), productId, version);
    }

    /** Replaces the draft's loan terms. */
    @PutMapping("/{version}/loan-rules")
    public ProductAuthoring.VersionDetail setLoanRules(
            @PathVariable UUID productId, @PathVariable int version, @RequestBody ProductAuthoring.LoanRule rule) {
        var identity = Authorization.require("products:create");

        if (rule != null) {
            requireAccount(
                    identity.tenantId(),
                    rule.interestIncomeAccountId(),
                    "INTEREST_INCOME",
                    rule.currency(),
                    "interestIncomeAccountId");
            requireAccount(
                    identity.tenantId(),
                    rule.fundingAccountId(),
                    "LOAN_FUNDING",
                    rule.currency(),
                    "fundingAccountId");
            // Optional: null means penalties are recognised where interest is, which the lending
            // module already does. Only checked when the institution named a separate account.
            if (rule.penaltyIncomeAccountId() != null) {
                requireAccount(
                        identity.tenantId(),
                        rule.penaltyIncomeAccountId(),
                        "PENALTY_INCOME",
                        rule.currency(),
                        "penaltyIncomeAccountId");
            }
        }
        authoring.setLoanRule(identity.tenantId(), productId, version, rule);
        return authoring.read(identity.tenantId(), productId, version);
    }

    /** Schedules when the version becomes live once published. */
    @PatchMapping("/{version}")
    public ProductAuthoring.VersionDetail schedule(
            @PathVariable UUID productId, @PathVariable int version, @RequestBody Schedule request) {
        var identity = Authorization.require("products:create");
        authoring.setEffectiveFrom(identity.tenantId(), productId, version, request.effectiveFrom());
        return authoring.read(identity.tenantId(), productId, version);
    }

    /**
     * The account exists, is the institution's, is for this, is active, and is in this currency.
     *
     * <p>All five in one refusal on purpose: an administrator who named the wrong account wants to
     * be told the account is wrong, not to discover a second problem after fixing the first.
     */
    private void requireAccount(UUID tenantId, UUID accountId, String purpose, String currency, String field) {
        if (accountId == null) {
            throw new PricingRefused(field + " is required — a rule with no account has nowhere to post");
        }
        InstitutionAccounts.Account account = accounts.byLedgerAccountId(tenantId, accountId);
        if (account == null) {
            throw new PricingRefused(field + " is not one of this institution's accounts");
        }
        if (!account.active()) {
            throw new PricingRefused(field + " names a closed account: " + account.code());
        }
        if (!purpose.equals(account.purpose())) {
            throw new PricingRefused(
                    field + " must name a " + purpose.replace('_', ' ').toLowerCase(Locale.ROOT)
                            + " account; " + account.code() + " is " + account.purpose());
        }
        if (currency != null && !currency.equalsIgnoreCase(account.currency())) {
            throw new PricingRefused(
                    field + " is a " + account.currency() + " account and this rule prices in " + currency);
        }
    }

    /** @param copyFrom an existing version to copy the rules from, or null for an empty draft */
    public record Draft(Integer copyFrom) {}

    public record FeeRules(List<ProductAuthoring.FeeRule> rules) {}

    public record LimitRules(List<ProductAuthoring.LimitRule> rules) {}

    /** @param effectiveFrom ISO instant, or null for "as soon as it is published" */
    public record Schedule(String effectiveFrom) {}

    /** A rule the institution's own configuration will not support. */
    public static class PricingRefused extends RuntimeException {
        public PricingRefused(String message) {
            super(message);
        }
    }
}
