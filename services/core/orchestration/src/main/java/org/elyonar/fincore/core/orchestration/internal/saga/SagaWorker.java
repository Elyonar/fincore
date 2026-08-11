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
import org.elyonar.fincore.core.orchestration.api.CoreProperties;

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
            @Value("${" + CoreProperties.WORKER_LEASE_SECONDS + ":30}") long leaseSeconds,
            // The escalation bound from design.md: 12 attempts or 15 minutes, whichever first.
            @Value("${" + CoreProperties.WORKER_ESCALATE_AFTER_ATTEMPTS + ":12}") int escalateAfterAttempts,
            @Value("${" + CoreProperties.WORKER_ESCALATE_AFTER_MINUTES + ":15}") long escalateAfterMinutes) {
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
    @Scheduled(fixedDelayString = "${" + CoreProperties.WORKER_INTERVAL_MS + ":1000}")
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
                // One saga's failure must not stop the others being resolved — but a failure that
                // is only logged is a saga that loops on lease expiry forever, with no backoff and
                // no escalation. That is how a zero-fee reversal once retried indefinitely without
                // ever opening an ops case. Count the attempt, back off, and past the bound hand it
                // to a human.
                log.error("resolving saga {} failed", sagaId, e);
                retryOrEscalate(sagaId, e);
            }
        }
    }

    /**
     * Unknown-shaped recovery for a failure inside Core itself.
     *
     * <p>A {@code RuntimeException} out of {@link #resolve} means the outcome was not determined —
     * which is exactly what an unknown is, whether the cause was the network or our own defect. So
     * it is treated identically: the attempt is recorded, the retry backs off, and the attempt
     * ceiling escalates to an ops case rather than retrying a broken saga forever.
     */
    private void retryOrEscalate(UUID sagaId, RuntimeException failure) {
        try {
            SagaRecords.Pending pending = sagas.loadPending(sagaId);
            if (pending == null) {
                // Terminal after all, or gone. Either way there is nothing left to schedule.
                return;
            }
            sagas.recordUnknownAttempt(
                    pending.tenantId(), sagaId, "worker failure: " + failure.getClass().getSimpleName());
            if (shouldEscalate(pending)) {
                log.error("saga {} failing in the worker itself — raising an ops case", sagaId);
                sagas.escalate(pending.tenantId(), sagaId);
            } else {
                claims.scheduleRetry(sagaId, workerId, backoffFor(pending.attempts()));
            }
        } catch (RuntimeException recoveryFailure) {
            // Nothing more can be done here without risking the rest of the batch. The lease
            // expiry re-offers the saga, and the attempt counter — if the record above got through
            // — still converges on escalation.
            log.error("scheduling retry for saga {} failed too", sagaId, recoveryFailure);
        }
    }

    /**
     * Re-sends the saga's original outbound step under its original key, and applies whatever the
     * Ledger says.
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

        LedgerOutcome outcome;
        try {
            outcome = attempt(sagaId, pending);
        } catch (SagaRecords.Unretryable e) {
            // Nothing was sent, and nothing will be. Park it for a human rather than retrying a
            // saga that cannot be built — the reservation stays held, because whether the original
            // attempt posted is still unknown.
            log.error("saga {} cannot be re-driven and will not be retried: {}", sagaId, e.getMessage());
            sagas.escalate(pending.tenantId(), sagaId);
            return;
        }

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

    /**
     * The same outbound call the synchronous path made, under the same derived key.
     *
     * <p>The dispatch is the fix for a reversal that once looped forever: a REVERSAL saga's
     * synchronous path called {@code ledger.reverse} under the {@code :reverse} key, but the worker
     * rebuilt every claimed saga as a fresh posting under {@code :post} — a posting a reversal
     * cannot be, because it names a transaction rather than accounts. The Ledger's registry only
     * recognises a replay that repeats the original call, so anything else here is not recovery.
     */
    private LedgerOutcome attempt(UUID sagaId, SagaRecords.Pending pending) {
        if (pending.reversal()) {
            UUID target = pending.reversesLedgerTransactionId();
            if (target == null) {
                // A reversal of a transaction the ledger never confirmed has nothing to aim at,
                // and no retry changes that. openReversal only accepts COMPLETED targets, so this
                // is a state no code path writes today — but "cannot happen" is not a plan.
                throw new SagaRecords.Unretryable(
                        "REVERSAL saga targets a saga with no ledger transaction id");
            }
            return ledger.reverse(
                    pending.tenantId(),
                    target,
                    IdempotencyKeys.forStep(sagaId, IdempotencyKeys.REVERSE_STEP),
                    pending.initiatedBy());
        }
        return ledger.post(
                pending.tenantId(),
                pending.postingUnder(IdempotencyKeys.forStep(sagaId, IdempotencyKeys.POST_STEP)));
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
