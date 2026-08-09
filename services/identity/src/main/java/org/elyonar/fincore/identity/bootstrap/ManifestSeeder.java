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

    /** What the seeded administrator is called, on the screens that show a job rather than a role. */
    private static final String SEEDED_ADMIN_TITLE = "Super administrator";

    /**
     * The vocabulary an institution starts with — one title per seeded role template, plus the two
     * every branch has and no template covers. Not derived from the template names in code: a role
     * name is a slug in a claim and a title is what appears on somebody's profile, and deriving one
     * from the other would tie a display string to an identifier that must never change.
     */
    private static final List<String> STARTING_JOB_TITLES = List.of(
            SEEDED_ADMIN_TITLE,
            "Administrator",
            "Branch manager",
            "Teller",
            "Supervisor",
            "Loan officer",
            "Compliance officer",
            "Operations officer",
            "Customer service officer");

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
        Path manifest = Path.of(path);
        if (!Files.exists(manifest)) {
            // Configured and absent is the same fact as never configured — there is no manifest to
            // converge — so it gets the same answer rather than a stack trace. It is the ordinary
            // state of a fresh clone: compose points at /bootstrap/tenants.json and mounts the
            // directory, and the repository commits no manifest, because whose tenants an instance
            // serves is a deployment's own business. Refusing to start here crash-looped the
            // service on `docker compose up` before anyone could write one.
            //
            // A malformed manifest still refuses outright, and that distinction is the point: a
            // manifest that says something wrong must never be half-applied, while a manifest that
            // does not exist has said nothing at all.
            log.warn("  │  Bootstrap  NO MANIFEST at {} — this instance can authenticate nobody until"
                    + " one is written there (mounted from ./bootstrap in compose)", manifest.toAbsolutePath());
            return;
        }
        JsonNode root = json.readTree(Files.readString(manifest));
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
            // Every job template, not only the administrator's. An institution has to be able to
            // staff itself on day one — a teller, a supervisor, a loan officer — and authoring a
            // role is the operation that needs a second signature and therefore a second human.
            // Seeding the starting position is what breaks that circle. ADR 0017: nothing here is
            // privileged after provisioning; these are a starting position, not a structure.
            for (var template : PermissionCatalog.ROLE_TEMPLATES.entrySet()) {
                tx.jdbc()
                        .update(
                                "INSERT INTO auth.roles (tenant_id, name, template, created_by, created_via)"
                                        + " VALUES (?,?,TRUE,?,?) ON CONFLICT DO NOTHING",
                                tenantId,
                                template.getKey(),
                                "bootstrap:manifest",
                                "service:identity");
                for (String permission : template.getValue()) {
                    tx.jdbc()
                            .update(
                                    "INSERT INTO auth.role_permissions (tenant_id, role_name, permission)"
                                            + " VALUES (?,?,?) ON CONFLICT DO NOTHING",
                                    tenantId,
                                    template.getKey(),
                                    permission);
                }
            }
            // A starting vocabulary of job titles, for the same reason the role templates are
            // seeded: an institution has to be able to describe its own staff on day one. Titles
            // are not roles and grant nothing — this is the list a hiring form offers, and an
            // administrator adds to it or retires from it under Settings. Converged with
            // ON CONFLICT so an institution that has renamed its jobs keeps its own.
            for (String title : STARTING_JOB_TITLES) {
                tx.jdbc()
                        .update(
                                "INSERT INTO auth.job_titles (tenant_id, title, created_by)"
                                        + " VALUES (?,?,?) ON CONFLICT DO NOTHING",
                                tenantId,
                                title,
                                "bootstrap:manifest");
            }
            // The numbering rule, so the first hire is numbered rather than blank. Defaults only —
            // an institution that already numbers its staff changes prefix, width and next value
            // under Settings before it hires anybody.
            tx.jdbc()
                    .update(
                            "INSERT INTO auth.staff_numbering (tenant_id, updated_by) VALUES (?,?)"
                                    + " ON CONFLICT (tenant_id) DO NOTHING",
                            tenantId,
                            "bootstrap:manifest");

            int rows = tx.jdbc()
                    .update(
                            "INSERT INTO auth.users (tenant_id, id, username, email, first_name,"
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
                // Converged already: the user exists, so nothing here creates anything. One grant
                // is still asserted, because a tenant seeded before job:super-admin existed has a
                // super-administrator who cannot staff their own institution — the manifest says
                // who holds that authority, and a boot that reads the manifest should make it true.
                tx.jdbc()
                        .update(
                                "INSERT INTO auth.user_roles (tenant_id, user_id, role_name)"
                                        + " SELECT ?, u.id, ? FROM auth.users u"
                                        + " WHERE u.tenant_id = ? AND lower(u.username) = lower(?)"
                                        + " ON CONFLICT DO NOTHING",
                                tenantId,
                                PermissionCatalog.SUPER_ADMIN_ROLE,
                                tenantId,
                                username);
                // Exempt from the profile gate: there is nobody to unblock the first
                // administrator if they get stuck behind it, and the institution has to be
                // administrable from its first minute. They complete their own record from
                // Settings like anyone else.
                tx.jdbc()
                        .update(
                                "UPDATE auth.users SET profile_completed_at = now()"
                                        + " WHERE tenant_id = ? AND lower(username) = lower(?)"
                                        + " AND profile_completed_at IS NULL",
                                tenantId,
                                username);
                // Tenants seeded before job titles and staff numbers existed have a super-
                // administrator whose own record reads as blank on every screen that shows it.
                // Filled here rather than left for them to notice, and only where still null so a
                // deliberate change is never overwritten on the next boot.
                tx.jdbc()
                        .update(
                                "UPDATE auth.users SET job_title = ?"
                                        + " WHERE tenant_id = ? AND lower(username) = lower(?)"
                                        + " AND job_title IS NULL",
                                SEEDED_ADMIN_TITLE,
                                tenantId,
                                username);
                return false;
            }
            tx.jdbc()
                    .update(
                            "INSERT INTO auth.user_roles (tenant_id, user_id, role_name) VALUES (?,?,?)"
                                    + " ON CONFLICT DO NOTHING",
                            tenantId,
                            userId,
                            PermissionCatalog.SUPER_ADMIN_ROLE);
            tx.jdbc()
                    .update(
                            "INSERT INTO auth.credentials (tenant_id, user_id, password_hash)"
                                    + " VALUES (?,?,?)",
                            tenantId,
                            userId,
                            passwords.hash(temporary));
            tx.jdbc()
                    .update(
                            "UPDATE auth.users SET profile_completed_at = now(), job_title = ?"
                                    + " WHERE tenant_id = ? AND id = ?",
                            SEEDED_ADMIN_TITLE,
                            tenantId,
                            userId);
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
