package org.elyonar.fincore.core.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.elyonar.fincore.core.orchestration.internal.saga.SagaClaims;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The claim protocol under contention.
 *
 * <p>The property that matters: two instances must never work one saga at the same time, and a
 * saga whose worker died must not be stranded. Both are tested against real PostgreSQL, because
 * {@code FOR UPDATE SKIP LOCKED} is the thing under test and no substitute implements it.
 */
@SpringBootTest
class SagaClaimTest {

    @Autowired private SagaClaims claims;
    @Autowired private JdbcTemplate jdbc;

    private static final Duration LEASE = Duration.ofSeconds(30);

    private UUID insertClaimableSaga() {
        UUID tenantId = UUID.randomUUID();
        return jdbc.queryForObject(
                """
                INSERT INTO orchestration.sagas
                    (tenant_id, type, state, channel_idempotency_key, request_fingerprint,
                     amount_minor, currency, initiated_by, executed_by)
                VALUES (?, 'TRANSFER', 'POSTING', ?, 'fp', 1000, 'NGN', 'user:ada', 'core')
                RETURNING id
                """,
                UUID.class,
                tenantId,
                UUID.randomUUID().toString());
    }

    @Test
    void a_due_saga_is_claimed_and_its_lease_recorded() {
        UUID sagaId = insertClaimableSaga();

        assertThat(claims.claim("worker-1", LEASE, 50)).contains(sagaId);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT claimed_by FROM orchestration.sagas WHERE id = ?",
                                String.class,
                                sagaId))
                .isEqualTo("worker-1");
    }

    @Test
    void a_saga_under_a_live_lease_is_not_claimed_again() {
        UUID sagaId = insertClaimableSaga();
        claims.claim("worker-1", LEASE, 50);

        assertThat(claims.claim("worker-2", LEASE, 50)).doesNotContain(sagaId);
    }

    @Test
    void an_expired_lease_is_reclaimable_so_a_dead_workers_saga_is_never_stranded() {
        UUID sagaId = insertClaimableSaga();
        claims.claim("worker-1", LEASE, 50);

        // The worker died. Its lease lapses rather than being cleaned up by anyone.
        jdbc.update(
                "UPDATE orchestration.sagas SET claim_expires_at = now() - INTERVAL '1 second'"
                        + " WHERE id = ?",
                sagaId);

        assertThat(claims.claim("worker-2", LEASE, 50)).contains(sagaId);
    }

    @Test
    void a_terminal_saga_is_never_claimed() {
        UUID tenantId = UUID.randomUUID();
        UUID sagaId =
                jdbc.queryForObject(
                        """
                        INSERT INTO orchestration.sagas
                            (tenant_id, type, state, channel_idempotency_key, request_fingerprint,
                             amount_minor, currency, initiated_by, executed_by,
                             ledger_transaction_id, terminal_at)
                        VALUES (?, 'TRANSFER', 'COMPLETED', ?, 'fp', 1000, 'NGN', 'user:ada',
                                'core', ?, now())
                        RETURNING id
                        """,
                        UUID.class,
                        tenantId,
                        UUID.randomUUID().toString(),
                        UUID.randomUUID());

        assertThat(claims.claim("worker-1", LEASE, 100)).doesNotContain(sagaId);
    }

    @Test
    void a_saga_scheduled_for_later_is_not_claimed_yet() {
        UUID sagaId = insertClaimableSaga();
        jdbc.update(
                "UPDATE orchestration.sagas SET next_attempt_at = now() + INTERVAL '1 hour'"
                        + " WHERE id = ?",
                sagaId);

        assertThat(claims.claim("worker-1", LEASE, 100)).doesNotContain(sagaId);
    }

    @Test
    void only_the_holder_may_extend_a_lease() {
        UUID sagaId = insertClaimableSaga();
        claims.claim("worker-1", LEASE, 50);

        assertThat(claims.heartbeat(sagaId, "worker-2", LEASE)).isFalse();
        assertThat(claims.heartbeat(sagaId, "worker-1", LEASE)).isTrue();
    }

    @Test
    void scheduling_a_retry_releases_the_lease_so_the_backoff_is_the_only_delay() {
        UUID sagaId = insertClaimableSaga();
        claims.claim("worker-1", LEASE, 50);

        claims.scheduleRetry(sagaId, "worker-1", Duration.ZERO);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT claimed_by FROM orchestration.sagas WHERE id = ?",
                                String.class,
                                sagaId))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT attempts FROM orchestration.sagas WHERE id = ?",
                                Integer.class,
                                sagaId))
                .isEqualTo(1);
        // Immediately claimable again — the backoff governs, not the lease.
        assertThat(claims.claim("worker-2", LEASE, 50)).contains(sagaId);
    }

    @Test
    void concurrent_workers_never_both_claim_the_same_saga() throws Exception {
        UUID sagaId = insertClaimableSaga();

        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Callable<List<UUID>>> racers =
                    java.util.stream.IntStream.range(0, 8)
                            .<Callable<List<UUID>>>mapToObj(
                                    i -> () -> claims.claim("worker-" + i, LEASE, 50))
                            .toList();

            long winners =
                    pool.invokeAll(racers).stream()
                            .map(SagaClaimTest::get)
                            .filter(ids -> ids.contains(sagaId))
                            .count();

            // Exactly one. Two workers on one saga would mean two concurrent attempts at the same
            // ledger call — absorbed by idempotency, but the lease exists so it does not happen.
            assertThat(winners).isEqualTo(1);
        }
    }

    private static List<UUID> get(Future<List<UUID>> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
