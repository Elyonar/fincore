package org.elyonar.fincore.core.orchestration.internal.api;

import java.util.LinkedHashMap;
import java.util.Map;
import org.elyonar.fincore.auth.NotAuthenticatedException;
import org.elyonar.fincore.auth.NotAuthorizedException;
import org.elyonar.fincore.core.orchestration.api.CoreException;
import org.elyonar.fincore.core.orchestration.api.DetailKey;
import org.elyonar.fincore.core.orchestration.api.ErrorCode;
import org.elyonar.fincore.core.orchestration.internal.approval.ApprovalRecords;
import org.elyonar.fincore.core.orchestration.internal.saga.TransferService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The error catalog, as HTTP.
 *
 * <p>The mapping that matters is the last one. <strong>An unknown outcome is a 5xx, never a
 * success-shaped 202</strong>, because under Core's own retry rule a 5xx obliges the caller to
 * retry the same idempotency key — and that retry is what eventually resolves the saga. A 202 would
 * invite a teller to record "submitted" and move on, telling a customer the transfer went through
 * while the platform does not know whether it did.
 *
 * <p>Every body follows {@code docs/conventions/error-contract.md}: a documented {@code code}, a
 * {@code reason} where one code spans several causes, machine-readable {@code details}, and a
 * developer-English {@code message} that no channel displays or parses.
 */
@RestControllerAdvice
public class ApiErrors {

    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    /** 4xx statuses per code. Everything terminal for the key; only 5xx means "retry the same". */
    private static final Map<ErrorCode, HttpStatus> STATUSES =
            Map.ofEntries(
                    Map.entry(ErrorCode.CUSTOMER_NOT_FOUND, HttpStatus.UNPROCESSABLE_ENTITY),
                    Map.entry(ErrorCode.CUSTOMER_NOT_ACTIVE, HttpStatus.UNPROCESSABLE_ENTITY),
                    Map.entry(ErrorCode.ACCOUNT_NOT_LINKED, HttpStatus.UNPROCESSABLE_ENTITY),
                    Map.entry(ErrorCode.PRODUCT_NOT_FOUND, HttpStatus.UNPROCESSABLE_ENTITY),
                    Map.entry(ErrorCode.OPERATION_NOT_PERMITTED, HttpStatus.UNPROCESSABLE_ENTITY),
                    Map.entry(ErrorCode.LIMIT_EXCEEDED, HttpStatus.UNPROCESSABLE_ENTITY),
                    Map.entry(ErrorCode.AMOUNT_INVALID, HttpStatus.UNPROCESSABLE_ENTITY),
                    Map.entry(ErrorCode.WASH_TRANSACTION, HttpStatus.UNPROCESSABLE_ENTITY),
                    Map.entry(ErrorCode.CURRENCY_MISMATCH, HttpStatus.UNPROCESSABLE_ENTITY),
                    Map.entry(ErrorCode.COMMAND_INVALID, HttpStatus.UNPROCESSABLE_ENTITY),
                    Map.entry(ErrorCode.TILL_NOT_OPEN, HttpStatus.UNPROCESSABLE_ENTITY),
                    Map.entry(ErrorCode.FEE_EXCEEDS_DEPOSIT, HttpStatus.UNPROCESSABLE_ENTITY),
                    Map.entry(ErrorCode.INSUFFICIENT_FUNDS, HttpStatus.UNPROCESSABLE_ENTITY),
                    Map.entry(ErrorCode.LEDGER_REFUSED, HttpStatus.UNPROCESSABLE_ENTITY),
                    Map.entry(ErrorCode.NOT_REVERSIBLE, HttpStatus.UNPROCESSABLE_ENTITY),
                    Map.entry(ErrorCode.APPROVAL_REQUIRED, HttpStatus.UNPROCESSABLE_ENTITY),
                    Map.entry(ErrorCode.TRANSACTION_NOT_FOUND, HttpStatus.NOT_FOUND),
                    Map.entry(ErrorCode.IDEMPOTENCY_KEY_REUSED, HttpStatus.CONFLICT),
                    Map.entry(ErrorCode.ALREADY_REVERSED, HttpStatus.CONFLICT),
                    Map.entry(ErrorCode.LEDGER_UNREACHABLE, HttpStatus.SERVICE_UNAVAILABLE),
                    Map.entry(ErrorCode.OUTCOME_UNKNOWN, HttpStatus.SERVICE_UNAVAILABLE));

