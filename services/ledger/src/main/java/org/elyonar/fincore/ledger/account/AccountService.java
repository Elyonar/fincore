package org.elyonar.fincore.ledger.account;

import java.util.List;
import java.util.UUID;
import org.elyonar.fincore.ledger.outbox.LedgerEvent;
import org.elyonar.fincore.ledger.outbox.OutboxWriter;
import org.elyonar.fincore.ledger.shared.ErrorCode;
import org.elyonar.fincore.ledger.shared.ErrorReason;
import org.elyonar.fincore.ledger.shared.LedgerException;
import org.elyonar.fincore.ledger.tenant.TenantScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.elyonar.fincore.ledger.shared.AccountStatus;

/**
 * Opening, reading and closing accounts.
 *
 * <p>An account and its balance row are created in one transaction, so a posting can never find an
 * account without the row it must lock. Creation is idempotent on the caller's key: a retried
 * creation returns the original rather than orphaning a duplicate that would then quietly receive
 * half the tenant's traffic.
 */
@Service
public class AccountService {

    private final TenantScope tenantScope;
    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;

    public AccountService(TenantScope tenantScope, JdbcTemplate jdbc, OutboxWriter outbox) {
        this.tenantScope = tenantScope;
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    public UUID open(OpenAccountCommand command) {
        return tenantScope.inTenant(command.tenantId(), () -> doOpen(command));
    }

    private UUID doOpen(OpenAccountCommand command) {
        UUID existing =
                jdbc.query(
                        "SELECT id FROM accounts WHERE tenant_id = ? AND idempotency_key = ?",
                        rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                        command.tenantId(),
                        command.idempotencyKey());
        if (existing != null) {
            return existing;
        }

        // Asked before the insert rather than left to the foreign key. The constraint would refuse
        // it either way, but as a DataIntegrityViolation — a 500 carrying a Postgres constraint
        // name, which tells a caller its request may have committed and should be retried on the
        // same key. It never will. An institution that has configured a currency this ledger does
        // not carry needs a terminal 422 naming the currency, not an outage.
        Boolean known =
                jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM currencies WHERE code = ?)",
                        Boolean.class,
                        command.currency());
        if (!Boolean.TRUE.equals(known)) {
            throw new LedgerException(
                    ErrorCode.CURRENCY_UNKNOWN,
                    ErrorReason.UNKNOWN_CURRENCY,
                    "currency " + command.currency() + " is not in the ledger's registry",
                    java.util.Map.of("currency", command.currency()));
        }

        UUID id = UUID.randomUUID();
        int created =
                jdbc.update(
                        """
                        INSERT INTO accounts (id, tenant_id, idempotency_key, type, currency,
                                              customer_ref, group_ref, allow_negative)
                        VALUES (?,?,?,?,?,?,?,?)
                        ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                        """,
                        id,
                        command.tenantId(),
                        command.idempotencyKey(),
                        command.type(),
                        command.currency(),
                        command.customerRef(),
                        command.groupRef(),
                        command.allowNegative());
        if (created == 0) {
            return jdbc.queryForObject(
                    "SELECT id FROM accounts WHERE tenant_id = ? AND idempotency_key = ?",
                    UUID.class,
                    command.tenantId(),
                    command.idempotencyKey());
        }

        // Same transaction: a posting must never find an account whose balance row is missing.
        jdbc.update("INSERT INTO balances (account_id, tenant_id) VALUES (?,?)", id, command.tenantId());

