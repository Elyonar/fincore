package org.elyonar.fincore.ledger.posting;

import java.util.UUID;

/**
 * The outcome of a posting.
 *
 * <p>{@code replayed} distinguishes "this call moved money" from "this call returned the result of
 * an earlier call that moved money". Both are 200s carrying the same transaction id — the caller
 * cannot tell them apart from the money, and should not have to.
 */
public record PostingResult(UUID transactionId, boolean replayed) {

    static PostingResult posted(UUID transactionId) {
        return new PostingResult(transactionId, false);
    }

    static PostingResult replay(UUID transactionId) {
        return new PostingResult(transactionId, true);
    }
}
