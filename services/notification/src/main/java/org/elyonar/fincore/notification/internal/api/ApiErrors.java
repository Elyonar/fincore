package org.elyonar.fincore.notification.internal.api;

import java.util.Map;
import org.elyonar.fincore.auth.NotAuthenticatedException;
import org.elyonar.fincore.auth.NotAuthorizedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Every rejection carries a machine-readable code (AGENTS.md hard rule 9).
 *
 * <p>The {@code message} is developer English for a log and is never shown to an end user: a
 * platform serving Lagos and Abidjan cannot write that text for either, so a caller renders from
 * {@code code} and {@code details}, and the sentence here is for whoever is reading a stack trace.
 */
@RestControllerAdvice
public class ApiErrors {

    /** @param details the facts a message would interpolate, never prose a caller must parse */
    public record Error(String code, String message, Map<String, Object> details) {}

    @ExceptionHandler(NotAuthenticatedException.class)
    public ResponseEntity<Void> unauthenticated(NotAuthenticatedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    @ExceptionHandler(NotAuthorizedException.class)
    public ResponseEntity<Void> unauthorized(NotAuthorizedException e) {
        // Empty body: naming the permission that would have worked hands a prober the model.
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    @ExceptionHandler(NotFound.class)
    public ResponseEntity<Error> notFound(NotFound e) {
        // Not-found and wrong-tenant are one answer, as everywhere on this platform: distinguishing
        // them confirms that a record exists somewhere.
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new Error(e.code(), e.getMessage(), e.details()));
    }

    @ExceptionHandler(Unprocessable.class)
    public ResponseEntity<Error> unprocessable(Unprocessable e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new Error(e.code(), e.getMessage(), e.details()));
    }

    @ExceptionHandler(Conflict.class)
    public ResponseEntity<Error> conflict(Conflict e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Error(e.code(), e.getMessage(), e.details()));
    }

    /** Base for every documented rejection, so a code cannot exist without one. */
    public abstract static class Coded extends RuntimeException {
        private final String code;
        private final transient Map<String, Object> details;

        protected Coded(String code, String message, Map<String, Object> details) {
            super(message);
            this.code = code;
            this.details = details;
        }

        public String code() {
            return code;
        }

        public Map<String, Object> details() {
            return details;
        }
    }

    public static class NotFound extends Coded {
        public NotFound(String code, String message, Map<String, Object> details) {
            super(code, message, details);
        }
    }

    public static class Unprocessable extends Coded {
        public Unprocessable(String code, String message, Map<String, Object> details) {
            super(code, message, details);
        }
    }

    public static class Conflict extends Coded {
        public Conflict(String code, String message, Map<String, Object> details) {
            super(code, message, details);
        }
    }
}
