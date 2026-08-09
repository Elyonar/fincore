package org.elyonar.fincore.identity.auth;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.elyonar.fincore.identity.internal.IdentityProperties;
import org.elyonar.fincore.identity.internal.Tx;
import org.springframework.stereotype.Component;

/**
 * Refresh sessions (design.md D4): a family per login, opaque rotating tokens within it. The
 * presented value is digested and looked up; a match on a rotated row is the theft signal that
 * revokes the family and audits — detection, not just expiry.
 *
 * <p>All methods run inside the caller's tenant transaction.
 */
@Component
public class Sessions {

    /** The rotation outcome: a fresh token, or the user the caller must not learn more about. */
    public record Rotation(UUID userId, String newRefreshToken) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Tx tx;
    private final AuthEvents audit;
    private final IdentityProperties properties;

    public Sessions(Tx tx, AuthEvents audit, IdentityProperties properties) {
        this.tx = tx;
        this.audit = audit;
        this.properties = properties;
    }

    /** Opens a family and returns its first token. Plaintext leaves this method exactly once. */
    public String open(UUID tenantId, UUID userId, String clientId) {
        UUID familyId = UUID.randomUUID();
        tx.jdbc()
                .update(
                        "INSERT INTO auth.refresh_families"
                                + " (tenant_id, id, user_id, client_id, absolute_expiry) VALUES (?,?,?,?,?)",
                        tenantId,
                        familyId,
                        userId,
                        clientId,
                        java.sql.Timestamp.from(Instant.now()
                                .plus(properties.getRefresh().getAbsoluteHours(), ChronoUnit.HOURS)));
        return issue(tenantId, familyId);
    }

    /**
     * Rotates. Empty means refused — expired, unknown, revoked, or reused; the caller answers
     * {@code TOKEN_INVALID} without knowing which, and the audit trail knows exactly.
     */
    public java.util.Optional<Rotation> rotate(UUID tenantId, String presented, String source) {
        String digest = digest(presented);
        var row = tx.jdbc()
                .query(
                        "SELECT t.family_id, t.rotated_at, f.user_id, f.absolute_expiry, f.revoked_at"
                                + " FROM auth.refresh_tokens t"
                                + " JOIN auth.refresh_families f"
                                + "   ON f.tenant_id = t.tenant_id AND f.id = t.family_id"
                                + " WHERE t.tenant_id = ? AND t.token_digest = ?"
                                + " FOR UPDATE OF t, f",
                        rs -> rs.next()
                                ? new Object[] {
                                    rs.getObject(1, UUID.class),
                                    rs.getTimestamp(2),
                                    rs.getObject(3, UUID.class),
                                    rs.getTimestamp(4),
                                    rs.getTimestamp(5)
                                }
                                : null,
                        tenantId,
                        digest);
        if (row == null) {
            return java.util.Optional.empty();
        }
        UUID familyId = (UUID) row[0];
        java.sql.Timestamp rotatedAt = (java.sql.Timestamp) row[1];
        UUID userId = (UUID) row[2];
        java.sql.Timestamp absoluteExpiry = (java.sql.Timestamp) row[3];
        java.sql.Timestamp revokedAt = (java.sql.Timestamp) row[4];

        if (rotatedAt != null) {
            // The theft signal: this exact value was already spent. Whoever presents it second —
            // thief or victim — the family is no longer trustworthy, and the wire must not say so.
            //
            // Written in the caller's transaction, which commits: LoginService.refresh raises the
            // refusal *after* the transaction returns, precisely so this revocation survives it.
            // It used to throw from inside, and the throw discarded the revocation — theft was
            // detected, decided correctly, and then forgotten, leaving the stolen family live.
            revokeFamily(tenantId, familyId, "ROTATION_REUSE");
            audit.record(tenantId, userId, AuthEvent.REUSE_REVOKED, source, Map.of("family", familyId.toString()));
            return java.util.Optional.empty();
        }
        if (revokedAt != null || absoluteExpiry.toInstant().isBefore(Instant.now())) {
            return java.util.Optional.empty();
        }

        tx.jdbc()
                .update(
                        "UPDATE auth.refresh_tokens SET rotated_at = now()"
                                + " WHERE tenant_id = ? AND token_digest = ?",
                        tenantId,
                        digest);
        String next = issue(tenantId, familyId);
        audit.record(tenantId, userId, AuthEvent.ROTATED, source, Map.of("family", familyId.toString()));
        return java.util.Optional.of(new Rotation(userId, next));
    }

    /** Revokes the presented token's family. Idempotent: revoking nothing is not an error. */
    public void logout(UUID tenantId, String presented, String source) {
        var row = tx.jdbc()
                .query(
                        "SELECT t.family_id, f.user_id FROM auth.refresh_tokens t"
                                + " JOIN auth.refresh_families f"
                                + "   ON f.tenant_id = t.tenant_id AND f.id = t.family_id"
                                + " WHERE t.tenant_id = ? AND t.token_digest = ?",
                        rs -> rs.next()
                                ? new Object[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)}
                                : null,
                        tenantId,
                        digest(presented));
        if (row != null) {
            revokeFamily(tenantId, (UUID) row[0], "LOGOUT");
            audit.record(
                    tenantId, (UUID) row[1], "LOGOUT", source, Map.of("family", row[0].toString()));
        }
    }

    public int revokeAllFor(UUID tenantId, UUID userId, String reason, String source) {
        int families = tx.jdbc()
                .update(
                        "UPDATE auth.refresh_families SET revoked_at = now(), revoked_reason = ?"
                                + " WHERE tenant_id = ? AND user_id = ? AND revoked_at IS NULL",
                        reason,
                        tenantId,
                        userId);
        audit.record(tenantId, userId, AuthEvent.SESSIONS_REVOKED, source, Map.of("families", families, "reason", reason));
        return families;
    }

    private void revokeFamily(UUID tenantId, UUID familyId, String reason) {
        tx.jdbc()
                .update(
                        "UPDATE auth.refresh_families SET revoked_at = now(), revoked_reason = ?"
                                + " WHERE tenant_id = ? AND id = ? AND revoked_at IS NULL",
                        reason,
                        tenantId,
                        familyId);
    }

    private String issue(UUID tenantId, UUID familyId) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = "rt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        tx.jdbc()
                .update(
                        "INSERT INTO auth.refresh_tokens (tenant_id, token_digest, family_id)"
                                + " VALUES (?,?,?)",
                        tenantId,
                        digest(token),
                        familyId);
        return token;
    }

    static String digest(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
