package org.elyonar.fincore.core.orchestration.api;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Everything a member of staff may know about one posting.
 *
 * <p>Distinct from {@link TransferResult}, and deliberately. {@code TransferResult} is what a caller
 * is told when it posts: the outcome of the thing it just asked for. This is what somebody
 * <em>investigating</em> a posting needs afterwards — when it happened, who asked for it, what
 * priced it, whether it has already been undone. A receipt and a case file are not the same
 * document.
 *
 * <p><strong>What is deliberately absent.</strong> The saga carries more than this: the limit
 * headroom the decision was made against, the idempotency key the channel sent, the request
 * fingerprint, the retry bookkeeping and the last error text. None of it appears here. The limits
 * are the institution's control settings and are nobody's business at a counter; the idempotency
 * key belongs to the caller that minted it; and the error text is developer English, which no
 * client displays or parses. A read that returns "everything on the row" is a read that leaks the
 * first sensitive column somebody adds to it.
 *
 * @param reversesTransactionId set when this posting is itself a reversal — what it undid
 * @param reversedByTransactionId set when this posting has been undone — the reversal that did it.
 *     The pair is what stops a second reversal being raised against something already put right
 * @param terminalAt when the outcome became final; null while it is still being determined
 */
public record TransactionDetail(
        UUID transactionId,
        String reference,
        String type,
        String state,
        long amountMinor,
        long feeMinor,
        String currency,
        String channel,
        String productCode,
        Integer productVersion,
        UUID subjectCustomerId,
        UUID fromAccountId,
        UUID toAccountId,
        UUID feeAccountId,
        UUID tillId,
        UUID ledgerTransactionId,
        String initiatedBy,
        OffsetDateTime createdAt,
        OffsetDateTime terminalAt,
        UUID reversesTransactionId,
        String reversesReference,
        UUID reversedByTransactionId,
        String reversedByReference,
        UUID approvalId) {}
