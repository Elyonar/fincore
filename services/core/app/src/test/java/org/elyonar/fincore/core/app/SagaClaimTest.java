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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The claim protocol under contention.
 *
 * <p>The property that matters: two instances must never work one saga at the same time, and a
 * saga whose worker died must not be stranded. Both are tested against real PostgreSQL, because
 * {@code FOR UPDATE SKIP LOCKED} is the thing under test and no substitute implements it.
 */
@SpringBootTest
class SagaClaimTest {

    // Every tenant a test uses must be registered, because Core now refuses one it has
    // never heard of. Registering here rather than weakening the gate for tests: a guard
    // switched off under test is a guard nobody has tested.
    @Autowired private TenantRegistry tenantRegistry;

    @Autowired private SagaClaims claims;

    // The worker's connection: it claims across tenants, so it is the one that can see the rows
    // these tests assert on without a tenant context.
    @Autowired @Qualifier("workerJdbcTemplate") private JdbcTemplate jdbc;
    @Autowired @Qualifier("orchestrationJdbcTemplate") private JdbcTemplate requestPathDb;
    @Autowired @Qualifier("orchestrationTransactionManager") private PlatformTransactionManager requestPathTx;

    /**
     * Inserts a saga the way a request would: as the tenant-scoped role, inside a transaction.
     *
     * <p>The tenant context is SET LOCAL, so it belongs to the transaction. Seeding outside one
     * would be refused by row-level security — correctly.
     */
    private UUID seedSaga(UUID tenantId, String sql, Object... args) {
        var holder = new java.util.concurrent.atomic.AtomicReference<UUID>();
        new TransactionTemplate(requestPathTx)
                .executeWithoutResult(
                        s -> {
                            requestPathDb.queryForObject(
                                    "SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
                            holder.set(requestPathDb.queryForObject(sql, UUID.class, args));
                        });
        return holder.get();
    }

    private static final Duration LEASE = Duration.ofSeconds(30);

    private UUID insertClaimableSaga() {
        UUID tenantId = UUID.randomUUID();
        tenantRegistry.register(tenantId, "test tenant", "test");
        return seedSaga(
                tenantId,
                """
                INSERT INTO orchestration.sagas
                    (tenant_id, type, state, channel_idempotency_key, request_fingerprint,
                     amount_minor, currency, initiated_by, executed_by,
                     from_account_id, to_account_id)
                VALUES (?, 'TRANSFER', 'POSTING', ?, 'fp', 1000, 'NGN', 'user:ada', 'core',
                        gen_random_uuid(), gen_random_uuid())
                RETURNING id
                """,
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
        tenantRegistry.register(tenantId, "test tenant", "test");
        UUID sagaId =
                seedSaga(
                        tenantId,
                        """
                        INSERT INTO orchestration.sagas
                            (tenant_id, type, state, channel_idempotency_key, request_fingerprint,
                             amount_minor, currency, initiated_by, executed_by,
                             ledger_transaction_id, terminal_at, from_account_id, to_account_id)
                        VALUES (?, 'TRANSFER', 'COMPLETED', ?, 'fp', 1000, 'NGN', 'user:ada',
                                'core', ?, now(), gen_random_uuid(), gen_random_uuid())
                        RETURNING id
                        """,
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
        // The attempt counter belongs to recordUnknownAttempt, not to the scheduler: two writers
        // on one counter made attempt numbers skip and collide.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT attempts FROM orchestration.sagas WHERE id = ?",
                                Integer.class,
                                sagaId))
                .isZero();
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
