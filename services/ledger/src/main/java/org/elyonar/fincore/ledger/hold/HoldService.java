package org.elyonar.fincore.ledger.hold;

import java.util.UUID;
import org.elyonar.fincore.ledger.shared.ErrorCode;
import org.elyonar.fincore.ledger.shared.LedgerException;
import org.elyonar.fincore.ledger.tenant.TenantScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Places and releases holds. Capture lives in the posting engine, deliberately — see
 * {@code package-info}.
 *
 * <p>A hold moves no money. It reduces {@code available} (= current − holds_total) so that funds
 * committed to an in-flight operation cannot be spent twice, and it does so under the same balance
 * row lock every posting takes, which is what makes the two safe against each other.
 */
@Service
public class HoldService {

    private final TenantScope tenantScope;
    private final JdbcTemplate jdbc;

    public HoldService(TenantScope tenantScope, JdbcTemplate jdbc) {
        this.tenantScope = tenantScope;
        this.jdbc = jdbc;
    }

    public UUID place(PlaceHoldCommand command) {
        return tenantScope.inTenant(command.tenantId(), () -> doPlace(command));
    }

    private UUID doPlace(PlaceHoldCommand command) {
        if (command.expiresAt() == null) {
            throw new LedgerException(
                    ErrorCode.LIMIT_EXCEEDED, "a hold must carry an expiry: an unbounded hold is a permanent lien");
        }
        if (command.amountMinor() <= 0) {
            throw new LedgerException(ErrorCode.LIMIT_EXCEEDED, "hold amount must be positive");
        }

        // Placement is idempotent on the caller's key, so a retried placement can never
        // double-reserve the same funds.
        var existing =
                jdbc.query(
                        "SELECT id FROM holds WHERE tenant_id = ? AND idempotency_key = ?",
                        rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                        command.tenantId(),
                        command.idempotencyKey());
        if (existing != null) {
            return existing;
        }

        lockBalance(command.tenantId(), command.accountId());

        var account =
                jdbc.query(
                        "SELECT status, currency, allow_negative FROM accounts WHERE tenant_id = ? AND id = ?",
                        rs ->
                                rs.next()
                                        ? new Object[] {rs.getString(1), rs.getString(2).trim(), rs.getBoolean(3)}
                                        : null,
                        command.tenantId(),
                        command.accountId());
        if (account == null) {
            throw new LedgerException(ErrorCode.ACCOUNT_NOT_FOUND, "unknown account " + command.accountId());
        }
        if (!"OPEN".equals(account[0])) {
            throw new LedgerException(ErrorCode.ACCOUNT_CLOSED, "cannot reserve funds on a closed account");
        }
        if (!account[1].equals(command.currency())) {
            throw new LedgerException(
                    ErrorCode.CURRENCY_MISMATCH, "hold currency does not match the account's currency");
        }

        boolean guarded = !((Boolean) account[2]);
        if (guarded) {
            Long available =
                    jdbc.queryForObject(
                            "SELECT current_minor - holds_total_minor FROM balances WHERE tenant_id = ? AND account_id = ?",
                            Long.class,
                            command.tenantId(),
                            command.accountId());
            if (available == null || available < command.amountMinor()) {
                throw new LedgerException(
                        ErrorCode.INSUFFICIENT_FUNDS, "not enough available balance to reserve that amount");
            }
        }

        UUID holdId = UUID.randomUUID();
        int inserted =
                jdbc.update(
                        """
                        INSERT INTO holds (id, tenant_id, idempotency_key, account_id, amount_minor,
                                           currency, status, expires_at)
                        VALUES (?,?,?,?,?,?, 'ACTIVE', ?)
                        ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                        """,
                        holdId,
                        command.tenantId(),
                        command.idempotencyKey(),
                        command.accountId(),
                        command.amountMinor(),
                        command.currency(),
                        java.sql.Timestamp.from(command.expiresAt()));

        if (inserted == 0) {
            // Lost a concurrent race on the same key. As in posting, ON CONFLICT rather than a
            // caught violation: a raised error would abort the transaction and make this re-read
            // impossible.
            return jdbc.queryForObject(
                    "SELECT id FROM holds WHERE tenant_id = ? AND idempotency_key = ?",
                    UUID.class,
                    command.tenantId(),
                    command.idempotencyKey());
        }

        jdbc.update(
                """
                UPDATE balances SET holds_total_minor = holds_total_minor + ?, updated_at = now()
                 WHERE tenant_id = ? AND account_id = ?
                """,
                command.amountMinor(),
                command.tenantId(),
                command.accountId());

        return holdId;
    }

