package org.elyonar.fincore.customer.internal;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.elyonar.fincore.customer.api.CustomerAdministration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.elyonar.fincore.customer.api.CustomerErrorCode;

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
public class CustomerRecords implements CustomerAdministration {

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
        // Left blank, the institution's own numbering answers. Before this, `external_ref` was
        // NOT NULL with no default and taken verbatim from the request body, so every branch
        // invented a scheme and the first collision surfaced as a 409 at the counter.
        String reference = externalRef == null || externalRef.isBlank() ? claim(tenantId, "CUSTOMER") : externalRef;
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
                            tenantId, reference, fullName, phone, email, locale, kycTier, createdBy);
            return read(tenantId, id);
        } catch (DuplicateKeyException e) {
            // The tenant already numbered this customer. A 409 rather than a silent second record:
            // two rows for one person is how a KYC tier gets enforced against the wrong one.
            throw new ExternalRefTaken(reference);
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
                        SELECT ledger_account_id, account_number, currency, role, product_code, linked_at
                          FROM customer.customer_accounts
                         WHERE customer_id = ? AND unlinked_at IS NULL
                         ORDER BY linked_at
                        """,
                        (rs, row) ->
                                new Link(
                                        rs.getObject("ledger_account_id", UUID.class),
                                        rs.getString("account_number"),
                                        rs.getString("currency"),
                                        rs.getString("role"),
                                        rs.getString("product_code"),
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
            throw new IllegalArgumentException(CustomerErrorCode.TIER_UNCHANGED.code());
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
    public Link link(
            UUID tenantId,
            UUID customerId,
            UUID ledgerAccountId,
            String currency,
            String role,
            String productCode) {
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
                        (tenant_id, customer_id, ledger_account_id, currency, role, product_code)
                    VALUES (?,?,?,?,?,?)
                    RETURNING ledger_account_id, account_number, currency, role, product_code, linked_at
                    """,
                    (rs, row) ->
                            new Link(
                                    rs.getObject("ledger_account_id", UUID.class),
                                    rs.getString("account_number"),
                                    rs.getString("currency"),
                                    rs.getString("role"),
                                    rs.getString("product_code"),
                                    rs.getObject("linked_at", OffsetDateTime.class)),
                    tenantId, customerId, ledgerAccountId, currency, role, productCode);
        } catch (DuplicateKeyException e) {
            // `one_live_holder_per_account`. Two customers holding one account at once would make
            // CustomerEligibility.holdsAccount unanswerable, which the money path depends on.
            throw new CustomerAdministration.AccountAlreadyHeld();
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

        // The account number rides along because the caller that needs the contact is the caller
        // that needs to say *which account* — a message reading "your account has been debited"
        // is one a customer with two accounts cannot act on. Same row, same query, no extra hop.
        String[] found =
                jdbc.query(
                        """
                        SELECT customer_id::text, account_number FROM customer.customer_accounts
                         WHERE ledger_account_id = ? AND unlinked_at IS NULL
                        """,
                        rs -> rs.next() ? new String[] {rs.getString(1), rs.getString(2)} : null,
                        ledgerAccountId);

        UUID customerId = found == null ? null : UUID.fromString(found[0]);
        String accountNumber = found == null ? null : found[1];

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
                                    accountNumber,
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

    /**
     * @param locale BCP 47, or null when the customer was never asked
     * @param accountNumber the account this contact was looked up by — carried so a message can
     *     name which of a customer's accounts moved, which is the difference between an alert
     *     somebody can act on and one they have to ring the branch about
     */
    public record ContactAndConsent(
            UUID customerId,
            String status,
            String locale,
            String accountNumber,
            Map<String, String> addresses,
            List<Consent> consent) {

        ContactAndConsent withConsent(List<Consent> consent) {
            return new ContactAndConsent(customerId, status, locale, accountNumber, addresses, consent);
        }
    }

    /** One explicit answer. Absence of an entry means the customer was never asked. */
    public record Consent(String category, String channel, boolean granted) {}

    // --- numbering, and the published port -------------------------------------------------------

    /** What an institution gets before it decides: a bare ten-digit serial, NUBAN-shaped. */
    private static final String DEFAULT_PREFIX = "";

    private static final int DEFAULT_WIDTH = 10;

    /**
     * Takes the next number in a series, atomically.
     *
     * <p>{@code UPDATE … RETURNING} under the row lock is the arbiter, so two tellers opening
     * accounts in the same second take different numbers. The row is created on first use rather
     * than by a migration, because migrations run with no tenant context against FORCE row-level
     * security and would write nothing while appearing to succeed.
     */
    private String claim(UUID tenantId, String series) {
        Long claimed = nextFromExistingRow(tenantId, series);
        if (claimed == null) {
            // First use: seed the row claiming number 1 for ourselves. ON CONFLICT DO NOTHING
            // because two first users race to create it — and the loser must NOT also take
            // number 1, which is exactly what returning unconditionally here once did: both
            // claimants were told 1, and the second link collided on the unique index. The
            // update count is the arbiter — 1 row means we seeded and own number 1, 0 rows
            // means the winner's row exists now and the ordinary UPDATE claims from it.
            int seeded = jdbc.update(
                    "INSERT INTO customer.numbering (tenant_id, series, prefix, width, next_value, updated_by)"
                            + " VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                    tenantId,
                    series,
                    DEFAULT_PREFIX,
                    DEFAULT_WIDTH,
                    2L,
                    "service:core");
            if (seeded == 1) {
                return format(DEFAULT_PREFIX, DEFAULT_WIDTH, 1L);
            }
            claimed = nextFromExistingRow(tenantId, series);
            if (claimed == null) {
                // The row we just conflicted with has vanished, and rows here are never deleted.
                throw new IllegalStateException("numbering row for series " + series + " disappeared mid-claim");
            }
        }
        String[] rule = jdbc.query(
                "SELECT prefix, width FROM customer.numbering WHERE tenant_id = ? AND series = ?",
                rs -> rs.next() ? new String[] {rs.getString(1), String.valueOf(rs.getInt(2))} : null,
                tenantId,
                series);
        return rule == null
                ? format(DEFAULT_PREFIX, DEFAULT_WIDTH, claimed)
                : format(rule[0], Integer.parseInt(rule[1]), claimed);
    }

    /** The atomic take: {@code UPDATE … RETURNING} under the row lock, or null before first use. */
    private Long nextFromExistingRow(UUID tenantId, String series) {
        return jdbc.query(
                "UPDATE customer.numbering SET next_value = next_value + 1"
                        + " WHERE tenant_id = ? AND series = ? RETURNING next_value - 1",
                rs -> rs.next() ? rs.getLong(1) : null,
                tenantId,
                series);
    }

    private static String format(String prefix, int width, long value) {
        String digits = Long.toString(value);
        StringBuilder padded = new StringBuilder();
        for (int i = digits.length(); i < width; i++) {
            padded.append('0');
        }
        return prefix + padded + digits;
    }

    @Override
    @Transactional(readOnly = true, transactionManager = "customerTransactionManager")
    public CustomerAdministration.NumberSeries numbering(UUID tenantId, String series) {
        scopeTo(tenantId);
        CustomerAdministration.NumberSeries found = jdbc.query(
                "SELECT prefix, width, next_value FROM customer.numbering"
                        + " WHERE tenant_id = ? AND series = ?",
                rs ->
                        rs.next()
                                ? new CustomerAdministration.NumberSeries(
                                        series, rs.getString(1), rs.getInt(2), rs.getLong(3), null)
                                : null,
                tenantId,
                series);
        CustomerAdministration.NumberSeries rule = found == null
                ? new CustomerAdministration.NumberSeries(series, DEFAULT_PREFIX, DEFAULT_WIDTH, 1L, null)
                : found;
        return new CustomerAdministration.NumberSeries(
                rule.series(),
                rule.prefix(),
                rule.width(),
                rule.nextValue(),
                format(rule.prefix(), rule.width(), rule.nextValue()));
    }

    @Override
    @Transactional(transactionManager = "customerTransactionManager")
    public CustomerAdministration.NumberSeries setNumbering(
            UUID tenantId, String series, String prefix, int width, long nextValue, String updatedBy) {
        scopeTo(tenantId);
        // Forward only. Winding a series back re-issues numbers already carried by live accounts,
        // which surfaces later as serial ACCOUNT_NUMBER_TAKEN collisions at the counter — one per
        // opening, until the counter walks past the numbers already spent. The read locks the row,
        // so a claim racing this cannot slip between the check and the write.
        Long current = jdbc.query(
                "SELECT next_value FROM customer.numbering WHERE tenant_id = ? AND series = ? FOR UPDATE",
                rs -> rs.next() ? rs.getLong(1) : null,
                tenantId,
                series);
        if (current != null && nextValue < current) {
            throw new CustomerAdministration.NumberingRewind(nextValue, current);
        }
        jdbc.update(
                "INSERT INTO customer.numbering (tenant_id, series, prefix, width, next_value, updated_by)"
                        + " VALUES (?,?,?,?,?,?)"
                        + " ON CONFLICT (tenant_id, series) DO UPDATE SET prefix = EXCLUDED.prefix,"
                        + " width = EXCLUDED.width, next_value = EXCLUDED.next_value,"
                        + " updated_at = now(), updated_by = EXCLUDED.updated_by",
                tenantId,
                series,
                prefix == null ? "" : prefix,
                width,
                nextValue,
                updatedBy);
        return numbering(tenantId, series);
    }

    @Override
    @Transactional(transactionManager = "customerTransactionManager")
    public CustomerAdministration.OpenedAccount linkWithNumber(
            UUID tenantId,
            UUID customerId,
            UUID ledgerAccountId,
            String currency,
            String role,
            String productCode,
            String accountNumber) {
        scopeTo(tenantId);

        Integer exists =
                jdbc.query("SELECT 1 FROM customer.customers WHERE id = ?", rs -> rs.next() ? 1 : null, customerId);
        if (exists == null) {
            throw new NoSuchCustomer();
        }

        // Supplied or claimed, exactly as `external_ref` already works for the customer themselves.
        // An institution moving its book onto this platform arrives with account numbers already
        // printed on passbooks and already known to the settlement switch; a column that could only
        // be generated would renumber every one of them on migration day.
        String number =
                accountNumber == null || accountNumber.isBlank() ? claim(tenantId, "ACCOUNT") : accountNumber.trim();
        try {
            jdbc.update(
                    "INSERT INTO customer.customer_accounts"
                            + " (tenant_id, customer_id, ledger_account_id, currency, role, account_number,"
                            + "  product_code)"
                            + " VALUES (?,?,?,?,?,?,?)",
                    tenantId,
                    customerId,
                    ledgerAccountId,
                    currency,
                    role,
                    number,
                    productCode);
        } catch (DuplicateKeyException e) {
            // Two unique constraints can raise this: one live holder per ledger account, and one
            // account number per tenant. Told apart by which index Postgres names, because the
            // remedies differ — the first means the account is already held, the second means the
            // number belongs to somebody else.
            //
            // Read off the exception rather than by asking the database: the constraint violation
            // has already aborted this transaction, so any query issued here fails as well and the
            // refusal turns into a 500.
            if (violated(e, "customer_accounts_number_per_tenant")) {
                throw new CustomerAdministration.AccountNumberTaken(number);
            }
            throw new CustomerAdministration.AccountAlreadyHeld();
        }
        return new CustomerAdministration.OpenedAccount(ledgerAccountId, number, currency, role, productCode);
    }

    @Override
    @Transactional(readOnly = true, transactionManager = "customerTransactionManager")
    public String externalRefOf(UUID tenantId, UUID customerId) {
        scopeTo(tenantId);
        return jdbc.query(
                "SELECT external_ref FROM customer.customers WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                customerId);
    }

    /**
     * @param accountNumber what the institution calls this account, and the only name a customer
     *     will ever use for it. Null for accounts linked before the platform issued numbers — shown
     *     as absent rather than replaced with an identifier nobody can read down a telephone.
     * @param productCode what the account is held under; what prices every transaction on it.
     */
    public record Link(
            UUID ledgerAccountId,
            String accountNumber,
            String currency,
            String role,
            String productCode,
            OffsetDateTime linkedAt) {}

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


    /** Whether this violation names that index, anywhere down the cause chain. */
    private static boolean violated(Throwable e, String index) {
        for (Throwable at = e; at != null; at = at.getCause()) {
            String message = at.getMessage();
            if (message != null && message.contains(index)) {
                return true;
            }
            if (at.getCause() == at) {
                break;
            }
        }
        return false;
    }
    /**
     * The screen-opener (ui-runway.md §3): find customers by name or the tenant's own reference.
     *
     * <p>Keyset-paginated on id — an opaque cursor to callers, stable under concurrent writes in
     * a way an offset never is. One page is {@code limit} rows; the caller passes the last row's
     * cursor back to continue.
     */
    @Transactional(readOnly = true, transactionManager = "customerTransactionManager")
    public List<Map<String, Object>> search(UUID tenantId, String query, UUID afterId, int limit) {
        scopeTo(tenantId);
        String like = "%" + (query == null ? "" : query.trim()) + "%";
        return jdbc.query(
                """
                SELECT id, external_ref, full_name, status, kyc_tier
                  FROM customer.customers
                 WHERE (full_name ILIKE ? OR external_ref ILIKE ?)
                   AND (?::uuid IS NULL OR id > ?::uuid)
                 ORDER BY id
                 LIMIT ?
                """,
                (rs, i) -> {
                    var row = new LinkedHashMap<String, Object>();
                    row.put("customerId", rs.getObject("id", UUID.class).toString());
                    row.put("externalRef", rs.getString("external_ref"));
                    row.put("fullName", rs.getString("full_name"));
                    row.put("status", rs.getString("status"));
                    row.put("kycTier", rs.getString("kyc_tier"));
                    return row;
                },
                like, like, afterId, afterId, limit);
    }
}