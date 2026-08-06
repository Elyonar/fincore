package org.elyonar.fincore.core.orchestration.internal.saga;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The unresolved-outcome queue.
 *
 * <p>A case exists when a saga's outcome outlived the escalation bound: the money may have moved,
 * and nobody yet knows. The reservation stays held and nothing is compensated, because both would
 * be guesses.
 *
 * <p>There is deliberately no method here that writes an outcome. A human's job on this queue is
 * to get the Ledger asked again, not to decide the answer — the only thing that can determine what
 * happened is the Ledger itself.
 */
@Repository
public class OpsCases {

    private final JdbcTemplate jdbc;
    private final SagaWorker worker;

    public OpsCases(@Qualifier("orchestrationJdbcTemplate") JdbcTemplate jdbc, SagaWorker worker) {
        this.jdbc = jdbc;
        this.worker = worker;
    }

    private void scopeTo(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> open(UUID tenantId) {
        scopeTo(tenantId);
        return jdbc.query(
                """
                SELECT c.id, c.saga_id, c.kind, c.opened_at, s.state, s.amount_minor, s.currency,
                       s.attempts, s.last_error
                  FROM orchestration.ops_cases c
                  JOIN orchestration.sagas s ON s.id = c.saga_id
                 WHERE c.status = 'OPEN'
                 ORDER BY c.opened_at
                """,
                (rs, row) -> {
                    var out = new java.util.LinkedHashMap<String, Object>();
                    out.put("caseId", rs.getString("id"));
                    out.put("transactionId", rs.getString("saga_id"));
                    out.put("kind", rs.getString("kind"));
                    out.put("state", rs.getString("state"));
                    // Decimal string, like every monetary field this platform emits.
                    out.put("amountMinor", Long.toString(rs.getLong("amount_minor")));
                    out.put("currency", rs.getString("currency"));
                    out.put("attempts", rs.getInt("attempts"));
                    out.put("lastError", rs.getString("last_error"));
                    out.put("openedAt", String.valueOf(rs.getObject("opened_at")));
                    return out;
                });
    }

    /**
     * Re-attempts resolution and reports what the Ledger said.
     *
     * <p>Closes the case only if the saga reached a terminal state — that is, only if the Ledger
     * gave a definitive answer. An attempt that comes back unknown leaves the case open, which is
     * the correct outcome and the honest one.
     */
    @Transactional
    public Map<String, Object> reattempt(UUID tenantId, UUID caseId) {
        scopeTo(tenantId);
        UUID sagaId =
                jdbc.queryForObject(
                        "SELECT saga_id FROM orchestration.ops_cases WHERE id = ? AND status = 'OPEN'",
                        UUID.class, caseId);

        worker.resolve(sagaId);

        String state =
                jdbc.queryForObject(
                        "SELECT state FROM orchestration.sagas WHERE id = ?", String.class, sagaId);
        boolean determined = "COMPLETED".equals(state) || "FAILED".equals(state);
        if (determined) {
            jdbc.update(
                    """
                    UPDATE orchestration.ops_cases
                       SET status = 'RESOLVED', resolved_at = now(),
                           resolution = CASE WHEN ? = 'COMPLETED' THEN 'POSTED' ELSE 'NOT_POSTED' END
                     WHERE id = ?
                    """,
                    state, caseId);
        }
        return Map.of(
                "caseId", caseId.toString(),
                "transactionId", sagaId.toString(),
                "state", state,
                "resolved", determined);
    }
}
