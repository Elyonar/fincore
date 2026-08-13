package org.elyonar.fincore.core.orchestration.internal.services;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.elyonar.fincore.core.orchestration.api.CustomerEligibility;
import org.elyonar.fincore.core.orchestration.api.EligibilityResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The customer questions the money path asks, now over the wire (ADR 0020).
 *
 * <p>Two of these run on every transaction and neither may be cached. A customer frozen ten seconds
 * ago must be refused now, and {@code productOfHeldAccount} is a security control rather than a
 * lookup — the money path resolves the governing product from the account precisely so a caller
 * cannot name the rules that judge its own transaction. A cache here would put a time limit on that
 * guarantee, which is the same as not having it.
 *
 * <p>Absent is refused, not thrown. A customer who does not exist and a customer belonging to
 * another tenant both come back as 404 and both become {@code NOT_FOUND} — the same answer the
 * in-process implementation gave, and deliberately indistinguishable.
 *
 * <p>Unreachable is <em>not</em> absent. {@link ServiceCall} throws rather than returning a default,
 * and that exception is allowed to propagate: the saga engine already knows how to refuse a
 * transaction it could not decide, and turning "we could not ask" into "no such customer" would
 * write a false statement into the transaction record.
 */
@Component
public class HttpCustomerEligibility implements CustomerEligibility {

    private final ServiceCall call;
    private final String baseUrl;

    public HttpCustomerEligibility(
            ServiceCall call, @Value("${fincore.core.customer.base-url:http://localhost:8085}") String baseUrl) {
        this.call = call;
        this.baseUrl = baseUrl;
    }

    @Override
    public EligibilityResult check(UUID tenantId, UUID customerId) {
        JsonNode body = call.get(tenantId, baseUrl, "/v1/eligibility/" + customerId);
        if (body == null) {
            return EligibilityResult.refused(EligibilityResult.Reason.NOT_FOUND);
        }
        if (body.path("eligible").asBoolean()) {
            return EligibilityResult.eligible(body.path("kycTier").asString());
        }
        String reason = body.path("reason").asString();
        return EligibilityResult.refused(
                reason == null || reason.isBlank()
                        ? EligibilityResult.Reason.NOT_FOUND
                        : EligibilityResult.Reason.valueOf(reason));
    }

    @Override
    public boolean holdsAccount(UUID tenantId, UUID customerId, UUID ledgerAccountId) {
        JsonNode body = heldProduct(tenantId, customerId, ledgerAccountId);
        return body != null && body.path("holdsAccount").asBoolean();
    }

    @Override
    public String productOfHeldAccount(UUID tenantId, UUID customerId, UUID ledgerAccountId) {
        JsonNode body = heldProduct(tenantId, customerId, ledgerAccountId);
        if (body == null) {
            return null;
        }
        JsonNode code = body.get("productCode");
        return code == null || code.isNull() ? null : code.asString();
    }

    /**
     * Both answers in one call.
     *
     * <p>{@code TransferService} asks for the product and then, only when that is null, asks
     * whether the account is held at all — to tell "you do not hold this" from "this account has no
     * product". In process those were two cheap reads; over the wire they would be two round trips
     * on the busiest path on the platform, so the endpoint returns both and this splits them.
     */
    private JsonNode heldProduct(UUID tenantId, UUID customerId, UUID ledgerAccountId) {
        // Nobody holds an account that was not named. In process this was a query returning no row;
        // over the wire an unguarded null becomes the four characters "null" in a path parameter,
        // which the far side cannot parse into a UUID — so a caller who simply omitted the account
        // got a 500 about an unavailable service instead of a refusal naming the field they missed.
        if (customerId == null || ledgerAccountId == null) {
            return null;
        }
        return call.get(
                tenantId,
                baseUrl,
                "/v1/eligibility/" + customerId + "/account-product?ledgerAccountId=" + ledgerAccountId);
    }

    /**
     * The tiers this institution recognises.
     *
     * <p>Not part of the eligibility port — it is here because this class already holds the client
     * for the customer service, and the one caller is Core's pricing surface. A limit rule names a
     * tier, and a rule naming a tier nobody can hold is a rule that silently never applies: the
     * evaluator denies by default, so it reads as a configured ceiling and behaves as a refusal.
     *
     * <p>Product cannot make this check itself. The tier's authority is the customer service, and
     * Product calling it would be an edge ADR 0020 exists to prevent. Core can see both, so Core
     * checks — the same shape as the fee-account check it already performs against its own
     * internal-accounts register.
     */
    @Override
    public java.util.Set<String> recognisedTiers(UUID tenantId) {
        JsonNode body = call.get(tenantId, baseUrl, "/v1/kyc-tiers");
        java.util.Set<String> codes = new java.util.LinkedHashSet<>();
        if (body == null) {
            return codes;
        }
        for (JsonNode tier : body) {
            if (tier.path("active").asBoolean()) {
                codes.add(tier.path("code").asString());
            }
        }
        return codes;
    }

    @Override
    public List<HeldAccount> heldAccounts(UUID tenantId, UUID customerId) {
        JsonNode body = call.get(tenantId, baseUrl, "/v1/customers/" + customerId);
        List<HeldAccount> held = new ArrayList<>();
        if (body == null) {
            return held;
        }
        for (JsonNode account : body.path("accounts")) {
            held.add(new HeldAccount(
                    UUID.fromString(account.path("ledgerAccountId").asString()),
                    account.path("accountNumber").asString(),
                    account.path("currency").asString(),
                    account.path("role").asString(),
                    account.path("productCode").asString()));
        }
        return held;
    }
}
