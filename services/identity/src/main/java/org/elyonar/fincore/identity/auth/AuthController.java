package org.elyonar.fincore.identity.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.elyonar.fincore.identity.api.IdentityErrors;
import org.elyonar.fincore.identity.api.IdentityErrors.TokenInvalid;
import org.elyonar.fincore.identity.internal.IdentityProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The authentication surface — the platform's only pre-identity endpoints, reached through the
 * edge. Thin by design: outcomes and refusals live in {@link LoginService}; this class only
 * shapes HTTP.
 */
@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    public record LoginRequest(String username, String password, String clientId) {}

    public record PasswordRequest(
            String actionToken, String username, String currentPassword, String newPassword) {}

    public record RefreshRequest(String refreshToken, String clientId) {}

    public record LogoutRequest(String refreshToken) {}

    public record ServiceTokenRequest(String clientId, String clientSecret) {}

    private static final String DEFAULT_CLIENT = "fincore-web";

    private final LoginService login;
    private final ServiceClients serviceClients;
    private final IdentityProperties properties;

    public AuthController(
            LoginService login, ServiceClients serviceClients, IdentityProperties properties) {
        this.login = login;
        this.serviceClients = serviceClients;
        this.properties = properties;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request, HttpServletRequest http) {
        LoginService.LoginOutcome outcome = login.login(
                request.username(), request.password(), source(http), client(request.clientId()));
        return switch (outcome) {
            case LoginService.Granted granted -> ResponseEntity.ok(granted.tokens());
            case LoginService.Obliged obliged -> ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(Map.of(
                            "code", IdentityErrors.Codes.ACTION_REQUIRED,
                            "reason", obliged.action().reason(),
                            "actionToken", obliged.action().actionToken()));
        };
    }

    @PostMapping("/password")
    public ResponseEntity<Void> password(@RequestBody PasswordRequest request, HttpServletRequest http) {
        login.changePassword(
                request.actionToken(),
                request.username(),
                request.currentPassword(),
                request.newPassword(),
                source(http));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginService.TokenPair> refresh(
            @RequestBody RefreshRequest request, HttpServletRequest http) {
        return ResponseEntity.ok(
                login.refresh(request.refreshToken(), source(http), client(request.clientId())));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody LogoutRequest request, HttpServletRequest http) {
        login.logout(request.refreshToken(), source(http));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sessions/revoke-all")
    public ResponseEntity<Void> revokeAll(HttpServletRequest http) {
        String header = http.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new TokenInvalid();
        }
        login.revokeAll(header.substring(7).trim(), source(http));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/token")
    public ResponseEntity<Map<String, Object>> serviceToken(@RequestBody ServiceTokenRequest request) {
        String token = serviceClients.token(request.clientId(), request.clientSecret());
        return ResponseEntity.ok(Map.of(
                "accessToken", token,
                "expiresIn", (long) properties.getAccessTokenTtlSeconds()));
    }

    /**
     * The audit trail's source attribution. First hop of {@code X-Forwarded-For} when the edge
     * supplies it, the peer address otherwise. Attribution, not authorization: nothing grants or
     * denies on this value, so a spoofed header pollutes a log line and nothing else.
     */
    private static String source(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return http.getRemoteAddr();
    }

    private static String client(String clientId) {
        return clientId == null || clientId.isBlank() ? DEFAULT_CLIENT : clientId;
    }
}
