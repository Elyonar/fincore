package org.elyonar.fincore.identity.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.elyonar.fincore.identity.PermissionCatalog;
import org.elyonar.fincore.identity.auth.AuthEvents;
import org.elyonar.fincore.identity.auth.Passwords;
import org.elyonar.fincore.identity.internal.IdentityProperties;
import org.elyonar.fincore.identity.internal.Tenants;
import org.elyonar.fincore.identity.internal.Tx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The fourth reader of the ADR 0016 manifest. Seeds this service's tenant registry and each
 * tenant's one super-administrator, idempotently, additively, on every boot — the same
 * convergence contract as the other three registries, with the realm half of the old bootstrap
 * gone (ADR 0018).
 *
 * <p>The rules are the convention's, enforced here: the whole manifest validates before anything
 * is written; a malformed entry refuses startup naming the entry and the field; a manifest
 * carrying anything that looks like a credential is refused outright; removal of an entry
 * deprovisions nothing. The generated temporary credential is surfaced exactly once, into a
 * mode-600 file named by configuration, never logged and never committed.
 */
@Component
@Order(10)
public class ManifestSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ManifestSeeder.class);
    private static final Set<String> SEGMENTS = Set.of("MFB", "COOPERATIVE", "PSB", "FINTECH", "OTHER");
    private static final Set<String> REQUIRED = Set.of(
            "id", "realm", "legalName", "displayName", "countryCode", "segment",
            "businessTimezone", "webOrigin", "superAdmin");
    private static final Set<String> ADMIN_REQUIRED = Set.of("username", "email", "firstName", "lastName");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Tx tx;
    private final Tenants tenants;
    private final Passwords passwords;
    private final AuthEvents audit;
    private final IdentityProperties properties;
    private final ObjectMapper json = new ObjectMapper();

    public ManifestSeeder(
            Tx tx, Tenants tenants, Passwords passwords, AuthEvents audit, IdentityProperties properties) {
        this.tx = tx;
        this.tenants = tenants;
        this.passwords = passwords;
        this.audit = audit;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        String path = properties.getBootstrap().getManifest();
        if (path == null || path.isBlank()) {
            log.warn("  │  Bootstrap  NO MANIFEST configured — this instance can authenticate nobody"
                    + " until one is provided (fincore.identity.bootstrap.manifest)");
            return;
        }
        JsonNode root = json.readTree(Files.readString(Path.of(path)));
        List<JsonNode> entries = validate(root); // the whole manifest, before anything is written
        for (JsonNode entry : entries) {
            seed(entry);
        }
        log.info("  │  Bootstrap  manifest {} converged — {} tenant(s)", path, entries.size());
    }

    private List<JsonNode> validate(JsonNode root) {
        if (!root.has("tenants") || !root.get("tenants").isArray()) {
            throw new IllegalStateException("manifest: 'tenants' array is missing");
        }
        List<JsonNode> entries = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> realms = new HashSet<>();
        int index = 0;
        for (JsonNode entry : root.get("tenants")) {
            String label = "manifest entry " + index;
            for (String field : REQUIRED) {
                if (!entry.hasNonNull(field) || entry.get(field).asText().isBlank() && !field.equals("superAdmin")) {
                    throw new IllegalStateException(label + ": required field '" + field + "' is missing");
                }
            }
            try {
                UUID.fromString(entry.get("id").asText());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(label + ": 'id' is not a UUID");
            }
            if (!SEGMENTS.contains(entry.get("segment").asText())) {
                throw new IllegalStateException(label + ": unknown segment '" + entry.get("segment").asText() + "'");
            }
            if (!ids.add(entry.get("id").asText())) {
                throw new IllegalStateException(label + ": duplicate id");
            }
            if (!realms.add(entry.get("realm").asText())) {
                throw new IllegalStateException(label + ": duplicate realm");
            }
            JsonNode admin = entry.get("superAdmin");
            for (String field : ADMIN_REQUIRED) {
                if (!admin.hasNonNull(field) || admin.get(field).asText().isBlank()) {
                    throw new IllegalStateException(label + ": superAdmin field '" + field + "' is missing");
                }
            }
            // Secrets are never in the manifest, and a manifest carrying one is refused whole —
            // the credential was committed the moment it was written there.
            admin.fieldNames().forEachRemaining(name -> {
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.contains("password") || lower.contains("secret") || lower.contains("credential")) {
                    throw new IllegalStateException(
                            label + ": superAdmin carries '" + name + "' — secrets never ride in the manifest");
                }
            });
            entries.add(entry);
            index++;
        }
        return entries;
    }

    private void seed(JsonNode entry) throws IOException {
        UUID tenantId = UUID.fromString(entry.get("id").asText());
        tenants.register(tenantId, entry.get("legalName").asText(), "bootstrap:manifest");

        JsonNode admin = entry.get("superAdmin");
        String username = admin.get("username").asText();
        UUID userId = UUID.randomUUID();

        // Generated before the transaction so the row and the surfaced credential are the same
        // secret. It exists only past a successful insert: hashed in the row, plaintext in the
        // mode-600 file, and nowhere else — not in a log line, never in the manifest.
        byte[] raw = new byte[18];
        RANDOM.nextBytes(raw);
        String temporary = "tmp_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        // User, role grant and credential in ONE transaction: a half-seeded administrator — a user
        // row with no credential — is invisible to login (which JOINs the two) and, because the
        // user insert then converges, never repaired on a re-run. All three commit together or none.
        Boolean inserted = tx.inTenant(tenantId, () -> {
            for (String permission : PermissionCatalog.ADMIN_TEMPLATE) {
                tx.jdbc()
                        .update(
                                "INSERT INTO identity.role_permissions (tenant_id, role_name, permission)"
                                        + " VALUES (?,?,?) ON CONFLICT DO NOTHING",
                                tenantId,
                                PermissionCatalog.ADMIN_ROLE,
                                permission);
            }
            int rows = tx.jdbc()
                    .update(
                            "INSERT INTO identity.users (tenant_id, id, username, email, first_name,"
                                    + " last_name, created_by, created_via)"
                                    + " VALUES (?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                            tenantId,
                            userId,
                            username,
                            admin.get("email").asText(),
                            admin.get("firstName").asText(),
                            admin.get("lastName").asText(),
                            "bootstrap:manifest",
                            "service:identity");
            if (rows == 0) {
                return false; // converged already — additive means a re-run touches nothing
            }
            tx.jdbc()
                    .update(
                            "INSERT INTO identity.user_roles (tenant_id, user_id, role_name) VALUES (?,?,?)"
                                    + " ON CONFLICT DO NOTHING",
                            tenantId,
                            userId,
                            PermissionCatalog.ADMIN_ROLE);
            tx.jdbc()
                    .update(
                            "INSERT INTO identity.credentials (tenant_id, user_id, password_hash)"
                                    + " VALUES (?,?,?)",
                            tenantId,
                            userId,
                            passwords.hash(temporary));
            audit.record(tenantId, userId, "USER_CREATED", "bootstrap", Map.of("username", username));
            return true;
        });

        if (Boolean.TRUE.equals(inserted)) {
            surface(entry.get("realm").asText(), username, temporary);
            log.warn("  │  Bootstrap  seeded super-administrator '{}' for {} — temporary credential"
                            + " written to {}, forced change on first use",
                    username, entry.get("displayName").asText(), properties.getBootstrap().getCredentialsOut());
        }
    }

    private void surface(String realm, String username, String temporary) throws IOException {
        Path out = Path.of(properties.getBootstrap().getCredentialsOut());
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        if (!Files.exists(out)) {
            try {
                Files.createFile(out, PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException e) {
                Files.createFile(out); // non-POSIX filesystem: created, if not tightened
            }
        }
        Files.writeString(
                out,
                realm + " " + username + " " + temporary + System.lineSeparator(),
                StandardOpenOption.APPEND);
    }
}
