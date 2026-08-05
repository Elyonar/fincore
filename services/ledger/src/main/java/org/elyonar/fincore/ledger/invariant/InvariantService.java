package org.elyonar.fincore.ledger.invariant;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.elyonar.fincore.ledger.tenant.TenantScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The six invariants, checked per tenant and per currency.
 *
 * <p>These are the ledger's argument for its own correctness. Each is written as a query that
 * returns the counterexamples rather than a boolean, because "something is wrong" is not
 * actionable at three in the morning and "account X's balance disagrees with its entries by ₦450"
 * is.
 *
 * <p>Every check compares the ledger against itself — derived state against the entries it was
 * derived from — so a bug in the posting path shows up as a disagreement rather than being
 * invisibly consistent.
 */
@Service
public class InvariantService {

    private final TenantScope tenantScope;
    private final JdbcTemplate jdbc;

    public InvariantService(TenantScope tenantScope, JdbcTemplate jdbc) {
        this.tenantScope = tenantScope;
        this.jdbc = jdbc;
    }

    public InvariantReport verify(UUID tenantId) {
        Instant startedAt = Instant.now();
        List<Finding> findings =
                tenantScope.inTenant(
                        tenantId,
                        () -> {
                            List<Finding> all = new ArrayList<>();
                            all.addAll(moneyIsConserved(tenantId));
                            all.addAll(balancesMatchEntries(tenantId));
                            all.addAll(holdsAddUp(tenantId));
                            all.addAll(noUnexplainedNegatives(tenantId));
                            all.addAll(reversalsAreExactAndExclusive(tenantId));
                            all.addAll(terminalStatesAreTerminal(tenantId));
                            return all;
                        });

        InvariantReport report =
                new InvariantReport(null, tenantId, startedAt, Instant.now(), "INCREMENTAL", findings);
        return persist(report);
    }

    /** 1. Money is conserved: within each currency, every debit has its credit. */
    private List<Finding> moneyIsConserved(UUID tenantId) {
        return jdbc.query(
                """
                SELECT currency,
                       SUM(CASE WHEN direction='CREDIT' THEN amount_minor ELSE -amount_minor END) AS net
                  FROM entries WHERE tenant_id = ?
                 GROUP BY currency HAVING SUM(CASE WHEN direction='CREDIT' THEN amount_minor
                                                   ELSE -amount_minor END) <> 0
                """,
                (rs, i) ->
                        Finding.violation(
                                "money_is_conserved",
                                rs.getString(1),
                                "debits and credits differ by " + rs.getLong(2) + " minor units"),
                tenantId);
    }

    /** 2. The cached balance is honest: it equals the sum of its own entries. */
    private List<Finding> balancesMatchEntries(UUID tenantId) {
        return jdbc.query(
                """
                SELECT b.account_id, b.current_minor, COALESCE(e.derived, 0)
                  FROM balances b
                  LEFT JOIN (
                        SELECT account_id,
                               SUM(CASE WHEN direction='CREDIT' THEN amount_minor ELSE -amount_minor END) AS derived
                          FROM entries WHERE tenant_id = ? GROUP BY account_id) e
                    ON e.account_id = b.account_id
                 WHERE b.tenant_id = ? AND b.current_minor <> COALESCE(e.derived, 0)
                """,
                (rs, i) ->
                        Finding.violation(
                                "balance_matches_entries",
                                rs.getString(1),
                                "cached " + rs.getLong(2) + " but entries derive " + rs.getLong(3)),
                tenantId,
                tenantId);
    }

    /** 3. holds_total equals the sum of that account's ACTIVE holds. */
    private List<Finding> holdsAddUp(UUID tenantId) {
        return jdbc.query(
                """
                SELECT b.account_id, b.holds_total_minor, COALESCE(h.active, 0)
                  FROM balances b
                  LEFT JOIN (
                        SELECT account_id, SUM(amount_minor) AS active
                          FROM holds WHERE tenant_id = ? AND status = 'ACTIVE' GROUP BY account_id) h
                    ON h.account_id = b.account_id
                 WHERE b.tenant_id = ? AND b.holds_total_minor <> COALESCE(h.active, 0)
                """,
                (rs, i) ->
                        Finding.violation(
                                "holds_add_up",
                                rs.getString(1),
                                "holds_total " + rs.getLong(2) + " but active holds sum to " + rs.getLong(3)),
                tenantId,
                tenantId);
    }

