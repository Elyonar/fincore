package org.elyonar.fincore.identity.directory;

import com.nimbusds.jwt.JWTClaimsSet;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.elyonar.fincore.identity.api.IdentityErrors;
import org.elyonar.fincore.identity.internal.Tenants;
import org.elyonar.fincore.identity.token.LocalVerifier;
import org.elyonar.fincore.identity.token.TokenMinter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Who may speak to the directory, and on whose behalf — the ledger's posture, applied to identity.
 *
 * <p>The directory's callers are <em>services</em>, never browsers. The edge routes only the
 * authentication surface, so a tenant cannot reach this at all; that is a deployment fact, and a
 * deployment fact is not an authorization control. So the rule is enforced here too, and it is the
 * one {@code LedgerAuth} already established:
 *
 * <ul>
 *   <li>The {@code Authorization} bearer must be a <b>service credential</b> — a token carrying no
 *       tenant claim, whose {@code azp} is on the trusted-caller list. A staff token presented
 *       directly is refused, so a stolen administrator token cannot drive the directory even if
 *       the edge were misconfigured tomorrow.
 *   <li>The initiating administrator arrives separately in
 *       {@code X-Forwarded-Authorization} — their own verified token, forwarded by Core. The
 *       tenant comes from <em>its</em> claim and the attribution from its principal: verified
 *       facts, never strings a caller could assert.
 * </ul>
 *
 * <p>Both are required. A service credential alone can read nothing here: every directory
 * operation is something a human did, and an audit row that cannot name them is worth little.
 */
@Component
public class DirectoryAuth {

    /** The user's own token, forwarded by the calling service. */
    static final String FORWARDED = "X-Forwarded-Authorization";

    private static final String BEARER = "Bearer ";

    private final LocalVerifier verifier;
    private final Tenants tenants;
    private final List<String> trustedCallers;

    public DirectoryAuth(
            LocalVerifier verifier,
            Tenants tenants,
            @Value("${fincore.identity.directory.trusted-callers:core}") List<String> trustedCallers) {
        this.verifier = verifier;
        this.tenants = tenants;
        this.trustedCallers = trustedCallers;
    }

    /** The tenant this call is scoped to, and the administrator it is attributed to. */
    public record Caller(UUID tenantId, Directory.Administrator administrator) {}

    public Caller identify(HttpServletRequest request) {
        JWTClaimsSet service = verified(request.getHeader("Authorization"));
        if (service.getClaim(TokenMinter.CLAIM_TENANT) != null) {
            // A staff token. The directory accepts service credentials only.
            throw new IdentityErrors.TokenInvalid();
        }
        Object azp = service.getClaim(TokenMinter.CLAIM_AZP);
        if (azp == null || !trustedCallers.contains(String.valueOf(azp))) {
            throw new IdentityErrors.TokenInvalid();
        }

        JWTClaimsSet forwarded = verified(request.getHeader(FORWARDED));
        Object tenantClaim = forwarded.getClaim(TokenMinter.CLAIM_TENANT);
        Object usernameClaim = forwarded.getClaim(TokenMinter.CLAIM_USERNAME);
        if (tenantClaim == null || usernameClaim == null || forwarded.getSubject() == null) {
            throw new IdentityErrors.TokenInvalid();
        }

        UUID tenantId;
        UUID adminId;
        try {
            tenantId = UUID.fromString(String.valueOf(tenantClaim));
            adminId = UUID.fromString(forwarded.getSubject());
        } catch (IllegalArgumentException e) {
            throw new IdentityErrors.TokenInvalid();
        }

        // One deployed instance serves one institution (ADR 0018). A token minted for another
        // instance cannot verify against these keys at all; this catches the remaining case of a
        // registry that has drifted from the instance's configured tenant.
        UUID instance = tenants.instanceTenant();
        if (instance != null && !instance.equals(tenantId)) {
            throw new IdentityErrors.TokenInvalid();
        }

        return new Caller(
                tenantId,
                new Directory.Administrator(
                        adminId, String.valueOf(usernameClaim), "service:" + azp));
    }

    private JWTClaimsSet verified(String header) {
        if (header == null || header.isBlank()) {
            throw new IdentityErrors.TokenInvalid();
        }
        String token = header.regionMatches(true, 0, BEARER, 0, BEARER.length())
                ? header.substring(BEARER.length())
                : header.trim();
        return verifier.verify(token).orElseThrow(IdentityErrors.TokenInvalid::new);
    }
}
