package org.elyonar.fincore.core.customer.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer's administrative writes — the surface {@code api.md} always described and nothing ever
 * implemented.
 *
 * <p>Deliberately <em>not</em> exposed through {@code customer.api}. That package is the contract
 * with Orchestration, and its narrowness is load-bearing: it is what keeps the money path free of a
 * dependency on PII, and what lets Customer become its own deployable later by turning two methods
 * into a client. Administration has a different consumer — an operator over HTTP — so it lives
 * entirely inside the module, reachable only by the controller beside it.
 *
 * <p>Every method scopes its own connection. Row-level security is the backstop for a query that
 * forgot to filter, and a backstop that is only active sometimes is not one.
 */
@Repository
public class CustomerRecords {

    private final JdbcTemplate jdbc;

    public CustomerRecords(@Qualifier("customerJdbcTemplate") JdbcTemplate customerJdbcTemplate) {
        this.jdbc = customerJdbcTemplate;
    }

    private void scopeTo(UUID tenantId) {
        // SET LOCAL, never a session SET: connections are pooled across tenants.
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }

    /**
     * Registers a customer.
     *
     * @param externalRef the tenant's own customer number, unique within the tenant
     */
    @Transactional(transactionManager = "customerTransactionManager")
    public Profile create(
            UUID tenantId, String externalRef, String fullName, String phone, String kycTier, String createdBy) {

        scopeTo(tenantId);
        try {
            UUID id =
                    jdbc.queryForObject(
                            """
                            INSERT INTO customer.customers
                                (tenant_id, external_ref, full_name, phone, kyc_tier, created_by)
                            VALUES (?,?,?,?,?,?)
                            RETURNING id
                            """,
                            UUID.class,
                            tenantId, externalRef, fullName, phone, kycTier, createdBy);
            return read(tenantId, id);
        } catch (DuplicateKeyException e) {
            // The tenant already numbered this customer. A 409 rather than a silent second record:
            // two rows for one person is how a KYC tier gets enforced against the wrong one.
            throw new ExternalRefTaken(externalRef);
        }
    }

    /** The profile, with live account links. Null when no such customer is visible to this tenant. */
    @Transactional(readOnly = true, transactionManager = "customerTransactionManager")
    public Profile read(UUID tenantId, UUID customerId) {
        scopeTo(tenantId);

        Profile profile =
                jdbc.query(
                        """
                        SELECT id, external_ref, full_name, phone, status, kyc_tier, created_at
                          FROM customer.customers WHERE id = ?
                        """,
                        rs ->
                                rs.next()
                                        ? new Profile(
                                                rs.getObject("id", UUID.class),
                                                rs.getString("external_ref"),
                                                rs.getString("full_name"),
                                                rs.getString("phone"),
                                                rs.getString("status"),
                                                rs.getString("kyc_tier"),
                                                rs.getObject("created_at", OffsetDateTime.class),
                                                List.of())
                                        : null,
                        customerId);

        if (profile == null) {
            // Not-found and belongs-to-another-tenant are one answer, as everywhere in this module:
            // distinguishing them confirms that a customer exists somewhere.
            return null;
        }

        List<Link> links =
                jdbc.query(
                        """
                        SELECT ledger_account_id, currency, role, linked_at
                          FROM customer.customer_accounts
                         WHERE customer_id = ? AND unlinked_at IS NULL
                         ORDER BY linked_at
                        """,
                        (rs, row) ->
                                new Link(
                                        rs.getObject("ledger_account_id", UUID.class),
                                        rs.getString("currency"),
                                        rs.getString("role"),
                                        rs.getObject("linked_at", OffsetDateTime.class)),
                        customerId);

        return profile.withAccounts(links);
    }

