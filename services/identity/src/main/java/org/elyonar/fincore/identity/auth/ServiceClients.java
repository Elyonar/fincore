package org.elyonar.fincore.identity.auth;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.sql.DataSource;
import org.elyonar.fincore.identity.api.IdentityErrors.AuthFailed;
import org.elyonar.fincore.identity.internal.IdentityProperties;
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

    public ServiceClients(
            Tx tx,
            @Qualifier("dataSource") DataSource owner,
            AuthEvents audit,
            TokenMinter minter,
            IdentityProperties properties) {
        this.tx = tx;
        this.owner = new JdbcTemplate(owner);
        this.audit = audit;
        this.minter = minter;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String declared = properties.getServiceClients();
        if (declared == null || declared.isBlank()) {
            return;
        }
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
            owner.update(
                    "INSERT INTO auth.service_clients (client_id, secret_digest) VALUES (?,?)"
                            + " ON CONFLICT (client_id) DO UPDATE SET secret_digest = EXCLUDED.secret_digest",
                    parts[0].trim(),
                    digest(secret));
        }
    }

    /** Mints, or refuses in the login surface's one voice — an unknown client is not news. */
    public String token(String clientId, String clientSecret) {
        if (clientId == null || clientId.isBlank() || clientSecret == null) {
            throw new AuthFailed();
        }
        String stored = tx.plain(() -> tx.jdbc()
                .query(
                        "SELECT secret_digest FROM auth.service_clients"
                                + " WHERE client_id = ? AND enabled",
                        rs -> rs.next() ? rs.getString(1) : null,
                        clientId));
        byte[] presented = digest(clientSecret).getBytes(StandardCharsets.UTF_8);
        byte[] expected = (stored == null ? digest("decoy:" + clientId) : stored).getBytes(StandardCharsets.UTF_8);
        if (stored == null || !MessageDigest.isEqual(expected, presented)) {
            audit.recordTenantless("SERVICE_TOKEN_REFUSED", null, Map.of("client", clientId));
            throw new AuthFailed();
        }
        audit.recordTenantless("SERVICE_TOKEN_ISSUED", null, Map.of("client", clientId));
        return minter.serviceToken(clientId);
    }

    private static String resolve(String envName) {
        return System.getenv(envName) != null ? System.getenv(envName) : System.getProperty(envName);
    }

    private static String digest(String secret) {
        return Sessions.digest(secret);
    }
}
