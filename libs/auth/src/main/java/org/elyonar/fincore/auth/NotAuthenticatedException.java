package org.elyonar.fincore.auth;

/**
 * The caller could not be identified: no token, an invalid one, or an expired one.
 *
 * <p>Distinct from {@link NotAuthorizedException} because the answers differ — this one means
 * "prove who you are", that one means "you are known and may not do this". A single error for both
 * tells an attacker as little as a caller trying to fix their integration.
 */
public class NotAuthenticatedException extends RuntimeException {

    public NotAuthenticatedException(String message) {
        super(message);
    }

    public NotAuthenticatedException(String message, Throwable cause) {
        super(message, cause);
    }
}
