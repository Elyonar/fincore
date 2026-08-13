package org.elyonar.fincore.identity.auth;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.elyonar.fincore.identity.api.IdentityErrors.AuthFailed;
import org.elyonar.fincore.identity.internal.IdentityProperties;
import org.elyonar.fincore.identity.internal.Tenants;
import org.elyonar.fincore.identity.internal.Tx;
import org.elyonar.fincore.identity.token.TokenMinter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The client-credentials flow (design.md D8): a service presents its id and secret and receives
 * the short-lived service token the ledger's caller rules already expect — {@code azp}, no tenant
 * claim. This is what closes the standing residual where Core's token was static configuration.
 *
 * <p>Clients are declared as {@code clientId=ENV_NAME} pairs, the same secrets-by-reference
 * posture as the manifest (tenant-bootstrap.md hard rule): the configuration names where the
 * secret lives, never the secret. Digests are seeded at startup; plaintext is never stored.
 */
@Component
@Order(20)
public class ServiceClients implements ApplicationRunner {

    private final Tx tx;
    private final JdbcTemplate owner;
    private final AuthEvents audit;
    private final TokenMinter minter;
    private final IdentityProperties properties;
    private final Tenants tenants;

    public ServiceClients(
            Tx tx,
            @Qualifier("dataSource") DataSource owner,
            AuthEvents audit,
            TokenMinter minter,
            IdentityProperties properties,
            Tenants tenants) {
        this.tx = tx;
        this.owner = new JdbcTemplate(owner);
        this.audit = audit;
        this.minter = minter;
        this.properties = properties;
        this.tenants = tenants;
    }

    @Override
    public void run(ApplicationArguments args) {
        String declared = properties.getServiceClients();
        if (declared == null || declared.isBlank()) {
            return;
        }
        Map<String, String[]> grants = grants();
        for (String pair : declared.split(",")) {
            String[] parts = pair.trim().split("=", 2);
            if (parts.length != 2 || parts[0].isBlank()) {
                throw new IllegalStateException(
                        "malformed fincore.identity.service-clients entry: expected clientId=ENV_NAME");
            }
            String secret = resolve(parts[1].trim());
            if (secret == null || secret.isBlank()) {
                throw new IllegalStateException(
                        "service client '" + parts[0] + "' names a secret reference that resolves to"
                                + " nothing — refusing to register a client nobody can be");
            }
            String clientId = parts[0].trim();
            owner.update(
                    "INSERT INTO auth.service_clients (client_id, secret_digest, permissions)"
                            + " VALUES (?,?,?)"
                            + " ON CONFLICT (client_id) DO UPDATE SET secret_digest = EXCLUDED.secret_digest,"
                            + " permissions = EXCLUDED.permissions",
                    clientId,
                    digest(secret),
                    grants.getOrDefault(clientId, new String[0]));
            grants.remove(clientId);
        }
        if (!grants.isEmpty()) {
            // A grant naming a client that does not exist is a typo with security-shaped
            // consequences — the permission silently applies to nobody, and whoever wrote it
            // believes a service can do something it cannot. Refuse rather than shrug.
            throw new IllegalStateException(
                    "fincore.identity.service-client-grants names client(s) with no credential: " + grants.keySet());
        }
    }

    /** {@code clientId=perm|perm} pairs, parsed once at startup. */
    private Map<String, String[]> grants() {
        Map<String, String[]> parsed = new java.util.LinkedHashMap<>();
        String declared = properties.getServiceClientGrants();
        if (declared == null || declared.isBlank()) {
            return parsed;
        }
        for (String pair : declared.split(",")) {
            String[] parts = pair.trim().split("=", 2);
            if (parts.length != 2 || parts[0].isBlank()) {
                throw new IllegalStateException(
                        "malformed fincore.identity.service-client-grants entry:"
                                + " expected clientId=permission|permission");
            }
            String[] permissions = java.util.Arrays.stream(parts[1].split("\\|"))
                    .map(String::trim)
                    .filter(one -> !one.isBlank())
                    .toArray(String[]::new);
            parsed.put(parts[0].trim(), permissions);
        }
        return parsed;
    }

    /** Mints, or refuses in the login surface's one voice — an unknown client is not news. */
    public String token(String clientId, String clientSecret) {
        return token(clientId, clientSecret, null);
    }

    /**
     * Mints for a client, optionally scoped to one tenant (ADR 0019).
     *
     * <p>No tenant asked for: the tenantless token, unchanged, for the ledger's caller allowlist.
     * A tenant asked for: the tenant must be real and active and the client must have been
     * <em>declared</em> permissions, because a token that names a tenant and carries an empty
     * permission set is a credential that looks like authority and grants nothing — it would fail
     * at the reader with a refusal that reads like a bug in the caller.
     *
     * <p>All four refusals answer in the same voice. Which of them it was — wrong secret, unknown
     * tenant, undeclared client — is in the audit trail and never in the response, for the reason
     * the login surface has always had: a caller learning <em>which</em> half was wrong is a caller
     * being helped to guess the other.
     */
    public String token(String clientId, String clientSecret, UUID tenantId) {
        if (clientId == null || clientId.isBlank() || clientSecret == null) {
            throw new AuthFailed();
        }
        Registered stored = tx.plain(() -> tx.jdbc()
                .query(
                        "SELECT secret_digest, permissions FROM auth.service_clients"
                                + " WHERE client_id = ? AND enabled",
                        rs -> rs.next()
                                ? new Registered(rs.getString(1), (String[]) rs.getArray(2).getArray())
                                : null,
                        clientId));
        byte[] presented = digest(clientSecret).getBytes(StandardCharsets.UTF_8);
        byte[] expected = (stored == null ? digest("decoy:" + clientId) : stored.digest())
                .getBytes(StandardCharsets.UTF_8);
        if (stored == null || !MessageDigest.isEqual(expected, presented)) {
            audit.recordTenantless("SERVICE_TOKEN_REFUSED", null, Map.of("client", clientId));
            throw new AuthFailed();
        }

        if (tenantId == null) {
            audit.recordTenantless("SERVICE_TOKEN_ISSUED", null, Map.of("client", clientId));
            return minter.serviceToken(clientId);
        }

        if (!tenants.activeTenants().contains(tenantId)) {
            audit.recordTenantless(
                    "SERVICE_TOKEN_REFUSED", null, Map.of("client", clientId, "reason", "TENANT_UNKNOWN"));
            throw new AuthFailed();
        }
        List<String> permissions = List.of(stored.permissions());
        if (permissions.isEmpty()) {
            audit.recordTenantless(
                    "SERVICE_TOKEN_REFUSED", null, Map.of("client", clientId, "reason", "NO_GRANTS_DECLARED"));
            throw new AuthFailed();
        }
        audit.recordScoped(tenantId, "SERVICE_TOKEN_ISSUED", null, Map.of("client", clientId, "scoped", true));
        return minter.tenantServiceToken(tenantId, clientId, permissions);
    }

    /** A client's stored credential and what it was declared to be allowed to do. */
    private record Registered(String digest, String[] permissions) {}

    private static String resolve(String envName) {
        return System.getenv(envName) != null ? System.getenv(envName) : System.getProperty(envName);
    }

    private static String digest(String secret) {
        return Sessions.digest(secret);
    }
}
