package org.elyonar.fincore.identity.auth;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.elyonar.fincore.identity.api.IdentityErrors.AuthFailed;
import org.elyonar.fincore.identity.internal.IdentityProperties;
import org.elyonar.fincore.identity.internal.SecretCipher;
import org.elyonar.fincore.identity.internal.Tx;
import org.elyonar.fincore.identity.token.Totp;
import org.springframework.stereotype.Component;

/**
 * TOTP two-factor authentication (ADR 0018 phase 2): enrolment, activation, the login-time factor
 * check, recovery codes, step-up and disable. The secret is encrypted at rest (SecretCipher) and
 * recovery codes are stored only as digests — the same "no reconstructable factor in the database"
 * rule refresh tokens follow.
 *
 * <p>{@link #isActive} and {@link #verifyFactor} participate in the caller's already-open tenant
 * transaction (login runs inside one). The self-service operations open their own.
 */
@Component
public class Mfa {

    /** What enrolment hands back once: the secret, its provisioning URI, and recovery codes. */
    public record Enrolment(String secret, String otpauthUri, List<String> recoveryCodes) {}

    /** MFA status for a user. */
    public record Status(boolean active, List<String> methods, int recoveryCodesRemaining) {}

    private static final int RECOVERY_CODES = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Tx tx;
    private final Totp totp;
    private final SecretCipher cipher;
    private final AuthEvents audit;
    private final StaffTokens staffTokens;
    private final IdentityProperties properties;

    public Mfa(
            Tx tx,
            Totp totp,
            SecretCipher cipher,
            AuthEvents audit,
            StaffTokens staffTokens,
            IdentityProperties properties) {
        this.tx = tx;
        this.totp = totp;
        this.cipher = cipher;
        this.audit = audit;
        this.staffTokens = staffTokens;
        this.properties = properties;
    }

    // ---- participate in the caller's transaction (login / completeMfa) ----------------------

    public boolean isActive(UUID tenantId, UUID userId) {
        Integer n = tx.jdbc()
                .query(
                        "SELECT 1 FROM identity.mfa_enrollments"
                                + " WHERE tenant_id = ? AND user_id = ? AND status = 'ACTIVE'",
                        rs -> rs.next() ? 1 : null,
                        tenantId,
                        userId);
        return n != null;
    }

    /** A current TOTP code, or a one-time recovery code. Consumes the recovery code on use. */
    public boolean verifyFactor(UUID tenantId, UUID userId, String code, String recoveryCode) {
        if (recoveryCode != null && !recoveryCode.isBlank()) {
            int used = tx.jdbc()
                    .update(
                            "UPDATE identity.mfa_recovery_codes SET used_at = now()"
                                    + " WHERE tenant_id = ? AND user_id = ? AND code_digest = ? AND used_at IS NULL",
                            tenantId,
                            userId,
                            Sessions.digest(recoveryCode.trim()));
            return used == 1;
        }
        String secret = activeSecret(tenantId, userId);
        return secret != null && totp.verify(secret, code);
    }

    // ---- self-service operations (open their own transaction) -------------------------------

    public Enrolment enroll(UUID tenantId, UUID userId, String username) {
        return tx.inTenant(tenantId, () -> {
            if (isActive(tenantId, userId)) {
                throw new AuthFailed(); // already active — re-enrolment goes through disable first
            }
            String secret = totp.newSecret();
            tx.jdbc()
                    .update(
                            "INSERT INTO identity.mfa_enrollments (tenant_id, user_id, method, secret_encrypted,"
                                    + " status) VALUES (?,?, 'TOTP', ?, 'PENDING')"
                                    + " ON CONFLICT (tenant_id, user_id, method)"
                                    + " DO UPDATE SET secret_encrypted = EXCLUDED.secret_encrypted, status = 'PENDING'",
                            tenantId,
                            userId,
                            cipher.encrypt(secret));

            // Fresh recovery codes replace any prior set.
            tx.jdbc().update("DELETE FROM identity.mfa_recovery_codes WHERE tenant_id = ? AND user_id = ?", tenantId, userId);
            List<String> codes = new ArrayList<>();
            for (int i = 0; i < RECOVERY_CODES; i++) {
                byte[] raw = new byte[8];
                RANDOM.nextBytes(raw);
                String code = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
                codes.add(code);
                tx.jdbc()
                        .update(
                                "INSERT INTO identity.mfa_recovery_codes (tenant_id, user_id, code_digest)"
                                        + " VALUES (?,?,?)",
                                tenantId,
                                userId,
                                Sessions.digest(code));
            }
            audit.record(tenantId, userId, "MFA_ENROLL_STARTED", null, Map.of("method", "TOTP"));
            return new Enrolment(secret, totp.provisioningUri("FinCore", username, secret), codes);
        });
    }

