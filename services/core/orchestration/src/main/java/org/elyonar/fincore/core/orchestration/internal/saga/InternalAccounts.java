package org.elyonar.fincore.core.orchestration.internal.saga;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.elyonar.fincore.core.orchestration.api.CoreException;
import org.elyonar.fincore.core.orchestration.api.DetailKey;
import org.elyonar.fincore.core.orchestration.api.ErrorCode;
import org.elyonar.fincore.core.orchestration.api.InstitutionAccounts;
import org.elyonar.fincore.core.orchestration.internal.ledger.LedgerClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The institution's own accounts: opening them in the ledger, and keeping the register of what
 * each one is called.
 *
 * <p>Two facts shape this class. The ledger owns the account and stores no name for it — eleven
 * columns since V1, none of them human-readable — so a chart of accounts has to live somewhere
 * else. And hard rule 3 says orchestration is the only module that may address the ledger, so
 * "somewhere else" is here, next to the client, rather than in a module that would have to reach
 * across a boundary to do it.
 *
 * <p>The order of the two writes is deliberate and the failure mode is stated rather than hidden:
 * the ledger account is opened first, under a key derived from the tenant and the code. If the
 * register insert then fails — a duplicate code, a lost connection — the ledger holds an account
 * nothing names. Retrying the same request re-derives the same key, so the ledger returns the
 * original account rather than opening a second, and the register write is retried against it. The
 * alternative, registering first and opening after, would leave a named account that does not
 * exist, and every screen that offered it would be offering a UUID the money path would reject.
 */
@Repository
public class InternalAccounts implements InstitutionAccounts {

    /**
     * What the bank means by an account, mapped to what the ledger does with it.
     *
     * <p>The mapping is made once, here, rather than asked of whoever fills in the form: an
     * administrator knows they are opening a fee income account and should not have to know that
     * the ledger calls it {@code FEE}, or which of six types a suspense account takes.
     */
    public enum Purpose {
        TILL("INTERNAL"),
        VAULT("INTERNAL"),
        FEE_INCOME("FEE"),
        INTEREST_INCOME("INTERNAL"),
        PENALTY_INCOME("INTERNAL"),
        SUSPENSE("SUSPENSE"),
        SETTLEMENT("SETTLEMENT_MIRROR"),
        OTHER("INTERNAL");

        private final String ledgerType;

        Purpose(String ledgerType) {
            this.ledgerType = ledgerType;
        }

        public String ledgerType() {
            return ledgerType;
        }
    }

    public record InternalAccount(
            UUID id,
            UUID ledgerAccountId,
            String code,
            String name,
            String purpose,
            String currency,
            String status,
            String openedAt,
            String openedBy) {}

    private final JdbcTemplate jdbc;
    private final LedgerClient ledger;

    public InternalAccounts(
            @Qualifier("orchestrationJdbcTemplate") JdbcTemplate orchestrationJdbcTemplate, LedgerClient ledger) {
        this.jdbc = orchestrationJdbcTemplate;
        this.ledger = ledger;
    }

    private void scopeTo(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }

    /** The register, newest last, so a list reads in the order the institution built it. */
    @Transactional(readOnly = true, transactionManager = "orchestrationTransactionManager")
    public List<InternalAccount> list(UUID tenantId) {
        scopeTo(tenantId);
        return jdbc.query(
                "SELECT id, ledger_account_id, code, name, purpose, currency, status, opened_at, opened_by"
                        + " FROM orchestration.internal_accounts"
                        + " WHERE tenant_id = ? ORDER BY purpose, lower(code)",
                (rs, row) ->
                        new InternalAccount(
                                rs.getObject("id", UUID.class),
                                rs.getObject("ledger_account_id", UUID.class),
                                rs.getString("code"),
                                rs.getString("name"),
                                rs.getString("purpose"),
                                rs.getString("currency"),
                                rs.getString("status"),
                                String.valueOf(rs.getObject("opened_at")),
                                rs.getString("opened_by")),
                tenantId);
    }

