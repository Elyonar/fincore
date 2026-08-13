package org.elyonar.fincore.core.app.pricing;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.elyonar.fincore.auth.Authorization;
import org.elyonar.fincore.core.orchestration.api.InstitutionAccounts;
import org.elyonar.fincore.core.orchestration.api.CustomerEligibility;
import org.elyonar.fincore.core.orchestration.api.ProductAuthoring;
import org.elyonar.fincore.core.orchestration.api.ProductErrorReason;
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
 * Product pricing (admin-surface §3) — fees and limits.
 *
 * <p>The gap this closes was the widest one on the platform. Both rule tables have been fully
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
@Tag(name = "Pricing", description = "Fee and limit rules per product version")
@RestController
@RequestMapping("/v1/products/{productId}/versions")
public class PricingController {

    private final ProductAuthoring authoring;
    private final InstitutionAccounts accounts;
    private final CustomerEligibility customers;

    public PricingController(
            ProductAuthoring authoring, InstitutionAccounts accounts, CustomerEligibility customers) {
        this.authoring = authoring;
        this.accounts = accounts;
        this.customers = customers;
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
        // The caller is the author — never inferred, never copied from the previous version.
        // Maker-checker at publish compares against exactly this value; recording anyone else
        // here decides who may publish, which is a money control, not bookkeeping.
        int version = authoring.draftNextVersion(
                identity.tenantId(), productId, copyFrom, Authorization.initiatedBy());
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
        List<ProductAuthoring.LimitRule> rules = request.rules() == null ? List.of() : request.rules();

        /*
         * The tier has to be one this institution recognises.
         *
         * A rule naming a tier nobody can hold does not fail — it stores cleanly and then never
         * matches, and because the evaluator denies by default it reads on screen as a configured
         * ceiling while behaving as a blanket refusal. That is the worst kind of misconfiguration:
         * silent, and indistinguishable from a working one until a customer is turned away.
         *
         * Checked here rather than in the product service because the vocabulary belongs to the
         * customer service, and Product calling it would be the edge ADR 0020 exists to prevent.
         * This controller can see both — the same reason it, and not Product, checks that a fee
         * rule names one of the institution's own accounts.
         */
        if (!rules.isEmpty()) {
            var recognised = customers.recognisedTiers(identity.tenantId());
            // An empty answer means the institution has defined none, not that none are valid;
            // refusing everything on that basis would block pricing over a provisioning gap.
            if (!recognised.isEmpty()) {
                for (ProductAuthoring.LimitRule rule : rules) {
                    if (!recognised.contains(rule.kycTier())) {
                        throw new ProductAuthoring.RulesInvalid(
                                ProductErrorReason.UNKNOWN_KYC_TIER,
                                Map.of(
                                        "kycTier", String.valueOf(rule.kycTier()),
                                        "permitted", recognised.toString()));
                    }
                }
            }
        }

        authoring.setLimitRules(identity.tenantId(), productId, version, rules);
        return authoring.read(identity.tenantId(), productId, version);
    }

    /**
     * Schedules when the version becomes live once published.
     *
     * <p>Forward only. A version dated before it existed makes every transaction it priced
     * unreconstructible: the saga records which version decided a transfer, and a version claiming
     * to have been live in 2020 turns that record into a lie that reconciliation cannot unpick.
     *
     * <p>Null is not backdating — it means "as soon as somebody publishes it", and the record layer
     * resolves it to {@code now()}.
     */
    @PatchMapping("/{version}")
    public ProductAuthoring.VersionDetail schedule(
            @PathVariable UUID productId, @PathVariable int version, @RequestBody Schedule request) {
        var identity = Authorization.require("products:create");
        String normalised = parseAndRequireNotBackdated(request.effectiveFrom());
        authoring.setEffectiveFrom(identity.tenantId(), productId, version, normalised);
        return authoring.read(identity.tenantId(), productId, version);
    }

    /**
     * Refuses a moment already past — and refuses a moment it cannot read.
     *
     * <p>This guard used to wave through any string Java's ISO parsers refused, on the theory that
     * the {@code timestamptz} cast downstream was the authority on what is a date. But the cast
     * accepts formats these parsers do not — {@code "2020-01-01"}, a space where the {@code T}
     * belongs — so "unparseable here" did not mean "unparseable there", and a caller who wrote the
     * date the way Postgres likes it backdated freely through the exact gap this method exists to
     * close. Now the guard is the authority: what it cannot read, it refuses, and what it passes
     * downstream is its own normalised UTC instant, so the database never sees a spelling this
     * method has not judged.
     *
     * @return the normalised ISO instant, or null for "as soon as somebody publishes it"
     */
    private static String parseAndRequireNotBackdated(String effectiveFrom) {
        if (effectiveFrom == null || effectiveFrom.isBlank()) {
            return null;
        }
        Instant moment;
        try {
            moment = OffsetDateTime.parse(effectiveFrom).toInstant();
        } catch (DateTimeParseException notAnOffset) {
            try {
                moment = Instant.parse(effectiveFrom);
            } catch (DateTimeParseException notAnInstant) {
                throw new ProductAuthoring.RulesInvalid(
                        ProductErrorReason.EFFECTIVE_FROM_INVALID,
                        Map.of("effectiveFrom", effectiveFrom, "expects", "an ISO-8601 instant, e.g. 2026-09-01T00:00:00Z"));
            }
        }
        if (moment.isBefore(Instant.now())) {
            throw new EffectiveFromInThePast();
        }
        return moment.toString();
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

    /** A draft dated to become effective before it existed. */
    public static class EffectiveFromInThePast extends RuntimeException {}

    /** A rule the institution's own configuration will not support. */
    public static class PricingRefused extends RuntimeException {
        public PricingRefused(String message) {
            super(message);
        }
    }
}