    /**
     * 4. No <em>unexplained</em> negative on a guarded account.
     *
     * <p>A guarded account below zero is a violation unless a reversal put it there — reversals
     * bypass the guard deliberately, because undoing must always be possible even after the money
     * has been spent onward. Those cases are real exposure that someone must chase, so they are
     * reported and aged rather than hidden; they are simply not bugs.
     */
    private List<Finding> noUnexplainedNegatives(UUID tenantId) {
        return jdbc.query(
                """
                SELECT a.id,
                       b.current_minor - b.holds_total_minor AS available,
                       EXISTS (
                           SELECT 1 FROM entries e
                             JOIN ledger_transactions t
                               ON t.tenant_id = e.tenant_id AND t.id = e.transaction_id
                            WHERE e.tenant_id = a.tenant_id
                              AND e.account_id = a.id
                              AND t.reverses_transaction_id IS NOT NULL
                       ) AS explained_by_reversal
                  FROM accounts a
                  JOIN balances b ON b.tenant_id = a.tenant_id AND b.account_id = a.id
                 WHERE a.tenant_id = ?
                   AND a.allow_negative = false
                   AND (b.current_minor - b.holds_total_minor) < 0
                """,
                (rs, i) -> {
                    String account = rs.getString(1);
                    long available = rs.getLong(2);
                    boolean explained = rs.getBoolean(3);
                    return explained
                            ? Finding.exposure(
                                    "no_unexplained_negatives",
                                    account,
                                    "available " + available + ", caused by a reversal; owner must resolve")
                            : Finding.violation(
                                    "no_unexplained_negatives",
                                    account,
                                    "available " + available + " with no reversal in its causal chain");
                },
                tenantId);
    }

    /** 5. Reversals are exact and exclusive, in both temporal orders. */
    private List<Finding> reversalsAreExactAndExclusive(UUID tenantId) {
        List<Finding> findings = new ArrayList<>();

        findings.addAll(
                jdbc.query(
                        """
                        SELECT t.id, count(r.id)
                          FROM ledger_transactions t
                          LEFT JOIN ledger_transactions r
                            ON r.tenant_id = t.tenant_id AND r.reverses_transaction_id = t.id
                         WHERE t.tenant_id = ? AND t.status = 'REVERSED'
                         GROUP BY t.id HAVING count(r.id) <> 1
                        """,
                        (rs, i) ->
                                Finding.violation(
                                        "reversals_are_exact",
                                        rs.getString(1),
                                        "marked REVERSED but has " + rs.getLong(2) + " reversing transactions"),
                        tenantId));

        // A reversed transaction must have no compensations at all — in either order. Anything
        // else is the double credit the exclusion exists to prevent.
        findings.addAll(
                jdbc.query(
                        """
                        SELECT t.id, count(c.id)
                          FROM ledger_transactions t
                          JOIN ledger_transactions c
                            ON c.tenant_id = t.tenant_id AND c.relates_to_transaction_id = t.id
                         WHERE t.tenant_id = ? AND t.status = 'REVERSED'
                         GROUP BY t.id
                        """,
                        (rs, i) ->
                                Finding.violation(
                                        "reversals_are_exclusive",
                                        rs.getString(1),
                                        "reversed, yet carries " + rs.getLong(2) + " compensations"),
                        tenantId));

        return findings;
    }