        outbox.write(
                command.tenantId(),
                LedgerEvent.ACCOUNT_CREATED,
                id,
                java.util.Map.of("accountId", id.toString(), "type", command.type(), "currency", command.currency()));
        return id;
    }

    public AccountView find(UUID tenantId, UUID accountId) {
        AccountView view = tenantScope.inTenant(tenantId, () -> read(tenantId, accountId));
        if (view == null) {
            throw new LedgerException(ErrorCode.ACCOUNT_NOT_FOUND, "unknown account " + accountId);
        }
        return view;
    }

    private AccountView read(UUID tenantId, UUID accountId) {
        return jdbc.query(
                """
                SELECT a.id, a.type, a.currency, a.status, a.customer_ref, a.group_ref, a.allow_negative,
                       b.current_minor, b.holds_total_minor
                  FROM accounts a JOIN balances b ON b.tenant_id = a.tenant_id AND b.account_id = a.id
                 WHERE a.tenant_id = ? AND a.id = ?
                """,
                rs ->
                        rs.next()
                                ? new AccountView(
                                        rs.getObject(1, UUID.class),
                                        rs.getString(2),
                                        rs.getString(3).trim(),
                                        rs.getString(4),
                                        rs.getString(5),
                                        rs.getString(6),
                                        rs.getBoolean(7),
                                        rs.getLong(8),
                                        rs.getLong(9))
                                : null,
                tenantId,
                accountId);
    }

    /**
     * Closes an account.
     *
     * <p>Requires a zero balance and no active holds, and takes the balance row lock so an
     * in-flight posting cannot slip between the check and the close. There is no reopen: a
     * reopenable account would make "closed" mean nothing, and a fresh account costs nothing.
     */
    public void close(UUID tenantId, UUID accountId, String closedBy) {
        tenantScope.inTenant(
                tenantId,
                () -> {
                    Integer locked =
                            jdbc.query(
                                    "SELECT 1 FROM balances WHERE tenant_id = ? AND account_id = ? FOR UPDATE",
                                    rs -> rs.next() ? 1 : 0,
                                    tenantId,
                                    accountId);
                    if (locked == null || locked == 0) {
                        throw new LedgerException(ErrorCode.ACCOUNT_NOT_FOUND, "unknown account " + accountId);
                    }

                    AccountView account = read(tenantId, accountId);
                    if (!AccountStatus.of(account.status()).isOpen()) {
                        throw new LedgerException(ErrorCode.CLOSE_BLOCKED, "account is already closed");
                    }
                    if (account.currentMinor() != 0L) {
                        throw new LedgerException(
                                ErrorCode.CLOSE_BLOCKED, "account still holds a balance; sweep it to zero first");
                    }
                    Long activeHolds =
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM holds WHERE tenant_id = ? AND account_id = ? AND status='ACTIVE'",
                                    Long.class,
                                    tenantId,
                                    accountId);
                    if (activeHolds != null && activeHolds > 0) {
                        throw new LedgerException(
                                ErrorCode.CLOSE_BLOCKED, "account has active holds; release them first");
                    }

                    jdbc.update(
                            "UPDATE accounts SET status='CLOSED', closed_by=?, closed_at=now()"
                                    + " WHERE tenant_id = ? AND id = ?",
                            closedBy,
                            tenantId,
                            accountId);
                    outbox.write(
                            tenantId,
                            LedgerEvent.ACCOUNT_CLOSED,
                            accountId,
                            java.util.Map.of("accountId", accountId.toString(), "closedBy", closedBy));
                });
    }

    /**
     * Summed balance across a fan-in shard group.
     *
     * <p>{@code group_ref} is the one identifier not covered by a composite foreign key, so this
     * sum is scoped by row-level security rather than by a key: members resolve through
     * {@code accounts}, which RLS already confines to the calling tenant. Two tenants may use the
     * same group label without ever seeing each other's shards.
     */
    public GroupBalance groupBalance(UUID tenantId, String groupRef) {
        return tenantScope.inTenant(
                tenantId,
                () ->
                        jdbc.query(
                                """
                                SELECT count(*), COALESCE(SUM(b.current_minor),0), COALESCE(SUM(b.holds_total_minor),0)
                                  FROM accounts a JOIN balances b
                                    ON b.tenant_id = a.tenant_id AND b.account_id = a.id
                                 WHERE a.tenant_id = ? AND a.group_ref = ?
                                """,
                                rs ->
                                        rs.next()
                                                ? new GroupBalance(groupRef, rs.getInt(1), rs.getLong(2), rs.getLong(3))
                                                : new GroupBalance(groupRef, 0, 0L, 0L),
                                tenantId,
                                groupRef));
    }

    public List<HoldSummary> holdsOn(UUID tenantId, UUID accountId, String statusFilter) {
        return tenantScope.inTenant(
                tenantId,
                () ->
                        jdbc.query(
                                """
                                SELECT id, amount_minor, currency, status, expires_at
                                  FROM holds
                                 WHERE tenant_id = ? AND account_id = ?
                                   AND (?::text IS NULL OR status = ?)
                                 ORDER BY placed_at DESC
                                """,
                                (rs, i) ->
                                        new HoldSummary(
                                                rs.getObject(1, UUID.class),
                                                rs.getLong(2),
                                                rs.getString(3).trim(),
                                                rs.getString(4),
                                                rs.getTimestamp(5).toInstant()),
                                tenantId,
                                accountId,
                                statusFilter,
                                statusFilter));
    }

    public record AccountView(
            UUID id,
            String type,
            String currency,
            String status,
            String customerRef,
            String groupRef,
            boolean allowNegative,
            long currentMinor,
            long holdsTotalMinor) {

        /** What may actually be spent: the quantity the guard and Invariant 4 operate on. */
        public long availableMinor() {
            return currentMinor - holdsTotalMinor;
        }
    }

    public record GroupBalance(String groupRef, int memberCount, long currentMinor, long holdsTotalMinor) {}

    public record HoldSummary(
            UUID id, long amountMinor, String currency, String status, java.time.Instant expiresAt) {}
}
