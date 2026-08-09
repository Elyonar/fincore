package org.elyonar.fincore.identity.auth;

import com.nimbusds.jwt.JWTClaimsSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.elyonar.fincore.identity.api.IdentityErrors.AuthFailed;
import org.elyonar.fincore.identity.api.IdentityErrors.PasswordPolicy;
import org.elyonar.fincore.identity.api.IdentityErrors.RateLimited;
import org.elyonar.fincore.identity.api.IdentityErrors.TokenInvalid;
import org.elyonar.fincore.identity.internal.IdentityProperties;
import org.elyonar.fincore.identity.internal.Tenants;
import org.elyonar.fincore.identity.internal.Tx;
import org.elyonar.fincore.identity.token.LocalVerifier;
import org.elyonar.fincore.identity.token.TokenMinter;
import org.springframework.stereotype.Service;

/**
 * The credential flows. One rule shapes every branch: exactly one hash verification per login
 * attempt (real or decoy), and one refusal in one voice at the end of every failing path. The
 * audit trail records which branch it actually was; the wire never does (design.md D5).
 */
@Service
public class LoginService {

    /** A successful login or refresh: the pair the client holds. */
    public record TokenPair(String accessToken, String refreshToken, long expiresIn) {}

    /** A login answered with an obligation instead of tokens. */
    public record ActionRequired(String reason, String actionToken) {}

    public sealed interface LoginOutcome permits Granted, Obliged {}

    public record Granted(TokenPair tokens) implements LoginOutcome {}

    public record Obliged(ActionRequired action) implements LoginOutcome {}

    private record UserRow(UUID id, String username, String status, boolean temporary, String hash) {}

    private final Tx tx;
    private final Tenants tenants;
    private final Passwords passwords;
    private final Throttle throttle;
    private final Sessions sessions;
    private final AuthEvents audit;
    private final TokenMinter minter;
    private final LocalVerifier verifier;
    private final StaffTokens staffTokens;
    private final Mfa mfa;
    private final IdentityProperties properties;

    public LoginService(
            Tx tx,
            Tenants tenants,
            Passwords passwords,
            Throttle throttle,
            Sessions sessions,
            AuthEvents audit,
            TokenMinter minter,
            LocalVerifier verifier,
            StaffTokens staffTokens,
            Mfa mfa,
            IdentityProperties properties) {
        this.tx = tx;
        this.tenants = tenants;
        this.passwords = passwords;
        this.throttle = throttle;
        this.sessions = sessions;
        this.audit = audit;
        this.minter = minter;
        this.verifier = verifier;
        this.staffTokens = staffTokens;
        this.mfa = mfa;
        this.properties = properties;
    }

