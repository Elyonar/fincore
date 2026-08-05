package org.elyonar.fincore.ledger.hold;

import java.time.Instant;
import java.util.UUID;

/**
 * A hold's current state, for crash recovery reads.
 *
 * <p>An orchestrator that restarted mid-flow must be able to ask what happened to its reservation
 * without mutating it — asking should never be the thing that changes the answer.
 */
public record HoldView(
        UUID id, UUID accountId, long amountMinor, String currency, String status, Instant expiresAt) {}
