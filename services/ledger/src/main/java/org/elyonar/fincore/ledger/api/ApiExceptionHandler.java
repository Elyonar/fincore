package org.elyonar.fincore.ledger.api;

import java.time.DateTimeException;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.elyonar.fincore.ledger.posting.ReversalService;
import org.elyonar.fincore.ledger.shared.ErrorCode;
import org.elyonar.fincore.ledger.shared.ErrorReason;
import org.elyonar.fincore.ledger.shared.LedgerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
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
                    Map.entry(ErrorCode.SWEEP_INVALID, HttpStatus.CONFLICT),
                    Map.entry(ErrorCode.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS));

    @ExceptionHandler(ReversalService.AlreadyReversedException.class)
    public ResponseEntity<ApiError> alreadyReversed(ReversalService.AlreadyReversedException e) {
        // The winning reversal's id travels with the error so a saga converges on it instead of
        // retry-looping against a state that will never change again.
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(
                        ApiError.of(
                                ErrorCode.ALREADY_REVERSED.code(),
                                e.getMessage(),
                                false,
                                e.reversalId() == null ? null : e.reversalId().toString()));
    }

    @ExceptionHandler(LedgerException.class)
    public ResponseEntity<ApiError> ledgerRejection(LedgerException e) {
        HttpStatus status = STATUSES.getOrDefault(e.errorCode(), HttpStatus.CONFLICT);
        return ResponseEntity.status(status)
                .body(
                        new ApiError(
                                e.errorCode().code(),
                                e.reason(),
                                e.getMessage(),
                                false,
                                null,
                                e.details()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> malformed(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ApiError.of("BAD_REQUEST", e.getMessage()));
    }

    /**
     * Dates bind as strings and are parsed in application code, so a malformed one never reaches
     * Jackson and {@code DateTimeParseException} is not an {@code IllegalArgumentException}.
     * Before this handler it fell to the catch-all below, which answered 500 with
     * {@code retryableWithSameKey: true} — instructing Orchestration to retry a caller typo, on a
     * payment, with the same key, forever. A date that does not parse can never succeed as
     * written: a documented 422, terminal for the key, like every other malformed request.
     */
    @ExceptionHandler(DateTimeException.class)
    public ResponseEntity<ApiError> malformedDate(DateTimeException e) {
        Map<String, String> details =
                e instanceof DateTimeParseException parse && parse.getParsedString() != null
                        ? Map.of("supplied", parse.getParsedString())
                        : Map.of();
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(
                        new ApiError(
                                ErrorCode.VALUE_DATE_INVALID.code(),
                                ErrorReason.VALUE_DATE_MALFORMED,
                                e.getMessage(),
                                false,
                                null,
                                details));
    }

    /**
     * Unverified callers get a bare 401 (ADR 0014). The reason stays in the debug log: a caller
     * learning exactly why a credential failed learns how to make one that does not. 4xx, so an
     * orchestrator treats it as terminal for the key rather than as an unknown outcome.
     */
    @ExceptionHandler(org.elyonar.fincore.auth.NotAuthenticatedException.class)
    public ResponseEntity<ApiError> unauthenticated(org.elyonar.fincore.auth.NotAuthenticatedException e) {
        log.debug("rejected an unauthenticated ledger call: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of("NOT_AUTHENTICATED", "credentials required"));
    }

    /**
     * Spring's own web failures keep their own status.
     *
     * <p>A missing route, an unreadable body or a wrong method is a 4xx that Spring has already
     * classified correctly. Letting these reach the catch-all below turned every mistyped URL into
     * a 500 carrying {@code retryableWithSameKey: true} — which tells Orchestration the outcome is
     * unknown and it must retry. A typo must never be able to say that about a payment.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiError> unknownRoute(Exception e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", "no such endpoint"));
    }

    /** Malformed JSON, wrong method, missing parameter — Spring has already classified these. */
    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<ApiError> springWebFailure(ErrorResponseException e) {
        HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
        return ResponseEntity.status(status)
                .body(
                        ApiError.of(
                                status == HttpStatus.NOT_FOUND ? "NOT_FOUND" : "BAD_REQUEST",
                                e.getBody().getDetail() == null ? status.getReasonPhrase() : e.getBody().getDetail(),
                                false,
                                null));
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
                .body(ApiError.of("INTERNAL", "the outcome is unknown; retry with the same idempotency key", true, null));
    }
}