    public LoginOutcome login(String username, String password, String source, String clientId) {
        UUID tenantId = tenants.instanceTenant();
        if (tenantId == null || username == null || username.isBlank() || password == null) {
            passwords.verifyDecoy(password == null ? "" : password);
            throw new AuthFailed();
        }
        return tx.inTenant(tenantId, () -> {
            if (throttle.sourceFlooded(tenantId, source)) {
                throw new RateLimited(60);
            }
            UserRow user = find(tenantId, username);
            if (user == null) {
                passwords.verifyDecoy(password);
                throttle.recordFailure(tenantId, username, source);
                audit.recordRefusal(tenantId, null, "LOGIN_FAILED", source, Map.of("cause", "UNKNOWN_USER"));
                throw new AuthFailed();
            }
            boolean verified = passwords.verify(password, user.hash());
            if (!verified) {
                throttle.recordFailure(tenantId, username, source);
                audit.recordRefusal(tenantId, user.id(), "LOGIN_FAILED", source, Map.of("cause", "BAD_CREDENTIAL"));
                throw new AuthFailed();
            }
            if (throttle.accountLocked(tenantId, username)) {
                // The credential was right and the answer is still no — and still the same no. A
                // correct guess during a lock must not read differently from a wrong one.
                audit.recordRefusal(tenantId, user.id(), "LOGIN_FAILED", source, Map.of("cause", "LOCKED"));
                throw new AuthFailed();
            }
            if (!"ACTIVE".equals(user.status())) {
                audit.recordRefusal(tenantId, user.id(), "LOGIN_FAILED", source, Map.of("cause", "DISABLED"));
                throw new AuthFailed();
            }
            throttle.clear(tenantId, username);
            if (user.temporary()) {
                audit.record(tenantId, user.id(), "ACTION_REQUIRED", source, Map.of("action", "PASSWORD_CHANGE"));
                return new Obliged(new ActionRequired(
                        "PASSWORD_CHANGE_REQUIRED",
                        minter.actionToken(tenantId, user.id(), TokenMinter.ACTION_PASSWORD_CHANGE)));
            }
            if (mfa.isActive(tenantId, user.id())) {
                // The password was right; a second factor still stands between it and tokens. The
                // MFA action token is the only thing /mfa/verify accepts, and it is inert elsewhere.
                audit.record(tenantId, user.id(), "ACTION_REQUIRED", source, Map.of("action", "MFA"));
                return new Obliged(new ActionRequired(
                        "MFA_REQUIRED", minter.actionToken(tenantId, user.id(), TokenMinter.ACTION_MFA)));
            }
            audit.record(tenantId, user.id(), "LOGIN_OK", source, Map.of("client", clientId));
            return new Granted(staffTokens.grant(tenantId, user.id(), user.username(), clientId, true));
        });
    }

    /**
     * Completes a login that stopped at {@code MFA_REQUIRED}: verifies the action token and a
     * current second factor, then grants. A wrong factor refuses in the one voice and counts toward
     * lockout, exactly like a wrong password.
     */
    public TokenPair completeMfa(String actionToken, String code, String recoveryCode, String source, String clientId) {
        JWTClaimsSet claims = verifier.verify(actionToken).orElseThrow(AuthFailed::new);
        String act;
        String tid;
        try {
            act = claims.getStringClaim(TokenMinter.CLAIM_ACTION);
            tid = claims.getStringClaim(TokenMinter.CLAIM_ACTION_TENANT);
        } catch (java.text.ParseException e) {
            throw new AuthFailed();
        }
        if (!TokenMinter.ACTION_MFA.equals(act) || tid == null) {
            throw new AuthFailed();
        }
        UUID tenantId = UUID.fromString(tid);
        UUID userId = UUID.fromString(claims.getSubject());
        return tx.inTenant(tenantId, () -> {
            String username = usernameOf(tenantId, userId);
            if (username == null) {
                throw new AuthFailed();
            }
            boolean ok = mfa.verifyFactor(tenantId, userId, code, recoveryCode);
            if (!ok) {
                throttle.recordFailure(tenantId, username, source);
                audit.recordRefusal(tenantId, userId, "MFA_FAILED", source, Map.of());
                throw new AuthFailed();
            }
            throttle.clear(tenantId, username);
            audit.record(tenantId, userId, "LOGIN_OK", source, Map.of("client", clientId, "mfa", true));
            return staffTokens.grant(tenantId, userId, username, clientId, true);
        });
    }