    /** 6. Terminal states are terminal, and closed accounts receive nothing but undo and sweep. */
    private List<Finding> terminalStatesAreTerminal(UUID tenantId) {
        List<Finding> findings = new ArrayList<>();

        findings.addAll(
                jdbc.query(
                        """
                        SELECT id, status FROM holds
                         WHERE tenant_id = ? AND status <> 'ACTIVE' AND resolved_at IS NULL
                        """,
                        (rs, i) ->
                                Finding.violation(
                                        "terminal_states_are_terminal",
                                        rs.getString(1),
                                        "hold is " + rs.getString(2) + " with no resolution timestamp"),
                        tenantId));

        findings.addAll(
                jdbc.query(
                        """
                        SELECT a.id, count(e.id)
                          FROM accounts a
                          JOIN entries e ON e.tenant_id = a.tenant_id AND e.account_id = a.id
                          JOIN ledger_transactions t
                            ON t.tenant_id = e.tenant_id AND t.id = e.transaction_id
                         WHERE a.tenant_id = ?
                           AND a.status = 'CLOSED'
                           AND e.booked_at > a.closed_at
                           AND t.reverses_transaction_id IS NULL
                           AND NOT EXISTS (
                                 SELECT 1 FROM entries s
                                  WHERE s.tenant_id = e.tenant_id
                                    AND s.transaction_id = e.transaction_id
                                    AND s.account_id <> e.account_id
                                    AND EXISTS (
                                          SELECT 1 FROM accounts sa
                                           WHERE sa.tenant_id = s.tenant_id
                                             AND sa.id = s.account_id
                                             AND sa.type = 'SUSPENSE'))
                         GROUP BY a.id
                        """,
                        (rs, i) ->
                                Finding.violation(
                                        "closed_accounts_receive_only_undo",
                                        rs.getString(1),
                                        rs.getLong(2) + " post-closure entries that are neither reversal nor sweep"),
                        tenantId));

        return findings;
    }

    private InvariantReport persist(InvariantReport report) {
        return tenantScope.inTenant(
                report.tenantId(),
                () -> {
                    Long id =
                            jdbc.queryForObject(
                                    """
                                    INSERT INTO invariant_runs
                                        (tenant_id, started_at, completed_at, scope, violations, exposures, findings)
                                    VALUES (?,?,?,?,?,?, CAST(? AS jsonb))
                                    RETURNING id
                                    """,
                                    Long.class,
                                    report.tenantId(),
                                    java.sql.Timestamp.from(report.startedAt()),
                                    java.sql.Timestamp.from(report.completedAt()),
                                    report.scope(),
                                    (int) report.violations(),
                                    (int) report.exposures(),
                                    toJson(report.findings()));
                    return new InvariantReport(
                            id,
                            report.tenantId(),
                            report.startedAt(),
                            report.completedAt(),
                            report.scope(),
                            report.findings());
                });
    }

    /** Latest completed report. The GET endpoint fetches; it never triggers a scan. */
    public InvariantReport latest(UUID tenantId) {
        return tenantScope.inTenant(
                tenantId,
                () ->
                        jdbc.query(
                                """
                                SELECT id, started_at, completed_at, scope, violations, exposures
                                  FROM invariant_runs WHERE tenant_id = ?
                                 ORDER BY started_at DESC LIMIT 1
                                """,
                                rs ->
                                        rs.next()
                                                ? new InvariantReport(
                                                        rs.getLong(1),
                                                        tenantId,
                                                        rs.getTimestamp(2).toInstant(),
                                                        rs.getTimestamp(3) == null
                                                                ? null
                                                                : rs.getTimestamp(3).toInstant(),
                                                        rs.getString(4),
                                                        List.of())
                                                : null,
                                tenantId));
    }

    private static String toJson(List<Finding> findings) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < findings.size(); i++) {
            Finding f = findings.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"kind\":\"").append(f.kind()).append("\",")
                    .append("\"invariant\":\"").append(f.invariant()).append("\",")
                    .append("\"subject\":\"").append(escape(f.subject())).append("\",")
                    .append("\"detail\":\"").append(escape(f.detail())).append("\"}");
        }
        return json.append(']').toString();
    }

    private static String escape(String raw) {
        return raw == null ? "" : raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
