package org.elyonar.fincore.ledger.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.elyonar.fincore.auth.NotAuthenticatedException;
import org.elyonar.fincore.ledger.tenant.TenantRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Who may address the ledger, and on whose behalf (ADR 0014).
 *
 * <p>The ledger's callers are <em>services</em>, never browsers — clients reach money through
 * Core, which proxies the reads it chooses to expose. So in {@code jwt} mode the rule is strict
 * and simple: the {@code Authorization} bearer must be a <strong>service credential</strong> (a
 * token with no tenant claim, whose client id is on the trusted-caller list — {@code core}, and
 * one day Reporting's read path). A user token presented directly is refused: a token that can
 * reach the ledger from a browser would make the ledger a second API surface around the crown
 * jewel, which is the thing ADR 0014 rejected.
 *
 * <p>The originating principal arrives separately, as {@code X-Forwarded-Authorization} — the
 * user's own verified token, forwarded by Core (outbound propagation). When present, it is
 * decoded with the same rigor: the tenant comes from <em>its</em> claim, and posting attribution
 * ({@code initiated_by}) comes from its principal — a verified fact, not a copied string. When
 * absent (worker jobs: recovery, reconciliation, recognition), the verified service caller may
 * assert the tenant via {@code X-Tenant-Id} — an assertion now scoped to an authenticated,
 * allowlisted service rather than to anyone holding a UUID.
 *
 * <p>{@code header} mode is the development stand-in, with the same two locks the shared
 * library's dev mode carries: it refuses to start outside a sanctioned profile, and it announces
 * itself loudly. The tenant-header posture the ledger's README carried as a known limitation ends
 * where this class begins.
 */
@Component
public class LedgerAuth {

    private static final Logger log = LoggerFactory.getLogger(LedgerAuth.class);
    private static final String FORWARDED = "X-Forwarded-Authorization";

    /** The resolved caller: whose money, and — when a user token was forwarded — who asked. */
    public record Identity(UUID tenantId, String attributedInitiator) {}

    private final TenantRegistry registry;
    private final boolean jwt;
    private final List<String> trustedCallers;
    private final String tenantClaim;
    private final String principalClaim;
    private final String serviceClaim;
    private final JwtDecoder decoder;

    public LedgerAuth(
            TenantRegistry registry,
            Environment environment,
            @Value("${fincore.ledger.auth.mode:header}") String mode,
            @Value("${fincore.ledger.auth.issuer-uri:}") String issuerUri,
            @Value("${fincore.ledger.auth.jwks-uri:}") String jwksUri,
            @Value("${fincore.ledger.auth.trusted-callers:core}") List<String> trustedCallers,
            @Value("${fincore.ledger.auth.tenant-claim:tenant_id}") String tenantClaim,
            @Value("${fincore.ledger.auth.principal-claim:preferred_username}") String principalClaim,
            @Value("${fincore.ledger.auth.service-claim:azp}") String serviceClaim,
            @Value("${fincore.ledger.auth.header-profiles:dev,test,local}") List<String> headerProfiles) {
        this.registry = registry;
        this.trustedCallers = List.copyOf(trustedCallers);
        this.tenantClaim = tenantClaim;
        this.principalClaim = principalClaim;
        this.serviceClaim = serviceClaim;

        switch (mode) {
            case "jwt" -> {
                this.jwt = true;
                this.decoder = buildDecoder(issuerUri, jwksUri);
                log.info(
                        "ledger auth: jwt — service credentials verified, trusted callers {}",
                        this.trustedCallers);
            }
            case "header" -> {
                // The same two locks as the shared library's dev mode: a stray environment
                // variable must not be enough to run unverified, and running unverified must be
                // impossible to miss in the logs.
                boolean sanctioned =
                        List.of(environment.getActiveProfiles()).stream().anyMatch(headerProfiles::contains);
                if (!sanctioned) {
                    throw new IllegalStateException(
                            "fincore.ledger.auth.mode=header verifies nothing and requires an active"
                                    + " profile from " + headerProfiles + ". Set mode=jwt for deployed"
                                    + " environments.");
                }
                this.jwt = false;
                this.decoder = null;
                log.warn(
                        """

                        ============================================================
                          LEDGER AUTH IS NOT VERIFYING ANYTHING — header mode
                          The tenant is taken from X-Tenant-Id. Any caller can
                          claim any tenant. Development only.
                          Set fincore.ledger.auth.mode=jwt to verify.
                        ============================================================""");
            }
            default ->
                    throw new IllegalStateException(
                            "unknown fincore.ledger.auth.mode '" + mode + "' (header|jwt)");
        }
    }

    private static JwtDecoder buildDecoder(String issuerUri, String jwksUri) {
        if (issuerUri == null || issuerUri.isBlank()) {
            throw new IllegalStateException(
                    "fincore.ledger.auth.mode=jwt requires fincore.ledger.auth.issuer-uri."
                            + " Refusing to start rather than accepting tokens nobody verified.");
        }
        if (jwksUri == null || jwksUri.isBlank()) {
            return NimbusJwtDecoder.withIssuerLocation(issuerUri).build();
        }
        // Keys fetched from where this service can reach them; trust still bound to the issuer —
        // the same reasoning as the shared library's decoder.
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuerUri));
        return decoder;
    }

    public Identity identify(HttpServletRequest request) {
        if (!jwt) {
            return new Identity(headerTenant(request), null);
        }

        // 1. The caller: a trusted service credential, or nothing.
        Jwt caller = decode(bearer(request.getHeader("Authorization"), true));
        if (caller.getClaimAsString(tenantClaim) != null) {
            // A tenant-scoped (user) token presented directly. Clients never address the ledger.
            throw new NotAuthenticatedException("the ledger accepts service credentials only");
        }
        String service = caller.getClaimAsString(serviceClaim);
        if (service == null || !trustedCallers.contains(service)) {
            throw new NotAuthenticatedException("caller is not on the ledger's trusted list");
        }

        // 2. The origin: a forwarded user token when a human drove this, else the verified
        //    caller's tenant assertion for its own system jobs.
        String forwarded = bearer(request.getHeader(FORWARDED), false);
        if (forwarded != null) {
            Jwt origin = decode(forwarded);
            String rawTenant = origin.getClaimAsString(tenantClaim);
            if (rawTenant == null || rawTenant.isBlank()) {
                throw new NotAuthenticatedException("forwarded token carries no tenant claim");
            }
            UUID tenantId = parseTenant(rawTenant);
            registry.requireActive(tenantId);
            String principal = origin.getClaimAsString(principalClaim);
            if (principal == null || principal.isBlank()) {
                principal = origin.getSubject();
            }
            return new Identity(tenantId, principal == null ? null : "user:" + principal);
        }
        return new Identity(headerTenant(request), null);
    }

    private UUID headerTenant(HttpServletRequest request) {
        String raw = request.getHeader(TenantResolver.TENANT_HEADER);
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(TenantResolver.TENANT_HEADER + " is required");
        }
        UUID tenantId = parseTenant(raw);
        // A well-formed UUID is not a tenant. Without this the header alone conjured a working
        // ledger for any id at all, which made "tenant" mean nothing.
        registry.requireActive(tenantId);
        return tenantId;
    }

    private static UUID parseTenant(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("tenant id must be a UUID");
        }
    }

    private Jwt decode(String token) {
        try {
            return decoder.decode(token);
        } catch (JwtException e) {
            // Logged with the reason, answered without it — a caller learning exactly why a token
            // failed learns how to make one that does not.
            log.debug("ledger rejected a token: {}", e.getMessage());
            throw new NotAuthenticatedException("token rejected", e);
        }
    }

    private static String bearer(String header, boolean required) {
        if (header == null || header.isBlank()) {
            if (required) {
                throw new NotAuthenticatedException("no bearer token");
            }
            return null;
        }
        String token =
                header.regionMatches(true, 0, "Bearer ", 0, 7) ? header.substring(7).trim() : header.trim();
        if (token.isEmpty()) {
            if (required) {
                throw new NotAuthenticatedException("empty bearer token");
            }
            return null;
        }
        return token;
    }
}
