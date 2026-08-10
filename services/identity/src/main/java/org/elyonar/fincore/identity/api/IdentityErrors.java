package org.elyonar.fincore.identity.api;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The error contract (AGENTS.md hard rule 9), with this service's one documented deviation:
 * {@code AUTH_FAILED} and {@code TOKEN_INVALID} carry no {@code reason}, deliberately. Each
 * distinguishing reason on this surface is an oracle an attacker farms — user exists, user is
 * locked, token was once valid. The audit trail carries the true cause; the wire never does.
 */
@RestControllerAdvice
public class IdentityErrors {

    /** The catalog. api.md documents these and {@code ErrorCatalogTest} keeps both honest. */
    public static final class Codes {
        public static final String AUTH_FAILED = "AUTH_FAILED";
        public static final String ACTION_REQUIRED = "ACTION_REQUIRED";
        public static final String PASSWORD_POLICY = "PASSWORD_POLICY";
        public static final String TOKEN_INVALID = "TOKEN_INVALID";
        public static final String RATE_LIMITED = "RATE_LIMITED";

        private Codes() {}
    }

    public record Error(String code, String reason, String message, Map<String, Object> details) {}

    /** One shape, one voice: every credential refusal is byte-identical (design.md D5). */
    @ExceptionHandler(AuthFailed.class)
    public ResponseEntity<Error> authFailed(AuthFailed e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new Error(Codes.AUTH_FAILED, null, "credential refused", Map.of()));
    }

    @ExceptionHandler(TokenInvalid.class)
    public ResponseEntity<Error> tokenInvalid(TokenInvalid e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new Error(Codes.TOKEN_INVALID, null, "token refused", Map.of()));
    }

    @ExceptionHandler(RateLimited.class)
    public ResponseEntity<Error> rateLimited(RateLimited e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(e.retryAfterSeconds))
                .body(new Error(Codes.RATE_LIMITED, null, "slow down", Map.of()));
    }

    @ExceptionHandler(PasswordPolicy.class)
    public ResponseEntity<Error> passwordPolicy(PasswordPolicy e) {
        return ResponseEntity.badRequest()
                .body(new Error(
                        Codes.PASSWORD_POLICY,
                        e.violations.isEmpty() ? null : e.violations.get(0),
                        "password refused by policy",
                        Map.of("violations", e.violations)));
    }

    /**
     * The directory's refusals, which do carry a code and a reason.
     *
     * <p>The uniform-401 rule above is a control for a pre-identity surface an attacker can reach.
     * It is not a house style: an authenticated administrator asking about their own institution's
     * records is owed the fact, and hiding it here would only move the question to a support queue.
     */
    @ExceptionHandler(Refused.class)
    public ResponseEntity<Error> refused(Refused e) {
        return ResponseEntity.status(e.status)
                .body(new Error(e.code, e.reason, e.getMessage(), e.details));
    }

    /**
     * A named refusal from a surface that may name it. HTTP status is derived from the code so
     * that one throw site cannot disagree with itself about what it means.
     */
    public static class Refused extends RuntimeException {
        final String code;
        final String reason;
        final Map<String, Object> details;
        final HttpStatus status;

        public Refused(String code, String reason, Map<String, Object> details) {
            super("refused: " + code);
            this.code = code;
            this.reason = reason;
            this.details = details == null ? Map.of() : details;
            this.status = statusFor(code);
        }

        private static HttpStatus statusFor(String code) {
            return switch (code) {
                case "USER_NOT_FOUND" -> HttpStatus.NOT_FOUND;
                case "USER_EXISTS", "LAST_ADMINISTRATOR", "ROLE_EXISTS", "ROLE_IN_USE", "ROLE_NOT_CUSTOM" ->
                        HttpStatus.CONFLICT;
                case "PERMISSION_NOT_HELD_BY_GRANTOR" -> HttpStatus.FORBIDDEN;
                default -> HttpStatus.BAD_REQUEST;
            };
        }
    }

    /** Thrown for every credential failure; the constructor's cause never reaches the wire. */
    public static class AuthFailed extends RuntimeException {
        public AuthFailed() {
            super("credential refused");
        }
    }

    public static class TokenInvalid extends RuntimeException {
        public TokenInvalid() {
            super("token refused");
        }
    }

    public static class RateLimited extends RuntimeException {
        final int retryAfterSeconds;

        public RateLimited(int retryAfterSeconds) {
            super("rate limited");
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    public static class PasswordPolicy extends RuntimeException {
        final List<String> violations;

        public PasswordPolicy(List<String> violations) {
            super("password refused by policy");
            this.violations = violations;
        }
    }
}
