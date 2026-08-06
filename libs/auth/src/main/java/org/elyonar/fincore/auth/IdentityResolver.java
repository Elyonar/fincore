package org.elyonar.fincore.auth;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Turns an inbound request into an {@link IdentityContext}, or refuses it.
 *
 * <p>The seam exists so that services are written against identity rather than against Keycloak.
 * ADR 0010 permits a development implementation to stand in while realms are being provisioned,
 * under one condition that this interface's implementations must honour: an insecure resolver must
 * be impossible to enable accidentally in a deployed environment and must announce itself loudly at
 * startup — the same discipline the ledger's {@code log} event adapter follows.
 */
public interface IdentityResolver {

    /**
     * @throws NotAuthenticatedException when the caller cannot be identified
     */
    IdentityContext resolve(HttpServletRequest request);

    /** Short name for the startup banner, e.g. {@code jwt} or {@code dev}. */
    String name();

    /**
     * Whether this resolver actually verifies anything.
     *
     * <p>Reported at startup so "we have authentication" is a claim someone can check rather than
     * assume.
     */
    boolean verifies();
}
