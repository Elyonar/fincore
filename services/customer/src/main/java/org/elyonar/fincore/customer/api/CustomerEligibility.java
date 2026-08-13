package org.elyonar.fincore.customer.api;

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
     * The product this account was opened under, or null.
     *
     * <p>Null means one of two things, and the money path treats them alike: the customer does not
     * hold this account, or the account predates {@code customer_accounts.product_code} and has no
     * honest answer. Both are refusals. The alternative is taking the product from the request
     * body, which is where the fee charged and the limit applied used to come from — a caller could
     * name which rules its own transaction was judged by, and that is the hole this closes.
     *
     * <p>Still no PII. A product code is what the institution calls a product, not anything about
     * the person holding the account; the narrowness of this port is load-bearing.
     */
    String productOfHeldAccount(UUID tenantId, UUID customerId, UUID ledgerAccountId);

    /**
     * The accounts this customer currently holds — the customer-360 read (ui-runway.md §3).
     *
     * <p>Account ids, currencies and roles only: the money side (balances) is the ledger's answer
     * and Orchestration joins the two. Still no names, no documents, no PII — the narrowness of
     * this port is load-bearing and this method keeps its discipline.
     */
    java.util.List<HeldAccount> heldAccounts(UUID tenantId, UUID customerId);

    /**
     * @param accountNumber what the institution calls this account — the number on a paying-in slip
     *     and the only name for it a customer will ever use. Null for accounts linked before the
     *     platform issued numbers. Not PII: it identifies an account, not a person, in exactly the
     *     way {@code ledgerAccountId} does, and it is the difference between a screen a teller can
     *     read and a screen showing UUIDs
     * @param productCode what the account was opened under. Null for accounts linked before the
     *     column existed — a customer-360 read shows the gap rather than inventing a product.
     */
    record HeldAccount(
            UUID ledgerAccountId,
            String accountNumber,
            String currency,
            String role,
            String productCode) {}
}
