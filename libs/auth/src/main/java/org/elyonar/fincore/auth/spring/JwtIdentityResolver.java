package org.elyonar.fincore.auth.spring;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.elyonar.fincore.auth.IdentityContext;
import org.elyonar.fincore.auth.IdentityResolver;
import org.elyonar.fincore.auth.NotAuthenticatedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Identifies callers by a signed token, verified against the issuer's published keys.
 *
 * <p>Verification is local — the keys are fetched and cached, so no call is made to the identity
 * provider per request. That is what makes Identity critical infrastructure rather than a
 * per-transaction dependency: if it is briefly down, issued tokens keep working and money keeps
 * moving; only new logins block (PRD §6.1).
 */
public class JwtIdentityResolver implements IdentityResolver {

    private static final String BEARER = "Bearer ";

    private final JwtDecoder decoder;
    private final AuthProperties properties;

    public JwtIdentityResolver(JwtDecoder decoder, AuthProperties properties) {
        this.decoder = decoder;
        this.properties = properties;
    }

    @Override
    public IdentityContext resolve(HttpServletRequest request) {
        Jwt jwt = decode(bearerToken(request));

        // The tenant is a claim, never a header. A header-supplied tenant is a caller assertion,
        // and every downstream isolation control would faithfully enforce the wrong boundary.
        UUID tenantId = tenant(jwt);

        String principal = jwt.getClaimAsString(properties.getPrincipalClaim());
        if (principal == null || principal.isBlank()) {
            principal = jwt.getSubject();
        }
        if (principal == null || principal.isBlank()) {
            throw new NotAuthenticatedException("token carries no principal");
        }

        return new IdentityContext(
                tenantId,
                "user:" + principal,
                ServiceIdentity.of(request),
                permissions(jwt),
                jwt.getId(),
                units(jwt));
    }

    private String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            throw new NotAuthenticatedException("no bearer token");
        }
        String token = header.substring(BEARER.length()).trim();
        if (token.isEmpty()) {
            throw new NotAuthenticatedException("empty bearer token");
        }
        return token;
    }

    private Jwt decode(String token) {
        try {
            return decoder.decode(token);
        } catch (JwtException e) {
            // The reason is logged by the caller, not returned: a caller learning precisely why a
            // token failed learns how to make one that does not.
            throw new NotAuthenticatedException("token rejected", e);
        }
    }

    private UUID tenant(Jwt jwt) {
        String raw = jwt.getClaimAsString(properties.getTenantClaim());
        if (raw == null || raw.isBlank()) {
            throw new NotAuthenticatedException(
                    "token carries no " + properties.getTenantClaim() + " claim");
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new NotAuthenticatedException(
                    properties.getTenantClaim() + " claim is not a UUID", e);
        }
    }

    private Set<String> permissions(Jwt jwt) {
        List<String> claimed = jwt.getClaimAsStringList(properties.getPermissionsClaim());
        // No permissions claim is not an error — it is a caller who may do nothing. Deny by
        // default means an absent claim and an empty one behave identically.
        return claimed == null ? Set.of() : new LinkedHashSet<>(claimed);
    }

    private Set<String> units(Jwt jwt) {
        // Same rule as permissions: an absent claim is an empty scope, never an error. Most
        // machine identities and pre-organizational tenants carry none (ADR 0012).
        List<String> claimed = jwt.getClaimAsStringList(properties.getUnitsClaim());
        return claimed == null ? Set.of() : new LinkedHashSet<>(claimed);
    }

    @Override
    public String name() {
        return "jwt";
    }

    @Override
    public boolean verifies() {
        return true;
    }
}
