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
    record OpenedAccount(
            UUID ledgerAccountId, String accountNumber, String currency, String role, String productCode) {}

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
     *
     * <p>{@code accountNumber} is optional and taken verbatim when given, exactly as the customer's
     * own {@code externalRef} is. Left blank, the institution's numbering answers. An institution
     * migrating an existing book arrives with numbers already on passbooks and already known to the
     * settlement switch, and a column that could only be generated would renumber all of them.
     *
     * <p>{@code productCode} is required, not optional. It decides which fee and limit rules every
     * transaction on this account is judged by, and an account without one cannot transact at all —
     * so the moment to establish it is the moment the account comes into being, when somebody is
     * present who knows the answer.
     */
    /**
     * The refusals opening can raise, declared here rather than beside the implementation.
     *
     * <p>A caller of this port has to be able to catch them, and callers live outside the module —
     * {@code app}'s account-opening surface is one. Exceptions that only exist in {@code internal}
     * are catchable only by code that may not import them, so they arrive as a 500 instead.
     */
    class AccountAlreadyHeld extends RuntimeException {
        public AccountAlreadyHeld() {
            super("that ledger account is already held by a customer");
        }
    }

    /** The institution supplied an account number another live account already carries. */
    class AccountNumberTaken extends RuntimeException {
        public AccountNumberTaken(String accountNumber) {
            super("account number already in use: " + accountNumber);
        }
    }

    OpenedAccount linkWithNumber(
            UUID tenantId,
            UUID customerId,
            UUID ledgerAccountId,
            String currency,
            String role,
            String productCode,
            String accountNumber);

    /** The customer's own reference — what the institution calls them. Null when they do not exist. */
    String externalRefOf(UUID tenantId, UUID customerId);
}