    /**
     * Sets a new password — with the single-purpose action grant from a temporary-credential
     * login, or with the current credential. Success revokes every session the user holds: a
     * password change is the user's own theft response, and stolen refresh tokens die with it.
     */
    public void changePassword(
            String actionToken, String username, String currentPassword, String newPassword, String source) {
        if (actionToken != null && !actionToken.isBlank()) {
            JWTClaimsSet claims = verifier.verify(actionToken).orElseThrow(TokenInvalid::new);
            String act;
            String tid;
            try {
                act = claims.getStringClaim(TokenMinter.CLAIM_ACTION);
                tid = claims.getStringClaim(TokenMinter.CLAIM_ACTION_TENANT);
            } catch (java.text.ParseException e) {
                throw new TokenInvalid();
            }
            if (!TokenMinter.ACTION_PASSWORD_CHANGE.equals(act) || tid == null) {
                throw new TokenInvalid();
            }
            UUID tenantId = UUID.fromString(tid);
            UUID userId = UUID.fromString(claims.getSubject());
            tx.inTenant(tenantId, () -> {
                apply(tenantId, userId, newPassword, source);
                return null;
            });
            return;
        }

        // Current-credential mode is a login in every respect that matters to an attacker, so it
        // pays the same costs: throttle, decoy, one voice.
        UUID tenantId = tenants.instanceTenant();
        if (tenantId == null || username == null || username.isBlank() || currentPassword == null) {
            passwords.verifyDecoy(currentPassword == null ? "" : currentPassword);
            throw new AuthFailed();
        }
        tx.inTenant(tenantId, () -> {
            if (throttle.sourceFlooded(tenantId, source)) {
                throw new RateLimited(60);
            }
            UserRow user = find(tenantId, username);
            if (user == null) {
                passwords.verifyDecoy(currentPassword);
                throttle.recordFailure(tenantId, username, source);
                throw new AuthFailed();
            }
            if (!passwords.verify(currentPassword, user.hash())
                    || throttle.accountLocked(tenantId, username)
                    || !"ACTIVE".equals(user.status())) {
                throttle.recordFailure(tenantId, username, source);
                audit.recordRefusal(tenantId, user.id(), "LOGIN_FAILED", source, Map.of("cause", "BAD_CREDENTIAL"));
                throw new AuthFailed();
            }
            throttle.clear(tenantId, username);
            apply(tenantId, user.id(), newPassword, source);
            return null;
        });
    }

    public TokenPair refresh(String refreshToken, String source, String clientId) {
        UUID tenantId = tenants.instanceTenant();
        if (tenantId == null || refreshToken == null || refreshToken.isBlank()) {
            throw new TokenInvalid();
        }
        // The transaction commits and *then* the refusal is raised, rather than the refusal
        // unwinding the transaction. Both refusing branches here write something that must outlive
        // the refusal — a revoked family on reuse, revoked sessions for a disabled user — and
        // throwing from inside took those writes with it: theft was detected, the family revoked,
        // and the revocation rolled straight back, so the stolen token kept working.
        //
        // Nesting the writes in their own transaction is the wrong repair. Rotation holds row locks
        // on exactly the rows the revocation updates, so an inner transaction blocks on the outer
        // one that is waiting for it — a self-deadlock that only shows up under a lock timeout.
        // Committing first and refusing after needs neither a second transaction nor a second
        // connection.
        TokenPair granted = tx.inTenant(tenantId, () -> {
            Sessions.Rotation rotation = sessions.rotate(tenantId, refreshToken, source).orElse(null);
            if (rotation == null) {
                return null;
            }
            UserRow user = findById(tenantId, rotation.userId());
            if (user == null || !"ACTIVE".equals(user.status()) || user.temporary()) {
                // A disabled user's outstanding sessions die at the next rotation, quietly.
                sessions.revokeAllFor(tenantId, rotation.userId(), "ADMIN", source);
                return null;
            }
            TokenPair minted = staffTokens.grant(tenantId, user.id(), user.username(), clientId, false);
            return new TokenPair(minted.accessToken(), rotation.newRefreshToken(), minted.expiresIn());
        });
        if (granted == null) {
            throw new TokenInvalid();
        }
        return granted;
    }

    public void logout(String refreshToken, String source) {
        UUID tenantId = tenants.instanceTenant();
        if (tenantId == null || refreshToken == null || refreshToken.isBlank()) {
            return; // revoking nothing is not an error, and not an oracle either
        }
        tx.inTenant(tenantId, () -> {
            sessions.logout(tenantId, refreshToken, source);
            return null;
        });
    }

