package org.elyonar.fincore.auth;

/**
 * The caller is known and lacks what this action requires.
 *
 * <p>Carries what was required so a service can log the denial usefully. It is deliberately not
 * carried into the HTTP response body: telling a caller exactly which permission would have worked
 * is a map of the permission model handed to whoever is probing.
 */
public class NotAuthorizedException extends RuntimeException {

    private final String required;

    public NotAuthorizedException(String required, String message) {
        super(message);
        this.required = required;
    }

    /** The permission or service identity that would have satisfied the check. */
    public String required() {
        return required;
    }
}
