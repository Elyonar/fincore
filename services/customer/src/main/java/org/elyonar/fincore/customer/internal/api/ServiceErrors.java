package org.elyonar.fincore.customer.internal.api;

import java.util.Map;
import org.elyonar.fincore.auth.NotAuthenticatedException;
import org.elyonar.fincore.auth.NotAuthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The rejections every controller here shares, with a machine-readable code (hard rule 9).
 *
 * <p>New with the extraction, and its absence was the first thing the moved suite caught. These
 * three handlers used to live in Core's orchestration advice and covered every module in the
 * process; once Customer became a deployable it had `CustomerApiErrors` for its own domain
 * exceptions and nothing at all for authentication, authorisation or a bare argument failure. The
 * result was a 500 everywhere a 401, 403 or 422 was owed — which is not merely an unhelpful status
 * but a claim that the service broke, when in fact it refused correctly.
 *
 * <p>Scoped to the whole service rather than to one controller, deliberately: an endpoint added
 * later and forgotten about should inherit the right refusals rather than fall through to a 500.
 *
 * <p>The {@code message} is developer English for a log and is never shown to an end user. A
 * platform serving Lagos and Abidjan cannot write that text for either, so a caller renders from
 * {@code code} and {@code details}.
 */
@RestControllerAdvice
public class ServiceErrors {

    private static final Logger log = LoggerFactory.getLogger(ServiceErrors.class);

    /** @param details the facts a message would interpolate, never prose a caller must parse */
    public record ApiError(String code, String message, Map<String, Object> details) {}

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
     * <p>A documented code rather than the exception's English message in the place a code belongs.
     * The message still travels, in the field a developer reads.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> invalid(IllegalArgumentException e) {
        return ResponseEntity.unprocessableEntity()
                .body(new ApiError("COMMAND_INVALID", e.getMessage(), Map.of()));
    }
}