    public void activate(UUID tenantId, UUID userId, String code) {
        tx.inTenant(tenantId, () -> {
            String secret = pendingSecret(tenantId, userId);
            if (secret == null || !totp.verify(secret, code)) {
                throw new AuthFailed();
            }
            tx.jdbc()
                    .update(
                            "UPDATE identity.mfa_enrollments SET status = 'ACTIVE', activated_at = now()"
                                    + " WHERE tenant_id = ? AND user_id = ? AND method = 'TOTP'",
                            tenantId,
                            userId);
            audit.record(tenantId, userId, "MFA_ACTIVATED", null, Map.of("method", "TOTP"));
            return null;
        });
    }

    public LoginService.TokenPair stepUp(UUID tenantId, UUID userId, String username, String clientId, String code) {
        return tx.inTenant(tenantId, () -> {
            if (!verifyFactor(tenantId, userId, code, null)) {
                audit.record(tenantId, userId, "STEP_UP_FAILED", null, Map.of());
                throw new AuthFailed();
            }
            audit.record(tenantId, userId, "STEP_UP_OK", null, Map.of());
            return staffTokens.elevated(tenantId, userId, username, clientId);
        });
    }

    public void disable(UUID tenantId, UUID userId, String code) {
        tx.inTenant(tenantId, () -> {
            if (!verifyFactor(tenantId, userId, code, null)) {
                throw new AuthFailed();
            }
            tx.jdbc().update("DELETE FROM identity.mfa_enrollments WHERE tenant_id = ? AND user_id = ?", tenantId, userId);
            tx.jdbc().update("DELETE FROM identity.mfa_recovery_codes WHERE tenant_id = ? AND user_id = ?", tenantId, userId);
            audit.record(tenantId, userId, "MFA_DISABLED", null, Map.of());
            return null;
        });
    }

    public Status status(UUID tenantId, UUID userId) {
        return tx.inTenant(tenantId, () -> {
            boolean active = isActive(tenantId, userId);
            Integer remaining = tx.jdbc()
                    .queryForObject(
                            "SELECT count(*)::int FROM identity.mfa_recovery_codes"
                                    + " WHERE tenant_id = ? AND user_id = ? AND used_at IS NULL",
                            Integer.class,
                            tenantId,
                            userId);
            return new Status(active, active ? List.of("TOTP") : List.of(), remaining == null ? 0 : remaining);
        });
    }

    private String activeSecret(UUID tenantId, UUID userId) {
        return secret(tenantId, userId, "ACTIVE");
    }

    private String pendingSecret(UUID tenantId, UUID userId) {
        return secret(tenantId, userId, "PENDING");
    }

    private String secret(UUID tenantId, UUID userId, String status) {
        String enc = tx.jdbc()
                .query(
                        "SELECT secret_encrypted FROM identity.mfa_enrollments"
                                + " WHERE tenant_id = ? AND user_id = ? AND method = 'TOTP' AND status = ?",
                        rs -> rs.next() ? rs.getString(1) : null,
                        tenantId,
                        userId,
                        status);
        return enc == null ? null : cipher.decrypt(enc);
    }
}
