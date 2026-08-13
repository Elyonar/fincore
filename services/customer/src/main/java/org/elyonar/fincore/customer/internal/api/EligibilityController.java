package org.elyonar.fincore.customer.internal.api;

import java.util.UUID;
import org.elyonar.fincore.auth.Authorization;
import org.elyonar.fincore.customer.api.CustomerEligibility;
import org.elyonar.fincore.customer.api.EligibilityResult;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The questions the money path asks, over the wire.
 *
 * <p>Separate from {@code CustomerController} on purpose. That surface is customer administration —
 * registering people, changing tiers, recording consent — and is used by humans a few times a day.
 * This one is called on **every transaction on the platform** and is the reason Core can still
 * refuse correctly after the extraction. Mixing them would have buried the hot path's contract in
 * the middle of a CRUD controller and made it easy to widen by accident.
 *
 * <p><strong>Neither answer may be cached, and that is a decision rather than an omission</strong>
 * (ADR 0020). A customer frozen ten seconds ago must be refused now, so eligibility is read live.
 * And {@code productOfHeldAccount} is a security control, not a lookup: the money path resolves the
 * governing product from the account precisely so that a caller cannot name the product whose rules
 * judge its own transaction. A cache here would reintroduce that hole with a time limit on it.
 *
 * <p><strong>The tenant comes from the token, never from a path or a query.</strong> These reads
 * return one institution's customers, and a caller able to name the tenant is a caller reading
 * another institution's people.
 *
 * <p><strong>Absent is 404 and says nothing else.</strong> Another tenant's customer and a customer
 * who never existed are deliberately the same answer — the same choice the ledger and Core already
 * make, and the reason is the same: anything else is an enumeration oracle over real people.
 */
@Tag(name = "Eligibility", description = "The customer questions the money path asks per transaction")
@RestController
@RequestMapping("/v1/eligibility")
public class EligibilityController {

    private final CustomerEligibility eligibility;

    public EligibilityController(CustomerEligibility eligibility) {
        this.eligibility = eligibility;
    }

    /**
     * Status and KYC tier, live.
     *
     * <p>Returned as a body rather than as a status code even when the customer is not eligible:
     * "not found" and "found but frozen" are different facts and Core turns them into different
     * refusals, so collapsing them into one 404 here would lose the distinction on the way.
     */
    @GetMapping("/{customerId}")
    public Eligibility check(@PathVariable UUID customerId) {
        var identity = Authorization.require("customers:read");
        EligibilityResult result = eligibility.check(identity.tenantId(), customerId);
        return new Eligibility(
                result.eligible(),
                result.reason() == null ? null : result.reason().name(),
                result.kycTier());
    }

    /**
     * Which product governs an account this customer holds, or null.
     *
     * <p>Null means either that the customer does not hold the account or that the account predates
     * the column. Both refuse upstream, and this surface deliberately does not distinguish them:
     * the caller has no legitimate use for the difference, and one of the two answers is a
     * statement about somebody else's account.
     */
    @GetMapping("/{customerId}/account-product")
    public HeldProduct productOfHeldAccount(
            @PathVariable UUID customerId, @RequestParam UUID ledgerAccountId) {
        var identity = Authorization.require("customers:read");
        UUID tenantId = identity.tenantId();
        return new HeldProduct(
                eligibility.productOfHeldAccount(tenantId, customerId, ledgerAccountId),
                eligibility.holdsAccount(tenantId, customerId, ledgerAccountId));
    }

    /**
     * @param reason null when eligible; otherwise the {@code EligibilityResult.Reason} name
     */
    public record Eligibility(boolean eligible, String reason, String kycTier) {}

    /**
     * Both answers in one call, because Core needs both and asking twice would double the hot
     * path's cost to learn something the first query already knew.
     *
     * @param productCode null when the customer does not hold the account, or it has no product
     * @param holdsAccount whether they hold it at all — what tells the two nulls apart upstream
     */
    public record HeldProduct(String productCode, boolean holdsAccount) {}
}