    /** Revokes every family for the bearer of a valid access token. */
    public void revokeAll(String bearerToken, String source) {
        JWTClaimsSet claims = verifier.verify(bearerToken).orElseThrow(TokenInvalid::new);
        String tid;
        try {
            tid = claims.getStringClaim(TokenMinter.CLAIM_TENANT);
        } catch (java.text.ParseException e) {
            throw new TokenInvalid();
        }
        if (tid == null) {
            throw new TokenInvalid(); // a service or action token holds no sessions
        }
        UUID tenantId = UUID.fromString(tid);
        UUID userId = UUID.fromString(claims.getSubject());
        tx.inTenant(tenantId, () -> {
            sessions.revokeAllFor(tenantId, userId, "LOGOUT", source);
            return null;
        });
    }

    // ---- internals -------------------------------------------------------------------------

    /** Inside an open tenant transaction. */
    private void apply(UUID tenantId, UUID userId, String newPassword, String source) {
        var current = tx.jdbc()
                .query(
                        "SELECT password_hash, history FROM identity.credentials"
                                + " WHERE tenant_id = ? AND user_id = ? FOR UPDATE",
                        rs -> rs.next()
                                ? Map.entry(
                                        rs.getString(1),
                                        (String[]) rs.getArray(2).getArray())
                                : null,
                        tenantId,
                        userId);
        if (current == null) {
            throw new TokenInvalid();
        }
        java.util.ArrayList<String> history = new java.util.ArrayList<>(List.of(current.getValue()));
        history.add(current.getKey());
        List<String> violations = passwords.policyViolations(newPassword, history);
        if (!violations.isEmpty()) {
            throw new PasswordPolicy(violations);
        }
        while (history.size() > 5) {
            history.remove(0);
        }
        tx.jdbc()
                .update(
                        "UPDATE identity.credentials SET password_hash = ?, history = ?, updated_at = now()"
                                + " WHERE tenant_id = ? AND user_id = ?",
                        passwords.hash(newPassword),
                        history.toArray(new String[0]),
                        tenantId,
                        userId);
        tx.jdbc()
                .update(
                        "UPDATE identity.users SET credential_temporary = FALSE"
                                + " WHERE tenant_id = ? AND id = ?",
                        tenantId,
                        userId);
        sessions.revokeAllFor(tenantId, userId, "PASSWORD_CHANGE", source);
        audit.record(tenantId, userId, "PASSWORD_CHANGED", source, Map.of());
    }

    /** The username of a user in the current tenant, or null. Used to complete an MFA login. */
    private String usernameOf(UUID tenantId, UUID userId) {
        return tx.jdbc()
                .query(
                        "SELECT username FROM identity.users WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'",
                        rs -> rs.next() ? rs.getString(1) : null,
                        tenantId,
                        userId);
    }

    private UserRow find(UUID tenantId, String username) {
        return tx.jdbc()
                .query(
                        "SELECT u.id, u.username, u.status, u.credential_temporary, c.password_hash"
                                + " FROM identity.users u"
                                + " JOIN identity.credentials c"
                                + "   ON c.tenant_id = u.tenant_id AND c.user_id = u.id"
                                + " WHERE u.tenant_id = ? AND lower(u.username) = lower(?)",
                        rs -> rs.next()
                                ? new UserRow(
                                        rs.getObject(1, UUID.class),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getBoolean(4),
                                        rs.getString(5))
                                : null,
                        tenantId,
                        username);
    }

    private UserRow findById(UUID tenantId, UUID userId) {
        return tx.jdbc()
                .query(
                        "SELECT u.id, u.username, u.status, u.credential_temporary, c.password_hash"
                                + " FROM identity.users u"
                                + " JOIN identity.credentials c"
                                + "   ON c.tenant_id = u.tenant_id AND c.user_id = u.id"
                                + " WHERE u.tenant_id = ? AND u.id = ?",
                        rs -> rs.next()
                                ? new UserRow(
                                        rs.getObject(1, UUID.class),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getBoolean(4),
                                        rs.getString(5))
                                : null,
                        tenantId,
                        userId);
    }
}
