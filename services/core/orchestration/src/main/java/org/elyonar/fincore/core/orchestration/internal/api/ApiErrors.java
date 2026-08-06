package org.elyonar.fincore.core.orchestration.internal.api;

import org.elyonar.fincore.auth.NotAuthenticatedException;
import org.elyonar.fincore.auth.NotAuthorizedException;
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
 */
@RestControllerAdvice
public class ApiErrors {

    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    /** A refusal. Terminal for this key — a new logical attempt mints a new one. */
    @ExceptionHandler(TransferService.TransferRefused.class)
    public ResponseEntity<ApiError> refused(TransferService.TransferRefused e) {
        return ResponseEntity.unprocessableEntity().body(new ApiError(e.code(), null));
    }

    /** Same key, different economics. A caller bug, and never a silent wrong answer. */
    @ExceptionHandler(TransferService.IdempotencyKeyReused.class)
    public ResponseEntity<ApiError> keyReused(TransferService.IdempotencyKeyReused e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("IDEMPOTENCY_KEY_REUSED", null));
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

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> invalid(IllegalArgumentException e) {
        return ResponseEntity.unprocessableEntity().body(new ApiError(e.getMessage(), null));
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
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError("OUTCOME_UNKNOWN", e.transactionId().toString()));
    }

    /** @param transactionId present when the caller has something to poll */
    public record ApiError(String code, String transactionId) {}
}
