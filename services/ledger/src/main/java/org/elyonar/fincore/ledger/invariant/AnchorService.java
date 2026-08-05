package org.elyonar.fincore.ledger.invariant;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.elyonar.fincore.ledger.tenant.TenantScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Daily balance anchors: the checkpoints that make invariant verification affordable.
 *
 * <p>Summing seven years of entries hourly on the operational primary stops being possible long
 * before seven years are up — at 200 TPS that is roughly 17M entries a day. An anchor is a
 * balance proven once, against a stated entry-id bound, so every later check only has to sum the
 * entries written since. The cost of verification becomes proportional to recent activity rather
 * than to all history.
 *
 * <p>Two properties make an anchor trustworthy, and both are easy to get wrong:
 *
 * <ul>
 *   <li><strong>It keys on physical insertion order, never on value date.</strong> Backdating is
 *       permitted, so a business-dated anchor could be falsified after the fact by a posting
 *       dated into a period it had already summed. A backdated posting is a <em>new</em> entry
 *       with a <em>higher</em> id, so it lands in the current delta window like any other and
 *       cannot disturb a bound already taken.
 *   <li><strong>Its bound is finalized at the quiesce horizon.</strong> Ids are assigned at
 *       insert, not commit, so an entry can still commit below a bound that has already been
 *       proven — after which the anchor is wrong forever and every check that trusted it is
 *       wrong too, silently.
 * </ul>
 *
 * <p>Anchors are trigger-protected against update and delete, because everything since has
 * trusted them.
 */
@Service
public class AnchorService {

    private final TenantScope tenantScope;
    private final JdbcTemplate jdbc;
    private final QuiesceHorizon horizon;

    public AnchorService(TenantScope tenantScope, JdbcTemplate jdbc, QuiesceHorizon horizon) {
        this.tenantScope = tenantScope;
        this.jdbc = jdbc;
        this.horizon = horizon;
    }

    /**
     * Captures today's anchor for every account of a tenant. Returns how many were written.
     *
     * <p>Idempotent per day: a second run finds the row already present and leaves it alone, since
     * re-proving an anchor at a later bound would silently change what earlier checks relied on.
     */
    public int captureFor(UUID tenantId, LocalDate on) {
        Optional<Long> settled = horizon.settledEntryIdBound(tenantId);
        if (settled.isEmpty()) {
            return 0; // nothing has settled yet; the next capture will pick it up
        }
        long bound = settled.get();

        return tenantScope.inTenant(
                tenantId,
                () ->
                        jdbc.update(
                                """
                                INSERT INTO balance_anchors
                                    (tenant_id, account_id, captured_on, entry_id_upper_bound, balance_minor)
                                SELECT a.tenant_id,
                                       a.id,
                                       ?,
                                       ?,
                                       COALESCE((
                                           SELECT SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount_minor
                                                           ELSE -e.amount_minor END)
                                             FROM entries e
                                            WHERE e.tenant_id = a.tenant_id
                                              AND e.account_id = a.id
                                              AND e.id <= ?
                                       ), 0)
                                  FROM accounts a
                                 WHERE a.tenant_id = ?
                                ON CONFLICT (tenant_id, account_id, captured_on) DO NOTHING
                                """,
                                on,
                                bound,
                                bound,
                                tenantId));
    }

    /**
     * Verifies every anchored account incrementally: anchor + entries since = current balance.
     *
     * <p>This is the check that runs hourly. It touches only entries written since the most recent
     * anchor, so its cost tracks recent activity rather than all history.
     */
    public List<Finding> verifyIncrementally(UUID tenantId) {
        return tenantScope.inTenant(
                tenantId,
                () ->
                        jdbc.query(
                                """
                                WITH newest AS (
                                    SELECT DISTINCT ON (account_id)
                                           account_id, entry_id_upper_bound, balance_minor
                                      FROM balance_anchors
                                     WHERE tenant_id = ?
                                     ORDER BY account_id, captured_on DESC
                                )
                                SELECT n.account_id,
                                       n.balance_minor,
                                       COALESCE(d.delta, 0) AS delta,
                                       b.current_minor
                                  FROM newest n
                                  JOIN balances b
                                    ON b.tenant_id = ? AND b.account_id = n.account_id
                                  LEFT JOIN LATERAL (
                                        SELECT SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount_minor
                                                        ELSE -e.amount_minor END) AS delta
                                          FROM entries e
                                         WHERE e.tenant_id = ?
                                           AND e.account_id = n.account_id
                                           AND e.id > n.entry_id_upper_bound
                                  ) d ON TRUE
                                 WHERE n.balance_minor + COALESCE(d.delta, 0) <> b.current_minor
                                """,
                                (rs, i) ->
                                        Finding.violation(
                                                "anchor_plus_delta_matches_balance",
                                                rs.getString(1),
                                                "anchor "
                                                        + rs.getLong(2)
                                                        + " plus delta "
                                                        + rs.getLong(3)
                                                        + " gives "
                                                        + (rs.getLong(2) + rs.getLong(3))
                                                        + " but the balance reads "
                                                        + rs.getLong(4)),
                                tenantId,
                                tenantId,
                                tenantId));
    }

    /** Tenants that have any accounts, so the capture job knows where to go. */
    public List<UUID> tenantsWithAccounts() {
        // Read outside tenant scope deliberately: this is infrastructure that serves every tenant
        // and must discover them before it can scope itself. Only tenant ids are read; every
        // balance is touched inside that tenant's own scope.
        return jdbc.query("SELECT DISTINCT tenant_id FROM accounts", (rs, i) -> rs.getObject(1, UUID.class));
    }
}
