package org.elyonar.fincore.identity.auth;

import com.nimbusds.jwt.JWTClaimsSet;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.elyonar.fincore.identity.api.IdentityErrors.TokenInvalid;
import org.elyonar.fincore.identity.token.LocalVerifier;
import org.elyonar.fincore.identity.token.TokenMinter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The multi-factor surface. The self-service operations authenticate with the caller's own access
 * token; {@code /verify} is the exception — it completes a login and so takes the MFA action token,
 * not a bearer, and is delegated to {@link LoginService}.
 */
@RestController
@RequestMapping("/v1/auth/mfa")
public class MfaController {

    public record CodeRequest(String code) {}

    public record VerifyRequest(String actionToken, String code, String recoveryCode, String clientId) {}

    private static final String DEFAULT_CLIENT = "fincore-web";

    private final Mfa mfa;
    private final LoginService login;
    private final LocalVerifier verifier;

    public MfaController(Mfa mfa, LoginService login, LocalVerifier verifier) {
        this.mfa = mfa;
        this.login = login;
        this.verifier = verifier;
    }

    @GetMapping
    public ResponseEntity<Mfa.Status> status(HttpServletRequest http) {
        Caller c = caller(http);
        return ResponseEntity.ok(mfa.status(c.tenantId, c.userId));
    }

    @PostMapping("/totp/enroll")
    public ResponseEntity<Mfa.Enrolment> enroll(HttpServletRequest http) {
        Caller c = caller(http);
        return ResponseEntity.ok(mfa.enroll(c.tenantId, c.userId, c.username));
    }

    @PostMapping("/totp/activate")
    public ResponseEntity<Void> activate(@RequestBody CodeRequest body, HttpServletRequest http) {
        Caller c = caller(http);
        mfa.activate(c.tenantId, c.userId, body.code());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/verify")
    public ResponseEntity<LoginService.TokenPair> verify(
            @RequestBody VerifyRequest body, HttpServletRequest http) {
        LoginService.TokenPair pair = login.completeMfa(
                body.actionToken(),
                body.code(),
                body.recoveryCode(),
                source(http),
                body.clientId() == null || body.clientId().isBlank() ? DEFAULT_CLIENT : body.clientId());
        return ResponseEntity.ok(pair);
    }

    @PostMapping("/step-up")
    public ResponseEntity<LoginService.TokenPair> stepUp(
            @RequestBody CodeRequest body, HttpServletRequest http) {
        Caller c = caller(http);
        return ResponseEntity.ok(mfa.stepUp(c.tenantId, c.userId, c.username, DEFAULT_CLIENT, body.code()));
    }

    @PostMapping("/disable")
    public ResponseEntity<Void> disable(@RequestBody CodeRequest body, HttpServletRequest http) {
        Caller c = caller(http);
        mfa.disable(c.tenantId, c.userId, body.code());
        return ResponseEntity.noContent().build();
    }

    private record Caller(UUID tenantId, UUID userId, String username) {}

    /** Establishes who is calling from their own access token — a staff token, tenant-scoped. */
    private Caller caller(HttpServletRequest http) {
        String header = http.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new TokenInvalid();
        }
        JWTClaimsSet claims = verifier.verify(header.substring(7).trim()).orElseThrow(TokenInvalid::new);
        try {
            String tid = claims.getStringClaim(TokenMinter.CLAIM_TENANT);
            String username = claims.getStringClaim(TokenMinter.CLAIM_USERNAME);
            if (tid == null || claims.getSubject() == null) {
                throw new TokenInvalid();
            }
            return new Caller(UUID.fromString(tid), UUID.fromString(claims.getSubject()), username);
        } catch (java.text.ParseException e) {
            throw new TokenInvalid();
        }
    }

    private static String source(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return http.getRemoteAddr();
    }
}
