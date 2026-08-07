package org.elyonar.fincore.notification.internal.send;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.elyonar.fincore.notification.internal.AddressCipher;
import org.elyonar.fincore.notification.internal.Suppressed;
import org.elyonar.fincore.notification.internal.channel.MessageSender;
import org.elyonar.fincore.notification.internal.channel.Senders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drains the send queue.
 *
 * <p>Work is claimed from the database with {@code FOR UPDATE SKIP LOCKED} and a lease, never from
 * an in-JVM queue: several instances run behind a load balancer, and an in-memory queue loses work
 * on restart and duplicates it across replicas. This is Core's saga-claim primitive, reused
 * deliberately — one idiom, learned once.
 *
 * <p><strong>The claim and the send are separate transactions</strong>, and that is the whole reason
 * a lease exists. A row lock cannot survive the send, because the send must not hold a database
 * transaction open across a network call to a gateway. The lease is the out-of-transaction
 * equivalent: it makes a claim visible to other instances without holding a connection, and a
 * worker that dies mid-send simply lets its lease expire.
 *
 * <p>A lease that expires while a send is genuinely in flight causes a duplicate <em>attempt</em>,
 * never a duplicate <em>message</em> — the unique key on the notification absorbs it, and the
 * client reference lets a gateway that deduplicates do so too. That is the property which makes an
 * aggressive lease safe.
 */
@Component
public class SendWorker {

    private static final Logger log = LoggerFactory.getLogger(SendWorker.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    // Explicit rather than @Transactional, because these boundaries are load-bearing: the worker
    // scope is a SET LOCAL and evaporates without a transaction around it. An annotation only
    // applies through a proxy, so a directly constructed worker would run every statement outside
    // a transaction, find nothing, and report an empty queue — which is what happened.
    private final TransactionTemplate transaction;
    private final Senders.Registry senders;
    private final AddressCipher cipher;
    private final String instanceId;
    private final Duration lease;
    private final int maxAttempts;
    private final Duration firstBackoff;
    private final Duration maxBackoff;

    public SendWorker(
            @Qualifier("workerJdbcTemplate") JdbcTemplate jdbc,
            @Qualifier("workerTransactionManager") PlatformTransactionManager transactionManager,
            Senders.Registry senders,
            AddressCipher cipher,
            @Value("${fincore.notification.worker.lease-ms:30000}") long leaseMs,
            @Value("${fincore.notification.worker.max-attempts:6}") int maxAttempts,
            @Value("${fincore.notification.worker.first-backoff-ms:2000}") long firstBackoffMs,
            @Value("${fincore.notification.worker.max-backoff-ms:300000}") long maxBackoffMs) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.senders = senders;
        this.cipher = cipher;
        this.instanceId = "sender-" + UUID.randomUUID();
        this.lease = Duration.ofMillis(leaseMs);
        this.maxAttempts = maxAttempts;
        this.firstBackoff = Duration.ofMillis(firstBackoffMs);
        this.maxBackoff = Duration.ofMillis(maxBackoffMs);
    }

    /** @return how many messages were attempted */
    public int drain(int batchSize) {
        List<Claimed> claimed = claim(batchSize);
        for (Claimed message : claimed) {
            deliver(message);
        }
        return claimed.size();
    }

    /**
     * Phase A: take the work, visibly, and count the attempt before making it.
     *
     * <p>Incrementing {@code attempts} at claim rather than at completion is deliberate. A worker
     * that dies mid-send must not get a free retry — the attempt happened, the gateway may well
     * have sent it, and counting only completed attempts turns a crash loop into an unbounded
     * stream of duplicate messages.
     */
    public List<Claimed> claim(int batchSize) {
        return transaction.execute(status -> {
            enterWorkerScope();
            return jdbc.query(
                """
                UPDATE notification.notifications
                   SET state = 'SENDING',
                       claimed_by = ?,
                       claim_expires_at = now() + make_interval(secs => ?),
                       attempts = attempts + 1,
                       updated_at = now()
                 WHERE id IN (
                       SELECT id FROM notification.notifications
                        WHERE state IN ('PENDING', 'SENDING')
                          AND next_attempt_at <= now()
                          AND (claim_expires_at IS NULL OR claim_expires_at < now())
                        ORDER BY next_attempt_at
                        FOR UPDATE SKIP LOCKED
                        LIMIT ?)
             RETURNING id, tenant_id, channel, recipient_address, rendered::text AS rendered, attempts
                """,
                (rs, row) ->
                        new Claimed(
                                rs.getObject("id", UUID.class),
                                rs.getObject("tenant_id", UUID.class),
                                rs.getString("channel"),
                                rs.getString("recipient_address"),
                                rs.getString("rendered"),
                                rs.getInt("attempts")),
                    instanceId, lease.toSeconds(), batchSize);
        });
    }

