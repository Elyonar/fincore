package org.elyonar.fincore.core.customer.api;

import java.util.UUID;

/**
 * What Orchestration is allowed to ask the Customer module.
 *
 * <p>The whole surface, deliberately. Orchestration needs to know whether a customer may transact
 * and at what KYC tier; it needs no names, no documents, nothing else Customer holds. Keeping the
 * interface this narrow is what stops the money path from acquiring a dependency on PII, and it is
 * why Customer can later become its own deployable by turning this into a client without changing
 * a single call site.
 */
public interface CustomerEligibility {

    /**
     * Whether this customer may transact, and under which tier.
     *
     * @param tenantId from the validated token, never from a request
     * @param customerId the subject of the operation
     */
    EligibilityResult check(UUID tenantId, UUID customerId);

    /**
     * Whether this ledger account belongs to this customer, in this tenant.
     *
     * <p>Asked separately because posting to an account a customer does not hold is a different
     * failure from a customer who may not transact, and the two deserve distinct error codes.
     */
    boolean holdsAccount(UUID tenantId, UUID customerId, UUID ledgerAccountId);

    /**
     * The accounts this customer currently holds — the customer-360 read (ui-runway.md §3).
     *
     * <p>Account ids, currencies and roles only: the money side (balances) is the ledger's answer
     * and Orchestration joins the two. Still no names, no documents, no PII — the narrowness of
     * this port is load-bearing and this method keeps its discipline.
     */
    java.util.List<HeldAccount> heldAccounts(UUID tenantId, UUID customerId);

    record HeldAccount(UUID ledgerAccountId, String currency, String role) {}
}
