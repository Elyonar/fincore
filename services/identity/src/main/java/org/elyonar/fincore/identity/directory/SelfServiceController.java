package org.elyonar.fincore.identity.directory;

import com.nimbusds.jwt.JWTClaimsSet;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.elyonar.fincore.identity.api.IdentityErrors;
import org.elyonar.fincore.identity.internal.Tx;
import org.elyonar.fincore.identity.token.LocalVerifier;
import org.elyonar.fincore.identity.token.TokenMinter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a member of staff may see and change about <em>themselves</em>.
 *
 * <p>Browser-facing and authenticated by the holder's own access token, the same posture the MFA
 * endpoints take — no permission is required, because the subject and the caller are the same
 * person and a teller who needed {@code users:read} to see their own phone number would be an
 * absurdity.
 *
 * <p>The split it enforces is the whole point. A person completes the facts only they know:
 * mobile number, date of birth, address, who to call in an emergency. They cannot touch the facts
 * about their <em>job</em> — staff number, job title, roles, branch, employment date — because a
 * person editing their own job title is not a control, it is the absence of one. Those live on the
 * administered half of the record and move only through the directory.
 */
@Tag(name = "Me", description = "The signed-in member of staff's own record")
@RestController
@RequestMapping("/v1/me")
public class SelfServiceController {

    private static final String BEARER = "Bearer ";

    private final Tx tx;
    private final LocalVerifier verifier;
    private final Directory directory;

    public SelfServiceController(Tx tx, LocalVerifier verifier, Directory directory) {
        this.tx = tx;
        this.verifier = verifier;
        this.directory = directory;
    }

    /** The signed-in person's own record, including whether the onboarding gate is still closed. */
    @GetMapping
    public Directory.User me(HttpServletRequest http) {
        Caller caller = caller(http);
        Directory.User found =
                tx.inTenant(caller.tenantId(), () -> directory.user(caller.tenantId(), caller.userId()));
        if (found == null) {
            throw new IdentityErrors.TokenInvalid();
        }
        return found;
    }

    /**
     * Completes or updates the self-declared half of the record.
     *
     * <p>Completing it is what opens the portal: until every required field is present the shell
     * holds the person at onboarding. Requiring it at first sign-in rather than "soon" is
     * deliberate — a contact number collected on the day somebody needs it is collected too late.
     */
    @PutMapping("/profile")
    public ResponseEntity<Directory.User> updateProfile(
            @RequestBody Directory.SelfProfile request, HttpServletRequest http) {
        Caller caller = caller(http);
        Directory.User updated = tx.inTenant(
                caller.tenantId(),
                () -> directory.updateOwnProfile(caller.tenantId(), caller.userId(), request, source(http)));
        return ResponseEntity.ok(updated);
    }

    /** The fields the portal must collect before it opens, so the client never guesses the rule. */
    @GetMapping("/profile/requirements")
    public Map<String, Object> requirements() {
        return Map.of(
                "required", List.of("phone"),
                "optional", List.of(),
                "administered", List.of("staffNumber", "jobTitle", "startedOn", "roles", "units"));
    }

    private record Caller(UUID tenantId, UUID userId) {}

    private Caller caller(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            throw new IdentityErrors.TokenInvalid();
        }
        JWTClaimsSet claims =
                verifier.verify(header.substring(BEARER.length())).orElseThrow(IdentityErrors.TokenInvalid::new);
        Object tenant = claims.getClaim(TokenMinter.CLAIM_TENANT);
        if (tenant == null || claims.getSubject() == null) {
            // A service or action token. Neither is a person, and neither has a profile.
            throw new IdentityErrors.TokenInvalid();
        }
        try {
            return new Caller(UUID.fromString(String.valueOf(tenant)), UUID.fromString(claims.getSubject()));
        } catch (IllegalArgumentException e) {
            throw new IdentityErrors.TokenInvalid();
        }
    }

    private static String source(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }

}
