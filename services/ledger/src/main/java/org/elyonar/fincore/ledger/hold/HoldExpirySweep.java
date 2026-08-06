package org.elyonar.fincore.ledger.hold;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.elyonar.fincore.ledger.outbox.LedgerEvent;
import org.elyonar.fincore.ledger.outbox.OutboxWriter;
import org.elyonar.fincore.ledger.tenant.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.elyonar.fincore.ledger.shared.HoldStatus;

/**
 * Expires holds whose time has run out, returning the funds to available balance.
 *
 * <p>An expiry is not a bookkeeping detail: until it happens the customer's money is still
 * reserved and unspendable, so a sweep that stalls is indistinguishable from the bank quietly
 * freezing funds.
 *
 * <p>Two rules make it safe against the rest of the system. It takes the same balance row lock
 * every posting takes, so a capture racing an expiry has exactly one winner rather than both
 * adjusting {@code holds_total}. And it re-reads each hold's status under that lock — between
 * selecting a batch and acting on it, a capture or a release may already have resolved the hold,
 * and the row under the lock is the only authority.
 *
 * <p>Never a bulk {@code UPDATE ... WHERE expires_at < now()}: that would move reservations
 * without holding the locks that make balances consistent.
 *
 * <p>The work is a bean in its own right and the timer lives in {@link HoldExpiryScheduler}. Tests
 * disable the timer and drive a pass directly, so assertions are never racing a background thread —
 * but the code under test is the same code that runs in production, rather than a bean that
 * vanishes when scheduling is switched off.
 */
@Component
public class HoldExpirySweep {

    private static final Logger log = LoggerFactory.getLogger(HoldExpirySweep.class);

    private final TenantScope tenantScope;
    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;

    public HoldExpirySweep(TenantScope tenantScope, JdbcTemplate jdbc, OutboxWriter outbox) {
        this.tenantScope = tenantScope;
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    /** One pass over every tenant with expired holds. Driven by the scheduler, or directly in tests. */
    public void sweep() {
        try {
            for (UUID tenantId : tenantsWithExpiredHolds()) {
                int expired = expireFor(tenantId, 100);
                if (expired > 0) {
                    log.info("expired {} holds for tenant {}", expired, tenantId);
                }
            }
        } catch (RuntimeException e) {
            log.error("hold expiry sweep failed; will retry on the next tick", e);
        }
    }

    /**
     * Which tenants have work waiting.
     *
     * <p>Read outside tenant scope on purpose: the sweep is infrastructure serving every tenant,
     * and it needs to know where to go before it can scope itself. Only tenant ids are read here —
     * the money is touched inside {@link #expireFor}, under that tenant's own scope and locks.
     */
    private List<UUID> tenantsWithExpiredHolds() {
        return jdbc.query(
                "SELECT DISTINCT tenant_id FROM holds WHERE status = 'ACTIVE' AND expires_at <= now()",
                (rs, i) -> rs.getObject(1, UUID.class));
    }

    @Transactional
    public int expireFor(UUID tenantId, int batchSize) {
        return tenantScope.inTenant(
                tenantId,
                () -> {
                    List<Object[]> candidates =
                            jdbc.query(
                                    """
                                    SELECT id, account_id, amount_minor
                                      FROM holds
                                     WHERE tenant_id = ? AND status = 'ACTIVE' AND expires_at <= now()
                                     ORDER BY expires_at
                                     LIMIT ?
                                    """,
                                    (rs, i) ->
                                            new Object[] {
                                                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getLong(3)
                                            },
                                    tenantId,
                                    batchSize);

                    int expired = 0;
                    for (Object[] candidate : candidates) {
                        UUID holdId = (UUID) candidate[0];
                        UUID accountId = (UUID) candidate[1];
                        long amount = (Long) candidate[2];

                        jdbc.query(
                                "SELECT 1 FROM balances WHERE tenant_id = ? AND account_id = ? FOR UPDATE",
                                rs -> rs.next() ? 1 : 0,
                                tenantId,
                                accountId);

                        // Under the lock now: a capture may have consumed this hold since the
                        // batch was selected, and its status is the authority, not our snapshot.
                        String status =
                                jdbc.queryForObject(
                                        "SELECT status FROM holds WHERE tenant_id = ? AND id = ?",
                                        String.class,
                                        tenantId,
                                        holdId);
                        if (!HoldStatus.of(status).isActive()) {
                            continue;
                        }

                        jdbc.update(
                                "UPDATE holds SET status='EXPIRED', resolved_at=now()"
                                        + " WHERE tenant_id = ? AND id = ?",
                                tenantId,
                                holdId);
                        jdbc.update(
                                "UPDATE balances SET holds_total_minor = holds_total_minor - ?, updated_at = now()"
                                        + " WHERE tenant_id = ? AND account_id = ?",
                                amount,
                                tenantId,
                                accountId);
                        outbox.write(
                                tenantId,
                                LedgerEvent.HOLD_RELEASED,
                                holdId,
                                Map.of(
                                        "holdId", holdId.toString(),
                                        "reason", "expired",
                                        "releasedRemainderMinor", amount));
                        expired++;
                    }
                    return expired;
                });
    }
}
