package org.elyonar.fincore.core.product.internal.api;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.elyonar.fincore.auth.Authorization;
import org.elyonar.fincore.core.product.internal.ProductAuthoring;
import org.elyonar.fincore.core.product.internal.ProductRecords;
import org.elyonar.fincore.core.product.internal.RuleValidation;
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
 * The product catalogue, and the act of making a version live.
 *
 * <p>Publishing is the only endpoint on this platform outside the money path that carries
 * maker-checker, and it earns it: a product version holds the fee and the limit, so whoever can
 * draft and publish alone can raise a customer's ceiling and price against it without a second
 * signature. That is a money control wearing configuration's clothes.
 *
 * <p>There is no endpoint that edits a published version. A change is a new version, so that a
 * transaction decided under version 3 stays explicable when version 4 exists.
 */
@Tag(name = "Products", description = "The product catalogue and publishing versions")
@RestController
@RequestMapping("/v1/products")
public class ProductController {

    private final ProductRecords products;
    private final ProductAuthoring authoring;
    private final RuleValidation validation;

    public ProductController(
            ProductRecords products, ProductAuthoring authoring, RuleValidation validation) {
        this.products = products;
        this.authoring = authoring;
        this.validation = validation;
    }

    /** Every product and its versions, live or draft. */
    @GetMapping
    public List<ProductRecords.Product> list() {
        var identity = Authorization.require("products:read");
        return products.list(identity.tenantId());
    }

    /** Creates a product with its first version, as a DRAFT. Nothing prices until it is published. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductRecords.Product create(@RequestBody CreateProduct request) {
        var identity = Authorization.require("products:create");
        return products.create(
                identity.tenantId(),
                request.code(),
                request.name(),
                request.type(),
                Authorization.initiatedBy());
    }

    /**
     * Publishes a version.
     *
     * <p>The publisher is taken from the token and never the body — an approval whose second
     * signature the caller could name is not maker-checker at all. The database refuses author ==
     * publisher regardless of what this method does.
     */
    @PostMapping("/{id}/versions/{version}/publish")
    public ProductRecords.Version publish(@PathVariable UUID id, @PathVariable int version) {
        var identity = Authorization.require("products:publish");
        return products.publish(identity.tenantId(), id, version, Authorization.initiatedBy());
    }

    // ---------------------------------------------------------------- version authoring

    /**
     * Drafts the next version of a product.
     *
     * <p>{@code cloneFrom} copies an existing version's rules into the new draft, which is what a
     * price change almost always is: last version, with one number moved. Omit it for a version that
     * starts empty.
     */
    @PostMapping("/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductRecords.Version draft(@PathVariable UUID id, @RequestBody(required = false) DraftVersion request) {
        var identity = Authorization.require("products:create");
        Integer cloneFrom = request == null ? null : request.cloneFrom();
        return authoring.draft(identity.tenantId(), id, cloneFrom, Authorization.initiatedBy());
    }

    /**
     * One version and everything that prices it.
     *
     * <p>A first-class read, not a debugging aid: an administrator who cannot see the pricing of a
     * product they published cannot review it, and a client cannot render an edit form without it.
     */
    @GetMapping("/{id}/versions/{version}")
    public ProductAuthoring.VersionDetail version(@PathVariable UUID id, @PathVariable int version) {
        var identity = Authorization.require("products:read");
        return authoring.read(identity.tenantId(), id, version);
    }

    /**
     * Replaces the fee schedule of a draft.
     *
     * <p>Total, not incremental — send the rules the version should have. A PATCH of one rule out of
     * four is how a version comes to price half of what its author intended.
     */
    @PutMapping("/{id}/versions/{version}/fee-rules")
    public ProductAuthoring.VersionDetail feeRules(
            @PathVariable UUID id, @PathVariable int version, @RequestBody FeeRules request) {
        var identity = Authorization.require("products:create");
        List<ProductAuthoring.FeeRule> rules = request.rules() == null ? List.of() : request.rules();
        validation.checkFeeRules(identity.tenantId(), rules);
        return authoring.replaceFeeRules(identity.tenantId(), id, version, rules);
    }

    /** Replaces the limit schedule of a draft, per KYC tier and channel. Total, as fees are. */
    @PutMapping("/{id}/versions/{version}/limit-rules")
    public ProductAuthoring.VersionDetail limitRules(
            @PathVariable UUID id, @PathVariable int version, @RequestBody LimitRules request) {
        var identity = Authorization.require("products:create");
        List<ProductAuthoring.LimitRule> rules = request.rules() == null ? List.of() : request.rules();
        validation.checkLimitRules(identity.tenantId(), rules);
        return authoring.replaceLimitRules(identity.tenantId(), id, version, rules);
    }

    /**
     * Replaces the loan terms of a draft. LOAN products only; one rule set per version.
     *
     * <p>Surfaced under Lending in the portal rather than beside the catalogue, because the rate and
     * the term bounds are read at the desk. The rule still belongs to a product version, which is
     * why it is authored here.
     */
    @PutMapping("/{id}/versions/{version}/loan-rules")
    public ProductAuthoring.VersionDetail loanRules(
            @PathVariable UUID id, @PathVariable int version, @RequestBody LoanRules request) {
        var identity = Authorization.require("products:create");
        validation.checkLoanRule(identity.tenantId(), request.rule());
        return authoring.replaceLoanRules(identity.tenantId(), id, version, request.rule());
    }

    /**
     * Sets when a draft becomes live once published.
     *
     * <p>Forward only. A version claiming to have been effective before it existed makes every
     * transaction the saga says it priced unreconstructible.
     */
    @PatchMapping("/{id}/versions/{version}")
    public ProductAuthoring.VersionDetail schedule(
            @PathVariable UUID id, @PathVariable int version, @RequestBody Schedule request) {
        var identity = Authorization.require("products:create");
        validation.checkEffectiveFrom(request.effectiveFrom());
        return authoring.setEffectiveFrom(identity.tenantId(), id, version, request.effectiveFrom());
    }

    /** @param type SAVINGS, CURRENT or LOAN */
    public record CreateProduct(String code, String name, String type) {}

    /** @param cloneFrom an existing version of this product whose rules the draft starts from */
    public record DraftVersion(Integer cloneFrom) {}

    public record FeeRules(List<ProductAuthoring.FeeRule> rules) {}

    public record LimitRules(List<ProductAuthoring.LimitRule> rules) {}

    public record LoanRules(ProductAuthoring.LoanRule rule) {}

    public record Schedule(OffsetDateTime effectiveFrom) {}
}