    /**
     * Every contract refusal, including {@code TransferRefused} and {@code NotReversible}.
     *
     * <p>One handler rather than one per exception: the code decides the status, so a new refusal
     * cannot accidentally get a status nobody chose.
     */
    @ExceptionHandler(CoreException.class)
    public ResponseEntity<ApiError> refused(CoreException e) {
        HttpStatus status = STATUSES.getOrDefault(e.errorCode(), HttpStatus.UNPROCESSABLE_ENTITY);
        return ResponseEntity.status(status)
                .body(
                        new ApiError(
                                e.errorCode().code(),
                                e.reason(),
                                e.getMessage(),
                                status.is5xxServerError(),
                                null,
                                e.details()));
    }

    /** Same key, different economics. A caller bug, and never a silent wrong answer. */
    @ExceptionHandler(TransferService.IdempotencyKeyReused.class)
    public ResponseEntity<ApiError> keyReused(TransferService.IdempotencyKeyReused e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(ErrorCode.IDEMPOTENCY_KEY_REUSED.code(), e.getMessage()));
    }

    /**
     * The approval does not authorise this reversal — wrong target, wrong amount, unapproved, or
     * already spent. A 403: the request is well formed, the authority is not there.
     *
     * <p>The message says which of the four it was, and stays in the log. A caller is told only
     * that the authority was insufficient, because naming the discrepancy tells a prober what a
     * valid approval would have to look like.
     */
    @ExceptionHandler(ApprovalRecords.ApprovalRejected.class)
    public ResponseEntity<ApiError> approvalRejected(ApprovalRecords.ApprovalRejected e) {
        log.debug("approval refused: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(ErrorCode.APPROVAL_INVALID.code(), "the approval does not authorise this reversal"));
    }

    @ExceptionHandler(NotAuthenticatedException.class)
    public ResponseEntity<Void> unauthenticated() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    /** Body-less: naming the permission that would have worked is a map handed to a prober. */
    @ExceptionHandler(NotAuthorizedException.class)
    public ResponseEntity<Void> unauthorized(NotAuthorizedException e) {
        log.debug("denied: required {}", e.required());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    /**
     * Anything still throwing a bare {@code IllegalArgumentException}.
     *
     * <p>It carries a documented code now instead of putting the exception's English message where
     * the code belongs. The message still travels, in the field a developer reads.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> invalid(IllegalArgumentException e) {
        return ResponseEntity.unprocessableEntity()
                .body(ApiError.of(ErrorCode.COMMAND_INVALID.code(), e.getMessage()));
    }

    /**
     * The outcome is not known.
     *
     * <p>503 rather than 500: the caller's correct response is to retry the same key, and 503 says
     * "ask again" without claiming anything about what happened. The transaction id travels with it
     * so the caller can poll {@code GET /v1/transactions/{id}} instead of mutating to find out.
     */
    @ExceptionHandler(TransferService.OutcomeUnknown.class)
    public ResponseEntity<ApiError> unknown(TransferService.OutcomeUnknown e) {
        log.warn("outcome unknown for saga {}", e.transactionId());
        Map<String, String> details = new LinkedHashMap<>();
        details.put(DetailKey.TRANSACTION_ID, String.valueOf(e.transactionId()));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(
                        new ApiError(
                                ErrorCode.OUTCOME_UNKNOWN.code(),
                                null,
                                "the outcome is unknown; retry with the same idempotency key",
                                true,
                                e.transactionId() == null ? null : e.transactionId().toString(),
                                details));
    }
}
