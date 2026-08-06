package org.elyonar.fincore.core.orchestration.internal.saga;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claiming outstanding sagas across instances.
 *
 * <p>Core runs several instances behind a load balancer, so work is claimed from the database and
 * never queued in a JVM: an in-memory queue loses its contents on restart and duplicates them
 * across replicas.
 *
 * <p><strong>A lease, not just a row lock.</strong> The row lock cannot survive the outbound call —
 * Phase B must not hold a transaction open across a network hop, or one partner's slowness becomes
 * connection-pool exhaustion here. So the claim is recorded as {@code claimed_by} plus an expiry,
 * visible to other instances without holding a connection. A worker that dies mid-call simply lets
 * its lease run out, and another instance reclaims the saga and retries the <em>same derived
 * key</em> — which is safe by construction, because the Ledger's registry absorbs the duplicate
 * attempt.
 *
 * <p>That last property is what lets the lease be short. An expiry that fires while work is
 * genuinely in flight costs a duplicate <em>attempt</em>, never a duplicate <em>posting</em>.
 */
@Repository
public class SagaClaims {

    /** States a worker may pick up. Terminal sagas are never claimed. */
    private static final String CLAIMABLE = "('RECEIVED', 'POSTING')";

    private final JdbcTemplate jdbc;

    public SagaClaims(@Qualifier("workerJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims up to {@code batch} due sagas for {@code worker}, and returns their ids.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} means concurrent workers step over each other's rows rather
     * than queueing behind them, so throughput scales with instances instead of serialising.
     */
    @Transactional(transactionManager = "workerTransactionManager")
    public List<UUID> claim(String worker, Duration lease, int batch) {
        List<UUID> claimed =
                jdbc.queryForList(
                        """
                        SELECT id
                          FROM orchestration.sagas
                         WHERE state IN """
                                + CLAIMABLE
                                + """
                           AND next_attempt_at <= now()
                           AND (claim_expires_at IS NULL OR claim_expires_at < now())
                         ORDER BY next_attempt_at
                           FOR UPDATE SKIP LOCKED
                         LIMIT ?
                        """,
                        UUID.class,
                        batch);

        if (claimed.isEmpty()) {
            return List.of();
        }

        for (UUID id : claimed) {
            jdbc.update(
                    """
                    UPDATE orchestration.sagas
                       SET claimed_by = ?, claim_expires_at = now() + (? * INTERVAL '1 second')
                     WHERE id = ?
                    """,
                    worker,
                    lease.toSeconds(),
                    id);
        }
        return claimed;
    }

    /**
     * Extends a claim while work is genuinely still in flight.
     *
     * <p>Only the holder may extend, so a worker that lost its lease cannot silently take it back
     * while another instance is already retrying the same saga.
     */
    @Transactional(transactionManager = "workerTransactionManager")
    public boolean heartbeat(UUID sagaId, String worker, Duration lease) {
        return jdbc.update(
                        """
                        UPDATE orchestration.sagas
                           SET claim_expires_at = now() + (? * INTERVAL '1 second')
                         WHERE id = ? AND claimed_by = ? AND claim_expires_at >= now()
                        """,
                        lease.toSeconds(),
                        sagaId,
                        worker)
                == 1;
    }

    /** Releases a claim without changing state — used when a worker shuts down cleanly. */
    @Transactional(transactionManager = "workerTransactionManager")
    public void release(UUID sagaId, String worker) {
        jdbc.update(
                "UPDATE orchestration.sagas SET claimed_by = NULL, claim_expires_at = NULL"
                        + " WHERE id = ? AND claimed_by = ?",
                sagaId,
                worker);
    }

    /**
     * Schedules the next attempt after an unknown outcome.
     *
     * <p>Releases the lease at the same time: the saga is no longer being worked, and holding the
     * claim until expiry would delay the retry by the lease duration for no reason.
     *
     * <p>Does <em>not</em> touch the attempt counter — {@code recordUnknownAttempt} owns it. Two
     * writers on one counter made attempt numbers skip, and a re-resolve then collided on
     * {@code (saga_id, attempt_no)}.
     */
    @Transactional(transactionManager = "workerTransactionManager")
    public void scheduleRetry(UUID sagaId, String worker, Duration backoff) {
        jdbc.update(
                """
                UPDATE orchestration.sagas
                   SET next_attempt_at = now() + (? * INTERVAL '1 second'),
                       claimed_by = NULL,
                       claim_expires_at = NULL
                 WHERE id = ? AND claimed_by = ?
                """,
                backoff.toSeconds(),
                sagaId,
                worker);
    }
}