    /** Phase B: the gateway call, outside any transaction. */
    private void deliver(Claimed message) {
        MessageSender sender = senders.forChannel(message.channel());
        // Derived, never random: a retry of the same attempt must present the same reference, or a
        // gateway that deduplicates cannot.
        String reference = "notif:" + message.id() + ":" + message.attemptNo();

        MessageSender.Result result;
        try {
            result = sender.send(message.id(), cipher.decrypt(message.address()), parts(message), reference);
        } catch (RuntimeException e) {
            // A sender that throws has told us nothing about whether the message went. That is an
            // unknown, not a failure, and the difference decides whether we retry.
            log.warn("sender for {} threw; treating as unknown", message.channel(), e);
            result = MessageSender.Result.unknown("SENDER_THREW");
        }

        record(message, reference, result);
    }

    /** Phase C: the attempt is history, and the state moves once. */
    public void record(Claimed message, String reference, MessageSender.Result result) {
        transaction.executeWithoutResult(status -> {
            enterWorkerScope();
            recordScoped(message, reference, result);
        });
    }

    private void recordScoped(Claimed message, String reference, MessageSender.Result result) {
        jdbc.update(
                """
                INSERT INTO notification.delivery_attempts
                       (tenant_id, notification_id, attempt_no, outcome, client_reference, gateway_ref, error_code)
                VALUES (?,?,?,?,?,?,?)
                ON CONFLICT (tenant_id, notification_id, attempt_no) DO NOTHING
                """,
                message.tenantId(), message.id(), message.attemptNo(), result.outcome().name(),
                reference, result.gatewayRef(), result.errorCode());

        switch (result.outcome()) {
            case SENT -> settle(message, "SENT");
            // Terminal by nature: a malformed number or a rejected sender id will be malformed and
            // rejected on every retry, and retrying is a way of paying for the same answer.
            case DEFINITE_FAILURE -> settle(message, "FAILED");
            case UNKNOWN -> {
                if (message.attemptNo() >= maxAttempts) {
                    // The one case where a suppression is written by the worker rather than the
                    // intake: nothing more will be tried, so the reason it was never sent belongs
                    // in the same place every other such reason lives.
                    suppress(message);
                    settle(message, "FAILED");
                } else {
                    reschedule(message);
                }
            }
        }
    }

    private void settle(Claimed message, String state) {
        jdbc.update(
                """
                UPDATE notification.notifications
                   SET state = ?, claimed_by = NULL, claim_expires_at = NULL, updated_at = now()
                 WHERE id = ?
                """,
                state, message.id());
    }

    private void reschedule(Claimed message) {
        // Exponential with a cap. Aggressive early, because the likeliest cause of an unknown is a
        // response that was lost rather than a gateway that is down.
        long seconds = Math.min(
                maxBackoff.toSeconds(),
                firstBackoff.toSeconds() * (1L << Math.min(message.attemptNo() - 1, 16)));
        jdbc.update(
                """
                UPDATE notification.notifications
                   SET state = 'PENDING',
                       claimed_by = NULL,
                       claim_expires_at = NULL,
                       next_attempt_at = now() + make_interval(secs => ?),
                       updated_at = now()
                 WHERE id = ?
                """,
                seconds, message.id());
    }

    private void suppress(Claimed message) {
        jdbc.update(
                """
                INSERT INTO notification.suppressions
                       (tenant_id, business_moment_key, category, channel, recipient_ref, reason_code, detail)
                SELECT tenant_id, business_moment_key, category, channel, recipient_ref, ?, ?::jsonb
                  FROM notification.notifications WHERE id = ?
                """,
                Suppressed.ATTEMPTS_EXHAUSTED.name(),
                JSON.writeValueAsString(Map.of("attempts", message.attemptNo())),
                message.id());
    }

    /**
     * The worker's cross-tenant reach, for this transaction only.
     *
     * <p>{@code SET LOCAL}, so it dies with the transaction and cannot ride a pooled connection to
     * the next borrower. A permissive policy reads this flag on exactly three tables; every other
     * table in the schema stays closed to this role, which is what {@code BYPASSRLS} would have
     * given away.
     */
    private void enterWorkerScope() {
        jdbc.queryForObject("SELECT set_config('app.worker', 'on', true)", String.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parts(Claimed message) {
        return JSON.readValue(message.rendered(), Map.class);
    }

    /** @param attemptNo already incremented — this is the attempt about to be made, not the last one */
    public record Claimed(
            UUID id, UUID tenantId, String channel, String address, String rendered, int attemptNo) {}
}
