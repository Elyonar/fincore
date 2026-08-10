package org.elyonar.fincore.core.customer.api;

import java.util.UUID;

/**
 * The customer-side writes a composing surface needs (admin-surface §4).
 *
 * <p>Published for the same reason {@code ProductAuthoring} is: opening an account means opening it
 * in the ledger and recording who holds it, and those live in two modules that may not reach each
 * other (ADR 0006, hard rule 3). The composition happens in {@code app}, so both halves have to be
 * askable from there.
 *
 * <p>Deliberately narrow. Customer's own controller still owns creating, searching and reading a
 * customer; this carries only what a caller outside the module cannot do for itself.
 */
public interface CustomerAdministration {

    /** How a series of numbers is formed, and what the next one would be. */
    record NumberSeries(String series, String prefix, int width, long nextValue, String preview) {}

    /** An account, as the customer knows it. */
    record OpenedAccount(UUID ledgerAccountId, String accountNumber, String currency, String role) {}

    /** Both series: how customers are numbered, and how their accounts are. */
    NumberSeries numbering(UUID tenantId, String series);

    /** Changes a series. {@code nextValue} is settable — an institution migrating in has issued numbers. */
    NumberSeries setNumbering(
            UUID tenantId, String series, String prefix, int width, long nextValue, String updatedBy);

    /**
     * Records that this customer holds this ledger account, under a freshly claimed account number.
     *
     * <p>The number is claimed here rather than passed in, so two tellers opening accounts in the
     * same second cannot be handed the same one: the row lock inside this transaction is the
     * arbiter, exactly as it is for staff numbers.
     */
    OpenedAccount linkWithNumber(UUID tenantId, UUID customerId, UUID ledgerAccountId, String currency, String role);

    /** The customer's own reference — what the institution calls them. Null when they do not exist. */
    String externalRefOf(UUID tenantId, UUID customerId);
}