    /**
     * Changes a KYC tier and records who changed it and why.
     *
     * <p>The write and its audit row commit together or not at all. A tier that moved without a
     * trail is the failure this exists to prevent, and two statements in one transaction is the
     * cheapest way to make that impossible.
     */
    @Transactional(transactionManager = "customerTransactionManager")
    public TierChange changeTier(UUID tenantId, UUID customerId, String toTier, String reason, String changedBy) {
        scopeTo(tenantId);

        String fromTier =
                jdbc.query(
                        "SELECT kyc_tier FROM customer.customers WHERE id = ?",
                        rs -> rs.next() ? rs.getString(1) : null,
                        customerId);
        if (fromTier == null) {
            throw new NoSuchCustomer();
        }
        if (fromTier.equals(toTier)) {
            // Refused rather than recorded. An audit trail padded with changes that changed nothing
            // is harder to read, and reading it is the entire point.
            throw new IllegalArgumentException("TIER_UNCHANGED");
        }

        jdbc.update("UPDATE customer.customers SET kyc_tier = ?, updated_at = now() WHERE id = ?", toTier, customerId);
        jdbc.update(
                """
                INSERT INTO customer.customer_tier_changes
                    (tenant_id, customer_id, from_tier, to_tier, reason, changed_by)
                VALUES (?,?,?,?,?,?)
                """,
                tenantId, customerId, fromTier, toTier, reason, changedBy);

        return new TierChange(customerId, fromTier, toTier);
    }

    /**
     * Links a ledger account to a customer.
     *
     * <p>Records an association; it does not create an account. The Ledger is the authority on
     * whether the account exists, and Customer may not call it — only Orchestration may (AGENTS.md
     * hard rule 3). So this is deliberately a claim about ownership, not a verification of it.
     */
    @Transactional(transactionManager = "customerTransactionManager")
    public Link link(UUID tenantId, UUID customerId, UUID ledgerAccountId, String currency, String role) {
        scopeTo(tenantId);

        Integer exists =
                jdbc.query("SELECT 1 FROM customer.customers WHERE id = ?", rs -> rs.next() ? 1 : null, customerId);
        if (exists == null) {
            throw new NoSuchCustomer();
        }

        try {
            return jdbc.queryForObject(
                    """
                    INSERT INTO customer.customer_accounts
                        (tenant_id, customer_id, ledger_account_id, currency, role)
                    VALUES (?,?,?,?,?)
                    RETURNING ledger_account_id, currency, role, linked_at
                    """,
                    (rs, row) ->
                            new Link(
                                    rs.getObject("ledger_account_id", UUID.class),
                                    rs.getString("currency"),
                                    rs.getString("role"),
                                    rs.getObject("linked_at", OffsetDateTime.class)),
                    tenantId, customerId, ledgerAccountId, currency, role);
        } catch (DuplicateKeyException e) {
            // `one_live_holder_per_account`. Two customers holding one account at once would make
            // CustomerEligibility.holdsAccount unanswerable, which the money path depends on.
            throw new AccountAlreadyHeld();
        }
    }

    /** A customer as the administrative API sees them — the whole record, unlike what Orchestration gets. */
    public record Profile(
            UUID customerId,
            String externalRef,
            String fullName,
            String phone,
            String status,
            String kycTier,
            OffsetDateTime createdAt,
            List<Link> accounts) {

        Profile withAccounts(List<Link> accounts) {
            return new Profile(customerId, externalRef, fullName, phone, status, kycTier, createdAt, accounts);
        }
    }

    public record Link(UUID ledgerAccountId, String currency, String role, OffsetDateTime linkedAt) {}

    public record TierChange(UUID customerId, String fromTier, String toTier) {}

    public static class NoSuchCustomer extends RuntimeException {
        public NoSuchCustomer() {
            super("no such customer");
        }
    }

    public static class ExternalRefTaken extends RuntimeException {
        public ExternalRefTaken(String externalRef) {
            super("external ref already registered: " + externalRef);
        }
    }

    public static class AccountAlreadyHeld extends RuntimeException {
        public AccountAlreadyHeld() {
            super("that ledger account is already held by a customer");
        }
    }
}
