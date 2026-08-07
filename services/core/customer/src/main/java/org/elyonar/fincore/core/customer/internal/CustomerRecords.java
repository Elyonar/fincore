package org.elyonar.fincore.core.customer.internal;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
            UUID tenantId,
            String externalRef,
            String fullName,
            String phone,
            String email,
            String locale,
            String kycTier,
            String createdBy) {

        scopeTo(tenantId);
        try {
            UUID id =
                    jdbc.queryForObject(
                            """
                            INSERT INTO customer.customers
                                (tenant_id, external_ref, full_name, phone, email, locale, kyc_tier, created_by)
                            VALUES (?,?,?,?,?,?,?,?)
                            RETURNING id
                            """,
                            UUID.class,
                            tenantId, externalRef, fullName, phone, email, locale, kycTier, createdBy);
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
                        SELECT id, external_ref, full_name, phone, email, locale, status, kyc_tier, created_at
                          FROM customer.customers WHERE id = ?
                        """,
                        rs ->
                                rs.next()
                                        ? new Profile(
                                                rs.getObject("id", UUID.class),
                                                rs.getString("external_ref"),
                                                rs.getString("full_name"),
                                                rs.getString("phone"),
                                                rs.getString("email"),
                                                rs.getString("locale"),
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
            String email,
            String locale,
            String status,
            String kycTier,
            OffsetDateTime createdAt,
            List<Link> accounts) {

        Profile withAccounts(List<Link> accounts) {
            return new Profile(
                    customerId, externalRef, fullName, phone, email, locale, status, kycTier, createdAt, accounts);
        }
    }

    /**
     * Who to contact about an account, and whether they agreed to be contacted.
     *
     * <p>The lookup runs in the opposite direction from every other query here — from a ledger
     * account to the customer holding it — because that is the only identifier a domain event
     * carries. Event payloads hold no PII by design (ADR 0008), so a service that sends to
     * customers has to ask, on every send, and this is the one call that answers it.
     *
     * <p>Addresses are keyed by <em>address kind</em> rather than returned as named fields.
     * Notification treats a delivery channel as data, and several channels share one kind — SMS and
     * WhatsApp are both {@code PHONE}. A map means a new channel on an existing kind needs nothing
     * from Customer at all; only a genuinely new kind, such as a device token for push, is a change
     * here.
     *
     * <p>Consent is returned as the explicit answers on record, and nothing more. What an
     * <em>absent</em> answer permits is delivery policy — a transactional alert is a fraud control
     * a customer cannot opt out of, marketing is opt-in — and that decision belongs to the service
     * doing the sending. Inventing a default here would dress an assumption up as a customer's
     * answer.
     *
     * @return null when no live link to that account exists for this tenant
     */
    @Transactional(readOnly = true, transactionManager = "customerTransactionManager")
    public ContactAndConsent contactForAccount(UUID tenantId, UUID ledgerAccountId) {
        scopeTo(tenantId);

        UUID customerId =
                jdbc.query(
                        """
                        SELECT customer_id FROM customer.customer_accounts
                         WHERE ledger_account_id = ? AND unlinked_at IS NULL
                        """,
                        rs -> rs.next() ? rs.getObject("customer_id", UUID.class) : null,
                        ledgerAccountId);

        if (customerId == null) {
            // Unlinked, never linked, or another tenant's — one answer, as everywhere in this
            // module. Distinguishing them would confirm that an account exists somewhere.
            return null;
        }

        ContactAndConsent contact =
                jdbc.query(
                        "SELECT id, status, phone, email, locale FROM customer.customers WHERE id = ?",
                        rs -> {
                            if (!rs.next()) {
                                return null;
                            }
                            Map<String, String> addresses = new LinkedHashMap<>();
                            // Absent rather than null-valued: a caller iterating the map should see
                            // only addresses that exist, never an entry it has to null-check.
                            if (rs.getString("phone") != null) {
                                addresses.put(AddressKind.PHONE, rs.getString("phone"));
                            }
                            if (rs.getString("email") != null) {
                                addresses.put(AddressKind.EMAIL, rs.getString("email"));
                            }
                            return new ContactAndConsent(
                                    rs.getObject("id", UUID.class),
                                    rs.getString("status"),
                                    // Null when nobody asked. The sender falls back to the tenant
                                    // default; inventing 'en' here would make a guess look like the
                                    // customer's answer.
                                    rs.getString("locale"),
                                    addresses,
                                    List.of());
                        },
                        customerId);

        if (contact == null) {
            return null;
        }

        List<Consent> consent =
                jdbc.query(
                        """
                        SELECT category, channel, granted FROM customer.communication_consent
                         WHERE customer_id = ? ORDER BY category, channel
                        """,
                        (rs, row) ->
                                new Consent(rs.getString("category"), rs.getString("channel"), rs.getBoolean("granted")),
                        customerId);

        return contact.withConsent(consent);
    }

    /**
     * Records a customer's answer about being contacted, and keeps the history.
     *
     * <p>Current state and history are written together, in one transaction, the same way a tier
     * change is. The current row answers "may we send"; only the history answers "when did they
     * agree and who recorded it", which is the question NDPR asks and the one that arrives months
     * later with a regulator attached.
     *
     * <p>There is deliberately no endpoint that deletes a consent record. Withdrawing consent is
     * recording {@code granted = false} — an answer, kept — because a customer who opted out and a
     * customer who was never asked are different people to a compliance officer.
     */
    @Transactional(transactionManager = "customerTransactionManager")
    public Consent recordConsent(
            UUID tenantId, UUID customerId, String category, String channel, boolean granted, String recordedBy) {
        scopeTo(tenantId);

        Integer exists =
                jdbc.query("SELECT 1 FROM customer.customers WHERE id = ?", rs -> rs.next() ? 1 : null, customerId);
        if (exists == null) {
            throw new NoSuchCustomer();
        }

        String from =
                jdbc.query(
                        """
                        SELECT granted FROM customer.communication_consent
                         WHERE customer_id = ? AND category = ? AND channel = ?
                        """,
                        rs -> rs.next() ? (rs.getBoolean("granted") ? "GRANTED" : "DENIED") : "UNSET",
                        customerId, category, channel);

        jdbc.update(
                """
                INSERT INTO customer.communication_consent
                       (tenant_id, customer_id, category, channel, granted, recorded_by)
                VALUES (?,?,?,?,?,?)
                ON CONFLICT (tenant_id, customer_id, category, channel)
                DO UPDATE SET granted = EXCLUDED.granted,
                              recorded_by = EXCLUDED.recorded_by,
                              recorded_at = now()
                """,
                tenantId, customerId, category, channel, granted, recordedBy);

        jdbc.update(
                """
                INSERT INTO customer.consent_changes
                       (tenant_id, customer_id, category, channel, from_state, to_state, recorded_by)
                VALUES (?,?,?,?,?,?,?)
                """,
                tenantId, customerId, category, channel, from, granted ? "GRANTED" : "DENIED", recordedBy);

        return new Consent(category, channel, granted);
    }

    /**
     * The address kinds Customer can supply.
     *
     * <p>Constants rather than string literals, per AGENTS.md rule 10: these names are a published
     * contract that a consumer matches on, so changing one must be one edit and not a search.
     */
    public static final class AddressKind {
        private AddressKind() {}

        public static final String PHONE = "PHONE";
        public static final String EMAIL = "EMAIL";
    }

    /** @param locale BCP 47, or null when the customer was never asked */
    public record ContactAndConsent(
            UUID customerId,
            String status,
            String locale,
            Map<String, String> addresses,
            List<Consent> consent) {

        ContactAndConsent withConsent(List<Consent> consent) {
            return new ContactAndConsent(customerId, status, locale, addresses, consent);
        }
    }

    /** One explicit answer. Absence of an entry means the customer was never asked. */
    public record Consent(String category, String channel, boolean granted) {}

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
