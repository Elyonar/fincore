package org.elyonar.fincore.ledger.api;

import java.util.Map;
import org.elyonar.fincore.ledger.posting.ReversalService;
import org.elyonar.fincore.ledger.shared.ErrorCode;
import org.elyonar.fincore.ledger.shared.LedgerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the error catalog onto HTTP.
 *
 * <p>The mapping is deliberate rather than incidental, because Orchestration's retry behaviour
 * depends on it:
 *
 * <ul>
 *   <li><strong>422</strong> — the request is malformed or unbalanced. It will never succeed as
 *       written.
 *   <li><strong>404</strong> — unknown, which includes another tenant's. Not-found and
 *       wrong-tenant are deliberately indistinguishable, so a caller cannot probe for the
 *       existence of another tenant's accounts.
 *   <li><strong>409</strong> — the request is well-formed but the ledger's state refuses it.
 * </ul>
 *
 * <p>All of these are 4xx and therefore <em>terminal for the idempotency key</em>. Only a 5xx or a
 * timeout means "unknown outcome, retry the same key" — which is why nothing here is allowed to
 * turn a state conflict into a 500.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final Map<ErrorCode, HttpStatus> STATUSES =
            Map.ofEntries(
                    Map.entry(ErrorCode.UNBALANCED, HttpStatus.UNPROCESSABLE_ENTITY),
                    Map.entry(ErrorCode.WASH_TRANSACTION, HttpStatus.UNPROCESSABLE_ENTITY),
                    Map.entry(ErrorCode.LIMIT_EXCEEDED, HttpStatus.UNPROCESSABLE_ENTITY),
                    Map.entry(ErrorCode.CURRENCY_MISMATCH, HttpStatus.UNPROCESSABLE_ENTITY),
                    Map.entry(ErrorCode.VALUE_DATE_INVALID, HttpStatus.UNPROCESSABLE_ENTITY),
                    Map.entry(ErrorCode.ACCOUNT_NOT_FOUND, HttpStatus.NOT_FOUND),
                    Map.entry(ErrorCode.ACCOUNT_CLOSED, HttpStatus.CONFLICT),
                    Map.entry(ErrorCode.INSUFFICIENT_FUNDS, HttpStatus.CONFLICT),
                    Map.entry(ErrorCode.IDEMPOTENCY_KEY_REUSED, HttpStatus.CONFLICT),
                    Map.entry(ErrorCode.HOLD_NOT_ACTIVE, HttpStatus.CONFLICT),
                    Map.entry(ErrorCode.HOLD_EXCEEDED, HttpStatus.CONFLICT),
                    Map.entry(ErrorCode.ALREADY_REVERSED, HttpStatus.CONFLICT),
                    Map.entry(ErrorCode.REVERSAL_OF_REVERSAL, HttpStatus.CONFLICT),
                    Map.entry(ErrorCode.HAS_COMPENSATIONS, HttpStatus.CONFLICT),
                    Map.entry(ErrorCode.TARGET_REVERSED, HttpStatus.CONFLICT),
                    Map.entry(ErrorCode.CLOSE_BLOCKED, HttpStatus.CONFLICT),
                    Map.entry(ErrorCode.SWEEP_INVALID, HttpStatus.CONFLICT));

    @ExceptionHandler(ReversalService.AlreadyReversedException.class)
    public ResponseEntity<ApiError> alreadyReversed(ReversalService.AlreadyReversedException e) {
        // The winning reversal's id travels with the error so a saga converges on it instead of
        // retry-looping against a state that will never change again.
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(
                        new ApiError(
                                ErrorCode.ALREADY_REVERSED.code(),
                                e.getMessage(),
                                false,
                                e.reversalId() == null ? null : e.reversalId().toString()));
    }

    @ExceptionHandler(LedgerException.class)
    public ResponseEntity<ApiError> ledgerRejection(LedgerException e) {
        HttpStatus status = STATUSES.getOrDefault(e.errorCode(), HttpStatus.CONFLICT);
        return ResponseEntity.status(status).body(ApiError.of(e.errorCode().code(), e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> malformed(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ApiError.of("BAD_REQUEST", e.getMessage()));
    }

    /**
     * Anything unmapped is a 5xx, and that is the honest answer.
     *
     * <p>A 5xx tells the caller the outcome is unknown and it must retry the same key. Dressing an
     * unexpected failure up as a 4xx would tell it the opposite — that the key is spent — and
     * push it into minting a new one for an operation that may have committed.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("unhandled failure serving a ledger request", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("INTERNAL", "the outcome is unknown; retry with the same idempotency key", true, null));
    }
}
