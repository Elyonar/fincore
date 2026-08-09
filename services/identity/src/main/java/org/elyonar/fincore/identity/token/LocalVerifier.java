package org.elyonar.fincore.identity.token;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Optional;
import org.elyonar.fincore.identity.internal.IdentityProperties;
import org.springframework.stereotype.Component;

/**
 * Verifies this service's own tokens, locally.
 *
 * <p>Every other service verifies against the published JWKS over HTTP; this one holds the keys
 * and has no reason to call itself. Same trust decisions — signature against a published key,
 * exact issuer, expiry — arrived at without a network hop that could only ever loop back.
 */
@Component
public class LocalVerifier {

    private final KeyRing keys;
    private final IdentityProperties properties;

    public LocalVerifier(KeyRing keys, IdentityProperties properties) {
        this.keys = keys;
        this.properties = properties;
    }

    /** The claims, if the token is genuinely this instance's and unexpired. Never says why not. */
    public Optional<JWTClaimsSet> verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            boolean signed = false;
            for (JWK jwk : keys.published().getKeys()) {
                if (jwt.getHeader().getKeyID() != null
                        && !jwt.getHeader().getKeyID().equals(jwk.getKeyID())) {
                    continue;
                }
                if (jwt.verify(new RSASSAVerifier(((RSAKey) jwk).toRSAPublicKey()))) {
                    signed = true;
                    break;
                }
            }
            if (!signed) {
                return Optional.empty();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (!properties.getIssuer().equals(claims.getIssuer())) {
                return Optional.empty();
            }
            if (claims.getExpirationTime() == null
                    || claims.getExpirationTime().toInstant().isBefore(Instant.now())) {
                return Optional.empty();
            }
            return Optional.of(claims);
        } catch (Exception e) {
            // The reason is not surfaced: a caller learning precisely why a token failed learns
            // how to make one that does not — the same rule libs/auth applies.
            return Optional.empty();
        }
    }
}
