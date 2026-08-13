package org.elyonar.fincore.identity.token;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.elyonar.fincore.identity.internal.IdentityProperties;
import org.springframework.stereotype.Component;

/**
 * Mints exactly the token the platform already verifies — the acceptance boundary of ADR 0018.
 *
 * <p>Staff: {@code iss}, {@code sub}, {@code preferred_username}, {@code tenant_id},
 * {@code permissions}, {@code units}, {@code jti}, {@code azp}. Service: the same minus any
 * tenant claim — the ledger's rule for a trusted service caller, unchanged. The claims are
 * resolved at mint time, which makes admin-surface.md's revocation-window property exactly true:
 * a role change takes effect on the next minted token.
 *
 * <p>Action grants are deliberately *not* platform tokens: they carry no {@code tenant_id} and no
 * {@code permissions}, so {@code libs/auth} rejects them outright and replaying one at any other
 * service grants nothing. Their tenant rides in a private claim only this service reads.
 */
@Component
public class TokenMinter {

    public static final String CLAIM_TENANT = "tenant_id";
    public static final String CLAIM_USERNAME = "preferred_username";
    public static final String CLAIM_PERMISSIONS = "permissions";
    public static final String CLAIM_UNITS = "units";
    public static final String CLAIM_AZP = "azp";
    public static final String CLAIM_ACTION = "act";
    public static final String CLAIM_ACTION_TENANT = "tid";
    public static final String CLAIM_ACTION_USER = "sub";
    public static final String CLAIM_ACR = "acr";
    public static final String CLAIM_AUTH_TIME = "auth_time";
    public static final String ACTION_PASSWORD_CHANGE = "password_change";
    public static final String ACTION_MFA = "mfa";
    /** Assurance level stamped on a token minted after a fresh second-factor proof (step-up). */
    public static final String ACR_MFA = "mfa";

    private final KeyRing keys;
    private final IdentityProperties properties;

    public TokenMinter(KeyRing keys, IdentityProperties properties) {
        this.keys = keys;
        this.properties = properties;
    }

    public String staffToken(
            UUID tenantId,
            UUID userId,
            String username,
            List<String> permissions,
            List<String> units,
            String clientId) {
        Instant now = Instant.now();
        return sign(new JWTClaimsSet.Builder()
                .issuer(properties.getIssuer())
                .subject(userId.toString())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(properties.getAccessTokenTtlSeconds())))
                .claim(CLAIM_TENANT, tenantId.toString())
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_PERMISSIONS, permissions)
                .claim(CLAIM_UNITS, units)
                .claim(CLAIM_AZP, clientId)
                .build());
    }

    /**
     * A service acting inside one tenant (ADR 0019): the staff shape without the human parts.
     *
     * <p>Carries {@code tenant_id} and {@code permissions} so an ordinary tenant-scoped endpoint
     * can authorize it the way it authorizes everyone else — no second code path at the reader,
     * which is the point. No {@code preferred_username} and no {@code units}: there is no person
     * and no branch, and inventing either would put a fiction in an audit line. {@code libs/auth}
     * falls back to {@code sub} for the principal, so this reads as the client that acted.
     *
     * <p>The tenant is a parameter here and never a header on the call the token is then used for.
     * A service asserting its own tenant per request is the caller-asserted scope this platform
     * refuses everywhere, and the failure it invites — a consumer reading the wrong institution's
     * customers — is silent.
     */
    public String tenantServiceToken(UUID tenantId, String clientId, List<String> permissions) {
        Instant now = Instant.now();
        return sign(new JWTClaimsSet.Builder()
                .issuer(properties.getIssuer())
                .subject(clientId)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(properties.getAccessTokenTtlSeconds())))
                .claim(CLAIM_TENANT, tenantId.toString())
                .claim(CLAIM_PERMISSIONS, permissions)
                .claim(CLAIM_AZP, clientId)
                .build());
    }

    /** A trusted service caller: {@code azp}, and no tenant claim of any kind. */
    public String serviceToken(String clientId) {
        Instant now = Instant.now();
        return sign(new JWTClaimsSet.Builder()
                .issuer(properties.getIssuer())
                .subject(clientId)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(properties.getAccessTokenTtlSeconds())))
                .claim(CLAIM_AZP, clientId)
                .build());
    }

    /** Single-purpose grant for a named action. Inert everywhere but this service. */
    public String actionToken(UUID tenantId, UUID userId, String action) {
        Instant now = Instant.now();
        return sign(new JWTClaimsSet.Builder()
                .issuer(properties.getIssuer())
                .subject(userId.toString())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(properties.getActionTokenTtlSeconds())))
                .claim(CLAIM_ACTION, action)
                .claim(CLAIM_ACTION_TENANT, tenantId.toString())
                .build());
    }

    /** A short-lived elevated token carrying acr=mfa and a fresh auth_time, for step-up. */
    public String elevatedToken(
            UUID tenantId,
            UUID userId,
            String username,
            List<String> permissions,
            List<String> units,
            String clientId) {
        Instant now = Instant.now();
        return sign(new JWTClaimsSet.Builder()
                .issuer(properties.getIssuer())
                .subject(userId.toString())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(Math.min(properties.getAccessTokenTtlSeconds(), 300))))
                .claim(CLAIM_TENANT, tenantId.toString())
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_PERMISSIONS, permissions)
                .claim(CLAIM_UNITS, units)
                .claim(CLAIM_AZP, clientId)
                .claim(CLAIM_ACR, ACR_MFA)
                .claim(CLAIM_AUTH_TIME, now.getEpochSecond())
                .build());
    }

    private String sign(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(keys.active().getKeyID())
                            .build(),
                    claims);
            jwt.sign(new RSASSASigner(keys.active()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("token signing failed", e);
        }
    }
}
