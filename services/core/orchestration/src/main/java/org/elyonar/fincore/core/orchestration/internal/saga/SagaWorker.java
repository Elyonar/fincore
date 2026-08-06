package org.elyonar.fincore.core.orchestration.internal.saga;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.elyonar.fincore.core.orchestration.api.LedgerOutcome;
import org.elyonar.fincore.core.orchestration.internal.ledger.LedgerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Resolves sagas whose outcome is still unknown.
 *
 * <p>This is what makes {@code UNKNOWN} a temporary state rather than a permanent one. Without it a
 * saga whose Ledger call timed out would sit in {@code POSTING} until a human noticed — and the
 * customer's money would be in a state nobody was determining.
 *
 * <p>The worker never decides what happened. It re-sends the <em>same derived key</em> and lets the
 * Ledger answer: if the original committed, the replay returns it; if it did not, the replay posts
 * once. Both branches converge on the truth, which is why retrying is safe and guessing is not.
 */
@Component
public class SagaWorker {

    private static final Logger log = LoggerFactory.getLogger(SagaWorker.class);

    private final SagaClaims claims;
    private final SagaRecords sagas;
    private final LedgerClient ledger;
    private final String workerId;
    private final Duration lease;
    private final int escalateAfterAttempts;
    private final Duration escalateAfter;

    public SagaWorker(
            SagaClaims claims,
            SagaRecords sagas,
            LedgerClient ledger,
            @Value("${fincore.core.worker.id:#{T(java.util.UUID).randomUUID().toString()}}") String workerId,
            @Value("${fincore.core.worker.lease-seconds:30}") long leaseSeconds,
            // The escalation bound from design.md: 12 attempts or 15 minutes, whichever first.
            @Value("${fincore.core.worker.escalate-after-attempts:12}") int escalateAfterAttempts,
            @Value("${fincore.core.worker.escalate-after-minutes:15}") long escalateAfterMinutes) {
        this.claims = claims;
        this.sagas = sagas;
        this.ledger = ledger;
        this.workerId = workerId;
        this.lease = Duration.ofSeconds(leaseSeconds);
        this.escalateAfterAttempts = escalateAfterAttempts;
        this.escalateAfter = Duration.ofMinutes(escalateAfterMinutes);
    }

    /**
     * One pass: claim what is due, resolve each.
     *
     * <p>Claiming is what keeps several instances from working one saga at once, and the lease is
     * what keeps a dead instance's work from being stranded.
     */
    @Scheduled(fixedDelayString = "${fincore.core.worker.interval-ms:1000}")
    public void resolveOutstanding() {
        List<UUID> claimed;
        try {
            claimed = claims.claim(workerId, lease, 25);
        } catch (RuntimeException e) {
            // A worker that dies on a claim failure stops resolving anything at all.
            log.error("saga claim failed", e);
            return;
        }
        for (UUID sagaId : claimed) {
            try {
                resolve(sagaId);
            } catch (RuntimeException e) {
                // One saga's failure must not stop the others being resolved.
                log.error("resolving saga {} failed", sagaId, e);
            }
        }
    }

    /**
     * Re-sends the original posting under its original key, and applies whatever the Ledger says.
     *
     * <p>Public so a test can drive one resolution deterministically rather than waiting on a
     * timer — the assertions are about what the worker does, not about when it happened to run.
     */
    public void resolve(UUID sagaId) {
        SagaRecords.Pending pending = sagas.loadPending(sagaId);
        if (pending == null) {
            // Someone else finished it between the claim and now. Nothing to do, and nothing wrong.
            return;
        }

        LedgerOutcome outcome =
                ledger.post(pending.postingUnder(IdempotencyKeys.forStep(sagaId, "post")));

        switch (outcome) {
            case LedgerOutcome.Success success -> {
                log.info("saga {} resolved as posted", sagaId);
                sagas.complete(pending.tenantId(), sagaId, success.ledgerTransactionId());
            }
            case LedgerOutcome.DefiniteFailure failure -> {
                log.info("saga {} resolved as not posted: {}", sagaId, failure.errorCode());
                sagas.fail(pending.tenantId(), sagaId, failure.errorCode());
            }
            case LedgerOutcome.Unknown unknown -> {
                sagas.recordUnknownAttempt(pending.tenantId(), sagaId, unknown.reason());

                if (shouldEscalate(pending)) {
                    // Still undetermined past the bound. A human now owns *determining* the
                    // outcome — not declaring it. Nothing is compensated and the reservation
                    // stays held, because the money may have moved.
                    log.error(
                            "saga {} unresolved after {} attempts — raising an ops case",
                            sagaId,
                            pending.attempts() + 1);
                    sagas.escalate(pending.tenantId(), sagaId);
                } else {
                    claims.scheduleRetry(sagaId, workerId, backoffFor(pending.attempts()));
                }
            }
        }
    }

    private boolean shouldEscalate(SagaRecords.Pending pending) {
        return pending.attempts() + 1 >= escalateAfterAttempts
                || pending.age().compareTo(escalateAfter) >= 0;
    }

    /**
     * Exponential backoff from 1s, capped at 60s.
     *
     * <p>Aggressive early because the likeliest cause of an unknown is that the Ledger committed
     * and the response was lost — a prompt retry reads back the answer rather than doing anything
     * new.
     */
    private Duration backoffFor(int attempts) {
        long seconds = Math.min(60L, 1L << Math.min(attempts, 6));
        return Duration.ofSeconds(seconds);
    }
}
