package org.elyonar.fincore.identity.auth;

import java.util.List;
import java.util.UUID;
import org.elyonar.fincore.identity.internal.IdentityProperties;
import org.elyonar.fincore.identity.internal.Tx;
import org.elyonar.fincore.identity.token.TokenMinter;
import org.springframework.stereotype.Component;

/**
 * The one place a staff token is minted for a user, so login and MFA completion cannot drift in
 * what a granted token contains. Resolves the user's effective permissions and units at mint time
 * — which is exactly what makes admin-surface's revocation-window property true: a role change
 * takes effect on the next token this produces.
 *
 * <p>Every method runs inside an already-open tenant transaction.
 */
@Component
public class StaffTokens {

    private final Tx tx;
    private final TokenMinter minter;
    private final Sessions sessions;
    private final IdentityProperties properties;

    public StaffTokens(Tx tx, TokenMinter minter, Sessions sessions, IdentityProperties properties) {
        this.tx = tx;
        this.minter = minter;
        this.sessions = sessions;
        this.properties = properties;
    }

    public List<String> permissions(UUID tenantId, UUID userId) {
        return tx.jdbc()
                .query(
                        "SELECT DISTINCT rp.permission FROM identity.user_roles ur"
                                + " JOIN identity.role_permissions rp"
                                + "   ON rp.tenant_id = ur.tenant_id AND rp.role_name = ur.role_name"
                                + " WHERE ur.tenant_id = ? AND ur.user_id = ? ORDER BY rp.permission",
                        (rs, i) -> rs.getString(1),
                        tenantId,
                        userId);
    }

    public List<String> units(UUID tenantId, UUID userId) {
        return tx.jdbc()
                .query(
                        "SELECT unit_code FROM identity.user_units"
                                + " WHERE tenant_id = ? AND user_id = ? ORDER BY unit_code",
                        (rs, i) -> rs.getString(1),
                        tenantId,
                        userId);
    }

    /** A fresh access token and, when {@code newFamily}, a new refresh family. */
    public LoginService.TokenPair grant(
            UUID tenantId, UUID userId, String username, String clientId, boolean newFamily) {
        String access = minter.staffToken(
                tenantId, userId, username, permissions(tenantId, userId), units(tenantId, userId), clientId);
        String refresh = newFamily ? sessions.open(tenantId, userId, clientId) : null;
        return new LoginService.TokenPair(access, refresh, properties.getAccessTokenTtlSeconds());
    }

    /** A short-lived elevated token (acr=mfa), for step-up. No refresh family. */
    public LoginService.TokenPair elevated(UUID tenantId, UUID userId, String username, String clientId) {
        String access = minter.elevatedToken(
                tenantId, userId, username, permissions(tenantId, userId), units(tenantId, userId), clientId);
        return new LoginService.TokenPair(access, null, Math.min(properties.getAccessTokenTtlSeconds(), 300));
    }
}