    /**
     * Releases a hold, reporting the transition that actually happened.
     *
     * <p>The outcome is never collapsed into success. A caller that believes funds are reserved,
     * when they were expired or already captured, will make a wrong decision next.
     */
    public HoldReleaseOutcome release(UUID tenantId, UUID holdId) {
        return tenantScope.inTenant(tenantId, () -> doRelease(tenantId, holdId));
    }

    private HoldReleaseOutcome doRelease(UUID tenantId, UUID holdId) {
        var hold =
                jdbc.query(
                        "SELECT account_id, amount_minor, status FROM holds WHERE tenant_id = ? AND id = ?",
                        rs ->
                                rs.next()
                                        ? new Object[] {rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3)}
                                        : null,
                        tenantId,
                        holdId);
        if (hold == null) {
            throw new LedgerException(ErrorCode.ACCOUNT_NOT_FOUND, "unknown hold " + holdId);
        }

        UUID accountId = (UUID) hold[0];
        long amount = (Long) hold[1];
        String status = (String) hold[2];

        switch (status) {
            case "RELEASED" -> {
                return HoldReleaseOutcome.ALREADY_RELEASED;
            }
            case "EXPIRED" -> {
                return HoldReleaseOutcome.ALREADY_EXPIRED;
            }
            case "CONSUMED" -> {
                return HoldReleaseOutcome.ALREADY_CONSUMED;
            }
            default -> {
                // ACTIVE: fall through and release under the balance row lock.
            }
        }

        lockBalance(tenantId, accountId);

        // Re-read under the lock. Between the read above and the lock, an expiry sweep or a
        // capture may have resolved this hold; the row we now see is the authority.
        String current =
                jdbc.queryForObject(
                        "SELECT status FROM holds WHERE tenant_id = ? AND id = ?", String.class, tenantId, holdId);
        switch (current) {
            case "RELEASED" -> {
                return HoldReleaseOutcome.ALREADY_RELEASED;
            }
            case "EXPIRED" -> {
                return HoldReleaseOutcome.ALREADY_EXPIRED;
            }
            case "CONSUMED" -> {
                return HoldReleaseOutcome.ALREADY_CONSUMED;
            }
            default -> {
                // still ACTIVE, and now under our lock
            }
        }

        jdbc.update(
                "UPDATE holds SET status='RELEASED', resolved_at=now() WHERE tenant_id = ? AND id = ?",
                tenantId,
                holdId);
        jdbc.update(
                """
                UPDATE balances SET holds_total_minor = holds_total_minor - ?, updated_at = now()
                 WHERE tenant_id = ? AND account_id = ?
                """,
                amount,
                tenantId,
                accountId);

        return HoldReleaseOutcome.RELEASED_NOW;
    }

    /** Non-mutating state read: a crashed orchestrator asks, rather than probing by releasing. */
    public HoldView find(UUID tenantId, UUID holdId) {
        return tenantScope.inTenant(
                tenantId,
                () ->
                        jdbc.query(
                                """
                                SELECT id, account_id, amount_minor, currency, status, expires_at
                                  FROM holds WHERE tenant_id = ? AND id = ?
                                """,
                                rs ->
                                        rs.next()
                                                ? new HoldView(
                                                        rs.getObject(1, UUID.class),
                                                        rs.getObject(2, UUID.class),
                                                        rs.getLong(3),
                                                        rs.getString(4).trim(),
                                                        rs.getString(5),
                                                        rs.getTimestamp(6).toInstant())
                                                : null,
                                tenantId,
                                holdId));
    }

    private void lockBalance(UUID tenantId, UUID accountId) {
        Integer locked =
                jdbc.query(
                        "SELECT 1 FROM balances WHERE tenant_id = ? AND account_id = ? FOR UPDATE",
                        rs -> rs.next() ? 1 : 0,
                        tenantId,
                        accountId);
        if (locked == null || locked == 0) {
            throw new LedgerException(ErrorCode.ACCOUNT_NOT_FOUND, "unknown account " + accountId);
        }
    }
}
