package org.elyonar.fincore.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.elyonar.fincore.notification.internal.AddressCipher;
import org.elyonar.fincore.notification.internal.channel.MessageSender;
import org.elyonar.fincore.notification.internal.channel.Senders;
import org.elyonar.fincore.notification.internal.send.SendWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The send queue: claimed once, attempted with history, and never sent twice.
 *
 * <p>The interesting assertions are not "it sent". They are what happens when it does not: a
 * gateway that times out is an unknown and gets retried, a gateway that rejects is definite and
 * does not, and a message that exhausts its attempts records why it was never sent in the same
 * place every other such reason lives.
 */
@SpringBootTest
@DisplayName("send worker — claim, attempt, and record what happened")
class SendWorkerTest {

    @Autowired @Qualifier("appJdbcTemplate") private JdbcTemplate app;
    @Autowired @Qualifier("workerJdbcTemplate") private JdbcTemplate workerDb;
    @Autowired private AddressCipher cipher;
    @Autowired @Qualifier("workerTransactionManager") private PlatformTransactionManager workerTx;

    @Autowired private org.elyonar.fincore.notification.internal.TenantRegistry tenantRegistry;

    private UUID tenant;
    private UUID notificationId;
    private final List<String> references = new ArrayList<>();

    @BeforeEach
    void seed() {
        tenant = UUID.randomUUID();
        tenantRegistry.register(tenant, "test tenant", "test");
        references.clear();
        app.execute("SET app.tenant_id = '" + tenant + "'");
        notificationId = queue("+2348000000001");
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    @DisplayName("a queued message is sent once, and the attempt is recorded")
    void a_message_is_sent_and_recorded() {
        // The worker is cross-tenant by design — it has no tenant context, because it serves all of
        // them — so a drain sweeps up whatever else is queued. Every assertion here is about this
        // test's own message, which is the only thing it can honestly claim.
        drainAll(sender -> MessageSender.Result.sent("gw-1"));

        assertThat(state()).isEqualTo("SENT");
        assertThat(attempts()).hasSize(1);
        assertThat(attempts().get(0)).containsEntry("outcome", "SENT").containsEntry("gateway_ref", "gw-1");
    }

    @Test
    @DisplayName("the address reaches the sender decrypted, and the reference is derived")
    void the_sender_gets_what_it_needs() {
        List<String> addresses = new ArrayList<>();
        SendWorker worker = worker((id, address, parts, reference) -> {
            addresses.add(address);
            references.add(reference);
            return MessageSender.Result.sent("gw-1");
        });

        worker.drain(10);

        assertThat(addresses).containsExactly("+2348000000001");
        // Derived from the notification and the attempt number, never random: a retry of the same
        // attempt must present the same reference or a gateway cannot deduplicate on it.
        assertThat(references).containsExactly("notif:" + notificationId + ":1");
    }

    @Test
    @DisplayName("a drained queue is not attempted again")
    void a_sent_message_is_not_reclaimed() {
        drainAll(s -> MessageSender.Result.sent("gw-1"));

        drainAll(s -> MessageSender.Result.sent("gw-2"));

        // One attempt, not two: a SENT message is terminal and is never claimed again.
        assertThat(attempts()).hasSize(1);
        assertThat(attempts().get(0)).containsEntry("gateway_ref", "gw-1");
    }

    // ------------------------------------------------------------------ the failures

    @Test
    @DisplayName("a definite rejection is terminal and is not retried")
    void a_rejection_is_terminal() {
        drainAll(s -> MessageSender.Result.rejected("INVALID_NUMBER"));

        // A malformed number is malformed on every retry. Retrying is paying repeatedly for the
        // same answer, and the answer does not change.
        assertThat(state()).isEqualTo("FAILED");
        assertThat(attempts().get(0)).containsEntry("error_code", "INVALID_NUMBER");

        drainAll(s -> MessageSender.Result.sent("gw"));
        assertThat(attempts()).as("a failed message is terminal and never retried").hasSize(1);
    }

    @Test
    @DisplayName("an unknown outcome is retried, not failed")
    void an_unknown_is_retried() {
        drainAll(s -> MessageSender.Result.unknown("TIMEOUT"));

        // A duplicate debit alert is an annoyance; a missing one is a fraud control that never
        // fired. The asymmetry is the whole reason this is not at-most-once.
        assertThat(state()).isEqualTo("PENDING");
        assertThat(attempts()).hasSize(1);
        // Backed off, so the next tick does not immediately re-attempt.
        assertThat(dueNow()).isFalse();
    }

    @Test
    @DisplayName("a sender that throws is an unknown, never a failure")
    void a_thrown_exception_is_an_unknown() {
        drainAll(s -> {
            throw new IllegalStateException("connection reset");
        });

        // A sender that threw has told us nothing about whether the message went. Treating that as
        // failure would be a claim nobody can support.
        assertThat(state()).isEqualTo("PENDING");
        assertThat(attempts().get(0)).containsEntry("outcome", "UNKNOWN").containsEntry("error_code", "SENDER_THREW");
    }

    @Test
    @DisplayName("exhausting the attempts fails the message and records why it was never sent")
    void exhaustion_is_recorded_as_a_suppression() {
        SendWorker worker = worker(s -> MessageSender.Result.unknown("TIMEOUT"), 2);

        worker.drain(200);
        makeDue();
        worker.drain(200);

        assertThat(state()).isEqualTo("FAILED");
        assertThat(attempts()).hasSize(2);
        // The reason a message was never sent belongs where every other such reason lives, so one
        // query answers "why did my customer not get this?" whatever stage it stopped at.
        assertThat(app.queryForList(
                        "SELECT reason_code FROM notification.suppressions WHERE tenant_id = ?", String.class, tenant))
                .containsExactly("ATTEMPTS_EXHAUSTED");
    }

    @Test
    @DisplayName("the attempt is counted at claim, so a crash mid-send does not buy a free retry")
    void attempts_are_counted_before_the_send() {
        // Claim without ever recording an outcome — the shape of a worker that died mid-send.
        List<SendWorker.Claimed> claimed = worker(s -> MessageSender.Result.sent("gw")).claim(200);

        assertThat(claimed).anyMatch(c -> c.id().equals(notificationId) && c.attemptNo() == 1);
        assertThat(app.queryForObject(
                        "SELECT attempts FROM notification.notifications WHERE id = ?", Integer.class, notificationId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a claimed message is invisible to another worker until its lease expires")
    void a_lease_keeps_two_workers_off_one_message() {
        worker(s -> MessageSender.Result.sent("gw")).claim(200);

        // The second instance does not see *this* message again, rather than sending it twice.
        // FOR UPDATE SKIP LOCKED cannot help here: the first transaction has already committed, so
        // it is the lease and nothing else keeping the two apart.
        assertThat(worker(s -> MessageSender.Result.sent("gw")).claim(200))
                .noneMatch(c -> c.id().equals(notificationId));
    }

    // ------------------------------------------------------------------ harness

    private interface Stub {
        MessageSender.Result answer(UUID id);
    }

    private interface FullStub {
        MessageSender.Result answer(UUID id, String address, Map<String, String> parts, String reference);
    }

    private SendWorker worker(Stub stub) {
        return worker(stub, 6);
    }

    /** Drains until this test's message is no longer pending, ignoring whatever else is queued. */
    private void drainAll(Stub stub) {
        worker(stub).drain(200);
    }

    private SendWorker worker(Stub stub, int maxAttempts) {
        return worker((id, address, parts, reference) -> stub.answer(id), maxAttempts);
    }

    private SendWorker worker(FullStub stub) {
        return worker(stub, 6);
    }

    private SendWorker worker(FullStub stub, int maxAttempts) {
        MessageSender sender = new MessageSender() {
            @Override
            public String channel() {
                return "SMS";
            }

            @Override
            public boolean delivers() {
                return true;
            }

            @Override
            public Result send(UUID id, String address, Map<String, String> parts, String reference) {
                return stub.answer(id, address, parts, reference);
            }
        };
        return new SendWorker(
                workerDb, workerTx, new Senders.Registry(List.of(sender)), cipher,
                30_000, maxAttempts, 1_000, 60_000);
    }

    private UUID queue(String address) {
        return app.queryForObject(
                """
                INSERT INTO notification.notifications
                       (tenant_id, business_moment_key, category, channel, template_key, template_version,
                        locale, recipient_ref, recipient_address, rendered, units)
                VALUES (?, ?, 'TRANSACTIONAL', 'SMS', 'debit.alert', 1, 'en', ?, ?, ?::jsonb, 1)
                RETURNING id
                """,
                UUID.class,
                tenant, "send:" + UUID.randomUUID(), UUID.randomUUID(), cipher.encrypt(address),
                "{\"body\":\"Debit of 100000\"}");
    }

    private String state() {
        return app.queryForObject(
                "SELECT state FROM notification.notifications WHERE id = ?", String.class, notificationId);
    }

    private boolean dueNow() {
        return Boolean.TRUE.equals(app.queryForObject(
                "SELECT next_attempt_at <= now() FROM notification.notifications WHERE id = ?",
                Boolean.class, notificationId));
    }

    /** Brings a backed-off message forward, so a retry can be observed without waiting for it. */
    private void makeDue() {
        workerDb.execute("SET app.worker = 'on'");
        workerDb.update(
                "UPDATE notification.notifications SET next_attempt_at = now() - interval '1 minute' WHERE id = ?",
                notificationId);
    }

    private List<Map<String, Object>> attempts() {
        return app.queryForList(
                "SELECT * FROM notification.delivery_attempts WHERE notification_id = ? ORDER BY attempt_no",
                notificationId);
    }
}