    /** One account by the code the institution gave it, or null. */
    @Transactional(readOnly = true, transactionManager = "orchestrationTransactionManager")
    public InternalAccount byCode(UUID tenantId, String code) {
        scopeTo(tenantId);
        List<InternalAccount> found = jdbc.query(
                "SELECT id, ledger_account_id, code, name, purpose, currency, status, opened_at, opened_by"
                        + " FROM orchestration.internal_accounts"
                        + " WHERE tenant_id = ? AND lower(code) = lower(?)",
                (rs, row) ->
                        new InternalAccount(
                                rs.getObject("id", UUID.class),
                                rs.getObject("ledger_account_id", UUID.class),
                                rs.getString("code"),
                                rs.getString("name"),
                                rs.getString("purpose"),
                                rs.getString("currency"),
                                rs.getString("status"),
                                String.valueOf(rs.getObject("opened_at")),
                                rs.getString("opened_by")),
                tenantId,
                code);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Opens an account in the ledger and registers what it is called.
     *
     * <p>Every account opened here permits a negative balance, which is worth explaining because it
     * looks like a missing guard. The ledger is credit-positive: a till being handed cash is
     * <em>debited</em> ({@code CashService}), so a working till's balance is negative by
     * construction. Refusing negative balances on
     * the institution's own accounts would not prevent an error — it would strand a saga mid-flight
     * on an account that was always going to go that way. The guard belongs on customer money,
     * where a negative balance means the customer spent what they did not have, and that is where
     * account opening for customers sets it.
     */
    @Transactional(transactionManager = "orchestrationTransactionManager")
    public InternalAccount open(
            UUID tenantId, String code, String name, Purpose purpose, String currency, String openedBy) {
        String cleanCode = require(code, "code").trim();
        String cleanName = require(name, "name").trim();
        String cleanCurrency = require(currency, "currency").trim().toUpperCase(Locale.ROOT);
        if (cleanCurrency.length() != 3) {
            throw new CoreException(
                    ErrorCode.COMMAND_INVALID, null, "currency must be a 3-letter ISO 4217 code",
                    Map.of(DetailKey.FIELD, "currency"));
        }

        scopeTo(tenantId);
        InternalAccount existing = byCode(tenantId, cleanCode);
        if (existing != null) {
            // Not idempotent-success: the caller believes they are opening a new account and
            // agreeing would hide that they are looking at somebody else's.
            throw new CodeTaken(cleanCode);
        }

        // Derived from the tenant and the code, so a retry after a failed register write asks the
        // ledger for the same account rather than opening a second one.
        String idempotencyKey = "core-internal-account:" + tenantId + ":" + cleanCode.toLowerCase(Locale.ROOT);

        var opened = ledger.open(
                tenantId,
                new LedgerClient.OpenAccount(
                        idempotencyKey,
                        purpose.ledgerType(),
                        cleanCurrency,
                        // The ledger stores no PII, and this is the institution's own account, so
                        // the reference is the code an operator would recognise in a ledger export.
                        "internal:" + cleanCode,
                        true));
        if (!opened.ok()) {
            throw new LedgerRefused(opened.failure());
        }

        UUID id;
        try {
            id = jdbc.queryForObject(
                    "INSERT INTO orchestration.internal_accounts"
                            + " (tenant_id, ledger_account_id, code, name, purpose, currency, opened_by)"
                            + " VALUES (?,?,?,?,?,?,?) RETURNING id",
                    UUID.class,
                    tenantId,
                    opened.accountId(),
                    cleanCode,
                    cleanName,
                    purpose.name(),
                    cleanCurrency,
                    openedBy);
        } catch (org.springframework.dao.DuplicateKeyException raced) {
            // Two opens raced past the byCode check above; internal_accounts_code_per_tenant picked
            // the winner. The loser gets the same 409 the check would have given a moment later —
            // not a 500 for a race they could not see. The ledger open above was idempotent on the
            // derived key, so both racers were handed the same ledger account and nothing leaks.
            throw new CodeTaken(cleanCode);
        }

        return new InternalAccount(
                id,
                opened.accountId(),
                cleanCode,
                cleanName,
                purpose.name(),
                cleanCurrency,
                "ACTIVE",
                null,
                openedBy);
    }

    // --- the published port ---------------------------------------------------------------------

    /**
     * Every account, as a neighbour sees them.
     *
     * <p>The port's shape rather than this class's: {@code InternalAccount} carries who opened it
     * and when, which is the administration screen's business and nobody else's.
     */
    @Override
    @Transactional(readOnly = true, transactionManager = "orchestrationTransactionManager")
    public List<InstitutionAccounts.Account> all(UUID tenantId) {
        return list(tenantId).stream()
                .map(a -> new InstitutionAccounts.Account(
                        a.id(), a.ledgerAccountId(), a.code(), a.name(), a.purpose(), a.currency(), a.status()))
                .toList();
    }

    /**
     * Opens a customer's account.
     *
     * <p>No register row: the institution's own accounts are named here because nothing else names
     * them, and a customer's account is named by the customer module — by its holder and its
     * account number. Two registers for one account would be two answers to "whose is this".
     *
     * <p>Idempotent on the customer reference and currency, so a retry after a lost response links
     * the account that was already opened rather than opening a second one for the same person.
     */
    @Override
    public InstitutionAccounts.Opened openForCustomer(UUID tenantId, String customerRef, String currency) {
        var opened = ledger.open(
                tenantId,
                new LedgerClient.OpenAccount(
                        "core-customer-account:" + tenantId + ":" + customerRef + ":" + currency,
                        "CUSTOMER",
                        currency,
                        customerRef,
                        // The one place the guard means something: a customer balance below zero is
                        // money that was not there being spent.
                        false));
        return new InstitutionAccounts.Opened(opened.accountId(), opened.failure());
    }

    @Override
    @Transactional(readOnly = true, transactionManager = "orchestrationTransactionManager")
    public InstitutionAccounts.Account byLedgerAccountId(UUID tenantId, UUID ledgerAccountId) {
        if (ledgerAccountId == null) {
            return null;
        }
        scopeTo(tenantId);
        List<InstitutionAccounts.Account> found = jdbc.query(
                "SELECT id, ledger_account_id, code, name, purpose, currency, status"
                        + " FROM orchestration.internal_accounts"
                        + " WHERE tenant_id = ? AND ledger_account_id = ?",
                (rs, row) -> new InstitutionAccounts.Account(
                        rs.getObject("id", UUID.class),
                        rs.getObject("ledger_account_id", UUID.class),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("purpose"),
                        rs.getString("currency"),
                        rs.getString("status")),
                tenantId,
                ledgerAccountId);
        return found.isEmpty() ? null : found.get(0);
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new CoreException(
                    ErrorCode.COMMAND_INVALID, null, field + " is required", Map.of(DetailKey.FIELD, field));
        }
        return value;
    }

    /** The institution already has an account by that code. */
    public static class CodeTaken extends RuntimeException {
        public final String code;

        public CodeTaken(String code) {
            super("internal account code taken: " + code);
            this.code = code;
        }
    }

    /** The ledger would not open it. The reason is the ledger's own, passed through unchanged. */
    public static class LedgerRefused extends RuntimeException {
        public final String reason;

        public LedgerRefused(String reason) {
            super("ledger refused to open the account: " + reason);
            this.reason = reason;
        }
    }
}
