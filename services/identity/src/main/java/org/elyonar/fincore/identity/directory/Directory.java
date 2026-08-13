package org.elyonar.fincore.identity.directory;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.elyonar.fincore.identity.PermissionCatalog;
import org.elyonar.fincore.identity.api.IdentityErrors;
import org.elyonar.fincore.identity.auth.AuthEvent;
import org.elyonar.fincore.identity.auth.AuthEvents;
import org.elyonar.fincore.identity.auth.Passwords;
import org.elyonar.fincore.identity.auth.Sessions;
import org.elyonar.fincore.identity.auth.StaffTokens;
import org.elyonar.fincore.identity.auth.Throttle;
import org.elyonar.fincore.identity.internal.Tx;
import org.springframework.stereotype.Component;

/**
 * The staff directory: who works here, what they may do, and where.
 *
 * <p>The service-facing half of admin-surface §5 (ADR 0018). Core owns the product surface and the
 * maker-checker workflow; this owns the records and the rules that must hold no matter which
 * caller asks. Two of ADR 0017's guardrails are enforced here rather than only in Core, because a
 * rule enforced only in the caller is a rule that ends the first time a second caller appears:
 *
 * <ul>
 *   <li><b>Guardrail 1 — no administrator grants a permission they do not themselves hold.</b>
 *       Without it, one administration permission is every permission: an administrator writes
 *       themselves a role containing everything and deny-by-default has been defeated from
 *       inside. Checked against the <em>initiating</em> administrator's effective permissions,
 *       resolved the same way a token resolves them.
 *   <li><b>Guardrail 0 — a role name may never be a permission name.</b> Enforced in
 *       {@link PermissionCatalog#roleNameViolation} and applied wherever a name is stored.
 * </ul>
 *
 * <p>Every method runs inside a tenant transaction the caller opens.
 */
@Component
public class Directory {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** How many staff one page returns. Keyset by username, so the cursor is the last one seen. */
    private static final int PAGE = 50;

    private final Tx tx;
    private final Passwords passwords;
    private final StaffTokens staff;
    private final Sessions sessions;
    private final Throttle throttle;
    private final AuthEvents audit;

    public Directory(
            Tx tx,
            Passwords passwords,
            StaffTokens staff,
            Sessions sessions,
            Throttle throttle,
            AuthEvents audit) {
        this.tx = tx;
        this.passwords = passwords;
        this.staff = staff;
        this.sessions = sessions;
        this.throttle = throttle;
        this.audit = audit;
    }

    // --- shapes --------------------------------------------------------------------------------

    public record Permission(String name, String grants) {}

    public record Role(String name, boolean template, List<String> permissions, int holders) {}

    /**
     * A member of staff. The record splits along who may write it: the administered half is set by
     * whoever administers staff, the self-declared half only by the person themselves.
     */
    public record User(
            UUID id,
            String username,
            String email,
            String firstName,
            String lastName,
            String status,
            boolean credentialTemporary,
            List<String> roles,
            List<String> units,
            // administered
            String staffNumber,
            String jobTitle,
            String startedOn,
            // self-declared
            String phone,
            /** Null until the person completes their record. The portal's onboarding gate. */
            String profileCompletedAt) {

        /** Whether this person may use the portal, or is still held at onboarding. */
        public boolean profileComplete() {
            return profileCompletedAt != null;
        }
    }

    /** The half of the record a person fills in about themselves. */
    public record SelfProfile(String phone) {}

    public record UserPage(List<User> users, String nextCursor) {}

    public record NewUser(
            String username,
            String email,
            String firstName,
            String lastName,
            List<String> roles,
            List<String> units,
            String staffNumber,
            String jobTitle,
            String startedOn) {}

    /** What creation hands back — the credential appears here once and is never readable again. */
    public record CreatedUser(UUID id, String username, String temporaryCredential) {}

    /** A job the institution recognises, and how many people currently hold it. */
    public record JobTitle(String title, int holders) {}

    /**
     * How staff numbers are formed.
     *
     * @param preview what the next hire would be given, so a settings screen can show the rule's
     *     effect rather than asking an administrator to imagine it. Null on the way in.
     */
    public record StaffNumbering(String prefix, int width, long nextValue, String preview) {
        StaffNumbering withPreview(String preview) {
            return new StaffNumbering(prefix, width, nextValue, preview);
        }
    }

    /**
     * The administered facts about somebody's job, settable after they were created.
     *
     * @param autoAssignStaffNumber take the next number from the institution's rule instead of the
     *     string above. Sent as a flag rather than inferred from a blank field, because blank
     *     legitimately means "clear this" and the two must not be the same request.
     */
    public record Employment(
            String staffNumber, String jobTitle, String startedOn, Boolean autoAssignStaffNumber) {}

    // --- reads ---------------------------------------------------------------------------------

    /** The closed vocabulary the code can enforce (ADR 0017). Read-only, forever. */
    public List<Permission> permissions() {
        return PermissionCatalog.PERMISSIONS.stream()
                .sorted()
                .map(name -> new Permission(name, PermissionCatalog.GRANTS.getOrDefault(name, "")))
                .toList();
    }

    /** The tenant's roles — templates it was seeded with and any it has authored — with contents. */
    public List<Role> roles(UUID tenantId) {
        List<Map<String, Object>> rows = tx.jdbc()
                .queryForList(
                        "SELECT r.name, r.template,"
                                + " COALESCE(array_agg(rp.permission ORDER BY rp.permission)"
                                + "   FILTER (WHERE rp.permission IS NOT NULL), '{}'::text[]) AS permissions,"
                                // Counted here rather than by the caller: a client that derived this
                                // from a page of staff would be wrong the moment staff paginate.
                                + " (SELECT count(*)::int FROM auth.user_roles ur"
                                + "   WHERE ur.tenant_id = r.tenant_id AND ur.role_name = r.name) AS holders"
                                + " FROM auth.roles r"
                                + " LEFT JOIN auth.role_permissions rp"
                                + "   ON rp.tenant_id = r.tenant_id AND rp.role_name = r.name"
                                + " WHERE r.tenant_id = ?"
                                // r.tenant_id is grouped because the holders subquery correlates
                                // on it; without it Postgres refuses the whole statement.
                                + " GROUP BY r.tenant_id, r.name, r.template ORDER BY r.name",
                        tenantId);
        return rows.stream()
                .map(row -> new Role(
                        (String) row.get("name"),
                        Boolean.TRUE.equals(row.get("template")),
                        array(row.get("permissions")),
                        row.get("holders") instanceof Number count ? count.intValue() : 0))
                .toList();
    }

    /** One page of staff, keyset by username. Absent and another tenant's are indistinguishable. */
    public UserPage listUsers(UUID tenantId, String role, String unit, String cursor) {
        return listUsers(tenantId, role, unit, null, cursor);
    }

    /**
     * The same page, narrowed to one username.
     *
     * <p>Exists so a caller holding a principal — {@code user:ngozi.teller}, the spelling tokens and
     * assignment rows use — can reach the user record without knowing its id. Core's organization
     * surface assigns by principal and has to move the {@code units} claim with it; without this it
     * could only find the id by walking every page of staff.
     */
    public UserPage listUsers(UUID tenantId, String role, String unit, String username, String cursor) {
        var sql = new StringBuilder(
                "SELECT u.id, u.username, u.email, u.first_name, u.last_name, u.status,"
                        + " u.credential_temporary, u.staff_number, u.job_title, u.started_on,"
                        + " u.phone, u.profile_completed_at"
                        + " FROM auth.users u WHERE u.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (role != null && !role.isBlank()) {
            sql.append(" AND EXISTS (SELECT 1 FROM auth.user_roles ur WHERE ur.tenant_id = u.tenant_id"
                    + " AND ur.user_id = u.id AND ur.role_name = ?)");
            args.add(role.trim());
        }
        if (unit != null && !unit.isBlank()) {
            sql.append(" AND EXISTS (SELECT 1 FROM auth.user_units uu WHERE uu.tenant_id = u.tenant_id"
                    + " AND uu.user_id = u.id AND uu.unit_code = ?)");
            args.add(unit.trim());
        }
        if (username != null && !username.isBlank()) {
            sql.append(" AND lower(u.username) = ?");
            args.add(username.trim().toLowerCase(Locale.ROOT));
        }
        if (cursor != null && !cursor.isBlank()) {
            sql.append(" AND lower(u.username) > ?");
            args.add(cursor.trim().toLowerCase(Locale.ROOT));
        }
        sql.append(" ORDER BY lower(u.username) LIMIT ").append(PAGE + 1);

        List<Map<String, Object>> rows = tx.jdbc().queryForList(sql.toString(), args.toArray());
        boolean more = rows.size() > PAGE;
        List<User> users = rows.stream().limit(PAGE).map(row -> hydrate(tenantId, row)).toList();
        return new UserPage(users, more ? users.get(users.size() - 1).username() : null);
    }

    /** One user, or null — absent and another tenant's are the same answer. */
    public User user(UUID tenantId, UUID id) {
        List<Map<String, Object>> rows = tx.jdbc()
                .queryForList(
                        "SELECT id, username, email, first_name, last_name, status, credential_temporary,"
                                + " staff_number, job_title, started_on, phone, profile_completed_at"
                                + " FROM auth.users WHERE tenant_id = ? AND id = ?",
                        tenantId,
                        id);
        return rows.isEmpty() ? null : hydrate(tenantId, rows.get(0));
    }

    // --- writes --------------------------------------------------------------------------------

    /**
     * Creates a member of staff with a temporary credential and a forced change on first use.
     *
     * <p>Not maker-checked, and that is the AGREED table's decision rather than an omission: a
     * tenant is seeded with exactly one administrator, so a second signature on the operation
     * that creates the second human is a deadlock. Changing an existing user's roles — the
     * operation an attacker actually wants — is maker-checked and lives in Core.
     */
    public CreatedUser createUser(UUID tenantId, Administrator admin, NewUser request, String source) {
        String username = require(request.username(), "username").trim();
        String email = require(request.email(), "email").trim();
        String firstName = require(request.firstName(), "firstName").trim();
        String lastName = require(request.lastName(), "lastName").trim();
        List<String> roles = clean(request.roles());
        List<String> units = clean(request.units());

        requireRolesExist(tenantId, roles);
        requireGrantorHolds(tenantId, admin, roles);

        UUID userId = UUID.randomUUID();
        byte[] raw = new byte[18];
        RANDOM.nextBytes(raw);
        String temporary = "tmp_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        int rows;
        try {
            rows = tx.jdbc()
                    .update(
                            "INSERT INTO auth.users (tenant_id, id, username, email, first_name,"
                                    + " last_name, created_by, created_via) VALUES (?,?,?,?,?,?,?,?)"
                                    + " ON CONFLICT DO NOTHING",
                            tenantId,
                            userId,
                            username,
                            email,
                            firstName,
                            lastName,
                            admin.attribution(),
                            admin.service());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw refuse(DirectoryErrors.USER_EXISTS, null, Map.of("username", username));
        }
        if (rows == 0) {
            // The unique index on (tenant, lower(username)) arbitrated: someone already has it.
            throw refuse(DirectoryErrors.USER_EXISTS, null, Map.of("username", username));
        }

        // The administered half: facts about the job, set here and never by the holder. A staff
        // number left blank is taken from the institution's numbering rule rather than left null —
        // an identifier payroll needs is not something to remember to fill in later.
        String jobTitle = blankToNull(request.jobTitle());
        requireJobTitleKnown(tenantId, jobTitle);
        String staffNumber = blankToNull(request.staffNumber());
        if (staffNumber == null) {
            staffNumber = claimStaffNumber(tenantId);
        }
        try {
            tx.jdbc()
                    .update(
                            "UPDATE auth.users SET staff_number = ?, job_title = ?, started_on = ?::date"
                                    + " WHERE tenant_id = ? AND id = ?",
                            staffNumber,
                            jobTitle,
                            blankToNull(request.startedOn()),
                            tenantId,
                            userId);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw refuse(DirectoryErrors.STAFF_NUMBER_TAKEN, null, Map.of("staffNumber", staffNumber));
        }

        for (String role : roles) {
            tx.jdbc()
                    .update(
                            "INSERT INTO auth.user_roles (tenant_id, user_id, role_name) VALUES (?,?,?)",
                            tenantId,
                            userId,
                            role);
        }
        for (String unit : units) {
            tx.jdbc()
                    .update(
                            "INSERT INTO auth.user_units (tenant_id, user_id, unit_code) VALUES (?,?,?)",
                            tenantId,
                            userId,
                            unit);
        }
        // credential_temporary defaults TRUE, so the first login is a forced change by construction.
        tx.jdbc()
                .update(
                        "INSERT INTO auth.credentials (tenant_id, user_id, password_hash) VALUES (?,?,?)",
                        tenantId,
                        userId,
                        passwords.hash(temporary));

        audit.recordAs(
                tenantId,
                userId,
                AuthEvent.USER_CREATED,
                admin.attribution(),
                admin.service(),
                source,
                Map.of("username", username, "roles", roles, "units", units));
        return new CreatedUser(userId, username, temporary);
    }

    /**
     * Replaces a user's unit assignments — the claim half of admin-surface's two-record write.
     *
     * <p>Core writes its own {@code unit_assignments} and calls this, so the record and the claim
     * move together. ADR 0017 names the drift between them as a live defect: assignments were the
     * documented system of record and nothing derived the claim, so assigning a teller to a branch
     * had no effect on authorization. The change takes effect on that user's next token.
     */
    public void setUnits(UUID tenantId, UUID userId, List<String> units, Administrator admin, String source) {
        requireUser(tenantId, userId);
        List<String> wanted = clean(units);
        tx.jdbc().update("DELETE FROM auth.user_units WHERE tenant_id = ? AND user_id = ?", tenantId, userId);
        for (String unit : wanted) {
            tx.jdbc()
                    .update(
                            "INSERT INTO auth.user_units (tenant_id, user_id, unit_code) VALUES (?,?,?)",
                            tenantId,
                            userId,
                            unit);
        }
        audit.recordAs(
                tenantId,
                userId,
                AuthEvent.UNITS_CHANGED,
                admin.attribution(),
                admin.service(),
                source,
                Map.of("units", wanted));
    }

    /**
     * Issues a fresh temporary credential and revokes every session the user holds.
     *
     * <p>The revocation is the point as much as the reset: an administrator resetting a password
     * is usually responding to "I think someone else has it".
     */
    public String resetPassword(UUID tenantId, UUID userId, Administrator admin, String source) {
        User target = requireUser(tenantId, userId);
        // Guardrail 1 again, and it was missing here — which made every other application of it
        // decoration. Composing a role refuses a permission the author does not hold, and granting
        // one refuses the same; but handing somebody a credential for an account that holds it is
        // the same grant with an extra step. An administrator could not give themselves
        // `cash:transact`, and could reset the super-administrator, sign in as them, and take it.
        requireNoAuthorityAbove(tenantId, admin, userId, target);

        byte[] raw = new byte[18];
        RANDOM.nextBytes(raw);
        String temporary = "tmp_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        int updated = tx.jdbc()
                .update(
                        "UPDATE auth.credentials SET password_hash = ?, updated_at = now()"
                                + " WHERE tenant_id = ? AND user_id = ?",
                        passwords.hash(temporary),
                        tenantId,
                        userId);
        if (updated == 0) {
            tx.jdbc()
                    .update(
                            "INSERT INTO auth.credentials (tenant_id, user_id, password_hash) VALUES (?,?,?)",
                            tenantId,
                            userId,
                            passwords.hash(temporary));
        }
        tx.jdbc()
                .update(
                        "UPDATE auth.users SET credential_temporary = TRUE WHERE tenant_id = ? AND id = ?",
                        tenantId,
                        userId);
        sessions.revokeAllFor(tenantId, userId, "ADMIN", source);
        throttle.unlock(tenantId, target.username());

        audit.recordAs(
                tenantId,
                userId,
                AuthEvent.PASSWORD_RESET,
                admin.attribution(),
                admin.service(),
                source,
                Map.of("username", target.username()));
        return temporary;
    }

    /** Clears a lockout early, so a fat-fingered teller is not idle for the window. */
    public void unlock(UUID tenantId, UUID userId, Administrator admin, String source) {
        User target = requireUser(tenantId, userId);
        throttle.unlock(tenantId, target.username());
        audit.recordAs(
                tenantId,
                userId,
                AuthEvent.UNLOCKED,
                admin.attribution(),
                admin.service(),
                source,
                Map.of("username", target.username()));
    }

    /**
     * Creates a tenant-authored role. The name is namespaced {@code role:} by the service, never by
     * the caller (guardrail 0), and every permission must be one the initiating administrator
     * already holds (guardrail 1) — which is what makes authoring safe from escalation even
     * without a second signature: nobody can compose access they do not already have.
     */
    public Role createRole(UUID tenantId, Administrator admin, String proposed, List<String> permissions, String source) {
        String violation = PermissionCatalog.roleNameViolation(proposed);
        if (violation != null) {
            throw refuse(DirectoryErrors.ROLE_NAME_INVALID, violation, Map.of("name", String.valueOf(proposed)));
        }
        String name = PermissionCatalog.tenantRoleName(proposed);
        List<String> wanted = requirePermissions(permissions);
        requireGrantorHoldsPermissions(tenantId, admin, wanted);

        int rows = tx.jdbc()
                .update(
                        "INSERT INTO auth.roles (tenant_id, name, template, created_by, created_via)"
                                + " VALUES (?,?,FALSE,?,?) ON CONFLICT DO NOTHING",
                        tenantId,
                        name,
                        admin.attribution(),
                        admin.service());
        if (rows == 0) {
            throw refuse(DirectoryErrors.ROLE_EXISTS, null, Map.of("role", name));
        }
        for (String permission : wanted) {
            tx.jdbc()
                    .update(
                            "INSERT INTO auth.role_permissions (tenant_id, role_name, permission) VALUES (?,?,?)",
                            tenantId,
                            name,
                            permission);
        }
        audit.recordAs(tenantId, null, AuthEvent.ROLE_CREATED, admin.attribution(), admin.service(), source,
                Map.of("role", name, "permissions", wanted));
        return new Role(name, false, wanted, 0);
    }

    /** Replaces what a role grants, as a set. Guardrail 1 applies to the incoming permissions. */
    public Role setRolePermissions(UUID tenantId, Administrator admin, String role, List<String> permissions, String source) {
        boolean template = requireRole(tenantId, role);
        List<String> wanted = requirePermissions(permissions);
        requireGrantorHoldsPermissions(tenantId, admin, wanted);

        tx.jdbc().update("DELETE FROM auth.role_permissions WHERE tenant_id = ? AND role_name = ?", tenantId, role);
        for (String permission : wanted) {
            tx.jdbc()
                    .update(
                            "INSERT INTO auth.role_permissions (tenant_id, role_name, permission) VALUES (?,?,?)",
                            tenantId,
                            role,
                            permission);
        }
        requireAnAdministratorRemains(tenantId);
        audit.recordAs(tenantId, null, AuthEvent.ROLE_CHANGED, admin.attribution(), admin.service(), source,
                Map.of("role", role, "permissions", wanted));
        return new Role(role, template, wanted, holdersOf(tenantId, role));
    }

    /** Removes a role. Refused while held, and template roles are the platform's starting position. */
    public void deleteRole(UUID tenantId, Administrator admin, String role, String source) {
        boolean template = requireRole(tenantId, role);
        if (template) {
            throw refuse(DirectoryErrors.ROLE_NOT_CUSTOM, null, Map.of("role", role));
        }
        Integer held = tx.jdbc()
                .queryForObject(
                        "SELECT count(*)::int FROM auth.user_roles WHERE tenant_id = ? AND role_name = ?",
                        Integer.class, tenantId, role);
        if (held != null && held > 0) {
            throw refuse(DirectoryErrors.ROLE_IN_USE, null, Map.of("role", role, "holders", held));
        }
        tx.jdbc().update("DELETE FROM auth.role_permissions WHERE tenant_id = ? AND role_name = ?", tenantId, role);
        tx.jdbc().update("DELETE FROM auth.roles WHERE tenant_id = ? AND name = ?", tenantId, role);
        audit.recordAs(tenantId, null, AuthEvent.ROLE_DELETED, admin.attribution(), admin.service(), source,
                Map.of("role", role));
    }

    /**
     * Replaces a user's role grants.
     *
     * <p>Guardrail 1 on what is being granted, and guardrail 2 on what is being taken away: the
     * last holder of user administration cannot be stripped of it, including by themselves. A
     * tenant that locks itself out needs an operator with platform authority, and ADR 0016
     * deliberately does not provide one.
     */
    public void setUserRoles(UUID tenantId, UUID userId, List<String> roles, Administrator admin, String source) {
        requireUser(tenantId, userId);
        List<String> wanted = clean(roles);
        requireRolesExist(tenantId, wanted);
        requireGrantorHolds(tenantId, admin, wanted);

        tx.jdbc().update("DELETE FROM auth.user_roles WHERE tenant_id = ? AND user_id = ?", tenantId, userId);
        for (String role : wanted) {
            tx.jdbc()
                    .update(
                            "INSERT INTO auth.user_roles (tenant_id, user_id, role_name) VALUES (?,?,?)",
                            tenantId,
                            userId,
                            role);
        }
        requireAnAdministratorRemains(tenantId);
        audit.recordAs(tenantId, userId, AuthEvent.ROLES_CHANGED, admin.attribution(), admin.service(), source,
                Map.of("roles", wanted));
    }

    /**
     * Updates the self-declared half of somebody's own record, and opens the onboarding gate once
     * a contact number is present.
     *
     * <p>The gate closes again if the number is later cleared, which is deliberate: the record is
     * a standing requirement rather than a one-time form, and a phone number deleted is a phone
     * number the institution no longer has.
     */
    public User updateOwnProfile(UUID tenantId, UUID userId, SelfProfile profile, String source) {
        User before = requireUser(tenantId, userId);
        String phone = blankToNull(profile.phone());

        tx.jdbc()
                .update(
                        "UPDATE auth.users SET phone = ?,"
                                + " profile_completed_at = CASE WHEN ?::text IS NULL THEN NULL"
                                + "     ELSE COALESCE(profile_completed_at, now()) END"
                                + " WHERE tenant_id = ? AND id = ?",
                        phone,
                        phone,
                        tenantId,
                        userId);

        audit.recordAs(
                tenantId,
                userId,
                before.profileComplete() ? AuthEvent.PROFILE_UPDATED : AuthEvent.PROFILE_COMPLETED,
                "user:" + before.username(),
                "service:identity",
                source,
                Map.of("complete", phone != null));
        return requireUser(tenantId, userId);
    }

    // --- the institution's vocabulary ----------------------------------------------------------

    /**
     * The job titles this institution uses, with how many people hold each.
     *
     * <p>A title is not a role and this file keeps them apart deliberately. A role is what somebody
     * may do and is enforced on every request; a title is what they are called and is enforced
     * nowhere. Institutions that conflate the two end up encoding place and seniority into
     * permission sets — {@code job:teller-lagos} — which is the multiplication ADR 0012 exists to
     * prevent.
     */
    public List<JobTitle> jobTitles(UUID tenantId) {
        return tx.jdbc()
                .query(
                        "SELECT t.title,"
                                + " (SELECT count(*) FROM auth.users u"
                                + "   WHERE u.tenant_id = t.tenant_id AND u.job_title = t.title) AS holders"
                                + " FROM auth.job_titles t WHERE t.tenant_id = ? ORDER BY lower(t.title)",
                        (rs, row) -> new JobTitle(rs.getString("title"), rs.getInt("holders")),
                        tenantId);
    }

    /** Adds a title to the vocabulary. Case is preserved as authored; uniqueness ignores it. */
    public JobTitle createJobTitle(UUID tenantId, Administrator admin, String proposed, String source) {
        String title = require(proposed, "title").trim();
        if (title.length() > 96) {
            throw refuse(DirectoryErrors.FIELD_INVALID, "title", Map.of("title", title));
        }

        int rows;
        try {
            rows = tx.jdbc()
                    .update(
                            "INSERT INTO auth.job_titles (tenant_id, title, created_by) VALUES (?,?,?)"
                                    + " ON CONFLICT DO NOTHING",
                            tenantId,
                            title,
                            admin.attribution());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // The case-insensitive index, which ON CONFLICT DO NOTHING does not cover.
            throw refuse(DirectoryErrors.JOB_TITLE_EXISTS, null, Map.of("title", title));
        }
        if (rows == 0) {
            throw refuse(DirectoryErrors.JOB_TITLE_EXISTS, null, Map.of("title", title));
        }

        audit.recordAs(
                tenantId,
                admin.userId(),
                AuthEvent.JOB_TITLE_CREATED,
                admin.attribution(),
                admin.service(),
                source,
                Map.of("title", title));
        return new JobTitle(title, 0);
    }

    /**
     * Retires a title.
     *
     * <p>Refused while anybody holds it, for the same reason a role in use cannot be deleted: the
     * alternative is a staff record naming a job the institution no longer recognises, discovered
     * by whoever next reads a profile.
     */
    public void deleteJobTitle(UUID tenantId, Administrator admin, String title, String source) {
        Integer holders = tx.jdbc()
                .queryForObject(
                        "SELECT count(*) FROM auth.users WHERE tenant_id = ? AND job_title = ?",
                        Integer.class,
                        tenantId,
                        title);
        if (holders != null && holders > 0) {
            throw refuse(DirectoryErrors.JOB_TITLE_IN_USE, null, Map.of("holders", holders));
        }

        int rows = tx.jdbc()
                .update("DELETE FROM auth.job_titles WHERE tenant_id = ? AND title = ?", tenantId, title);
        if (rows == 0) {
            throw refuse(DirectoryErrors.JOB_TITLE_UNKNOWN, null, Map.of("title", title));
        }

        audit.recordAs(
                tenantId,
                admin.userId(),
                AuthEvent.JOB_TITLE_DELETED,
                admin.attribution(),
                admin.service(),
                source,
                Map.of("title", title));
    }

    /** How the next staff number will be formed, and what it will be. */
    public StaffNumbering numbering(UUID tenantId) {
        StaffNumbering found = tx.jdbc()
                .query(
                        "SELECT prefix, width, next_value FROM auth.staff_numbering WHERE tenant_id = ?",
                        rs ->
                                rs.next()
                                        ? new StaffNumbering(
                                                rs.getString("prefix"),
                                                rs.getInt("width"),
                                                rs.getLong("next_value"),
                                                null)
                                        : null,
                        tenantId);
        StaffNumbering numbering = found == null ? DEFAULT_NUMBERING : found;
        return numbering.withPreview(format(numbering));
    }

    /**
     * Changes the numbering rule.
     *
     * <p>{@code nextValue} is settable because an institution migrating onto this platform arrives
     * with numbers already issued, and a counter that can only start at one would collide with
     * every one of them on the first hire. Lowering it is permitted and the unique index remains
     * the arbiter — a collision refuses the hire rather than silently reusing an identifier.
     */
    public StaffNumbering setNumbering(
            UUID tenantId, Administrator admin, StaffNumbering request, String source) {
        String prefix = request.prefix() == null ? "" : request.prefix().trim();
        int width = request.width();
        long next = request.nextValue();
        if (width < 1 || width > 12) {
            throw refuse(DirectoryErrors.FIELD_INVALID, "width", Map.of("width", width));
        }
        if (next < 1) {
            throw refuse(DirectoryErrors.FIELD_INVALID, "nextValue", Map.of("nextValue", next));
        }

        tx.jdbc()
                .update(
                        "INSERT INTO auth.staff_numbering (tenant_id, prefix, width, next_value, updated_by)"
                                + " VALUES (?,?,?,?,?)"
                                + " ON CONFLICT (tenant_id) DO UPDATE SET prefix = EXCLUDED.prefix,"
                                + " width = EXCLUDED.width, next_value = EXCLUDED.next_value,"
                                + " updated_at = now(), updated_by = EXCLUDED.updated_by",
                        tenantId,
                        prefix,
                        width,
                        next,
                        admin.attribution());

        audit.recordAs(
                tenantId,
                admin.userId(),
                AuthEvent.NUMBERING_CHANGED,
                admin.attribution(),
                admin.service(),
                source,
                Map.of("prefix", prefix, "width", width, "nextValue", next));
        return numbering(tenantId);
    }

    /**
     * Sets the administered half of somebody's record after the fact.
     *
     * <p>Creation was the only place these could be set, which left every person hired before an
     * institution decided on titles — including the seeded administrator — permanently blank. They
     * stay administered: the holder may not write them, because a person editing their own job
     * title is not a control.
     */
    public User setEmployment(
            UUID tenantId, Administrator admin, UUID userId, Employment request, String source) {
        requireUser(tenantId, userId);
        String jobTitle = blankToNull(request.jobTitle());
        String staffNumber = blankToNull(request.staffNumber());
        String startedOn = blankToNull(request.startedOn());
        if (staffNumber == null && Boolean.TRUE.equals(request.autoAssignStaffNumber())) {
            // Claimed from the counter here rather than composed by the caller: a client that
            // reads the preview and posts it back can hand two people the same number, and the
            // counter would never move past it.
            staffNumber = claimStaffNumber(tenantId);
        }
        requireJobTitleKnown(tenantId, jobTitle);

        try {
            tx.jdbc()
                    .update(
                            "UPDATE auth.users SET staff_number = ?, job_title = ?, started_on = ?::date"
                                    + " WHERE tenant_id = ? AND id = ?",
                            staffNumber,
                            jobTitle,
                            startedOn,
                            tenantId,
                            userId);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw refuse(DirectoryErrors.STAFF_NUMBER_TAKEN, null, Map.of("staffNumber", staffNumber));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // A malformed date reaches the driver as a cast failure, which is a caller error.
            throw refuse(DirectoryErrors.FIELD_INVALID, "startedOn", Map.of("startedOn", String.valueOf(startedOn)));
        }

        audit.recordAs(
                tenantId,
                userId,
                AuthEvent.EMPLOYMENT_CHANGED,
                admin.attribution(),
                admin.service(),
                source,
                Map.of(
                        "jobTitle", String.valueOf(jobTitle),
                        "staffNumber", String.valueOf(staffNumber),
                        "startedOn", String.valueOf(startedOn)));
        return requireUser(tenantId, userId);
    }

    /** The numbering an institution gets before it decides on one. */
    private static final StaffNumbering DEFAULT_NUMBERING = new StaffNumbering("STF-", 4, 1L, null);

    /**
     * Takes the next staff number, atomically.
     *
     * <p>{@code UPDATE ... RETURNING} under the row lock is the arbiter, so two administrators
     * hiring in the same second take different numbers. The row is created on first use rather
     * than by a migration, because migrations run with no tenant context against FORCE ROW LEVEL
     * SECURITY tables and would write nothing while appearing to succeed.
     */
    private String claimStaffNumber(UUID tenantId) {
        Long claimed = tx.jdbc()
                .query(
                        "UPDATE auth.staff_numbering SET next_value = next_value + 1"
                                + " WHERE tenant_id = ? RETURNING next_value - 1",
                        rs -> rs.next() ? rs.getLong(1) : null,
                        tenantId);
        if (claimed == null) {
            tx.jdbc()
                    .update(
                            "INSERT INTO auth.staff_numbering (tenant_id, prefix, width, next_value, updated_by)"
                                    + " VALUES (?,?,?,?,?) ON CONFLICT (tenant_id) DO NOTHING",
                            tenantId,
                            DEFAULT_NUMBERING.prefix(),
                            DEFAULT_NUMBERING.width(),
                            DEFAULT_NUMBERING.nextValue() + 1,
                            "service:identity");
            claimed = DEFAULT_NUMBERING.nextValue();
            return format(DEFAULT_NUMBERING);
        }
        StaffNumbering rule = tx.jdbc()
                .query(
                        "SELECT prefix, width FROM auth.staff_numbering WHERE tenant_id = ?",
                        rs ->
                                rs.next()
                                        ? new StaffNumbering(rs.getString("prefix"), rs.getInt("width"), 0L, null)
                                        : DEFAULT_NUMBERING,
                        tenantId);
        return format(new StaffNumbering(rule.prefix(), rule.width(), claimed, null));
    }

    private static String format(StaffNumbering numbering) {
        String digits = Long.toString(numbering.nextValue());
        StringBuilder padded = new StringBuilder();
        for (int i = digits.length(); i < numbering.width(); i++) {
            padded.append('0');
        }
        return numbering.prefix() + padded + digits;
    }

    /**
     * A job title must be one the institution authored.
     *
     * <p>Until the vocabulary has anything in it, free text is accepted — refusing every hire
     * because nobody has visited a settings screen yet would make an empty catalogue a lockout.
     * From the first authored title onward the catalogue is the vocabulary.
     */
    private void requireJobTitleKnown(UUID tenantId, String title) {
        if (title == null) {
            return;
        }
        Integer authored = tx.jdbc()
                .queryForObject("SELECT count(*) FROM auth.job_titles WHERE tenant_id = ?", Integer.class, tenantId);
        if (authored == null || authored == 0) {
            return;
        }
        Integer match = tx.jdbc()
                .queryForObject(
                        "SELECT count(*) FROM auth.job_titles WHERE tenant_id = ? AND lower(title) = lower(?)",
                        Integer.class,
                        tenantId,
                        title);
        if (match == null || match == 0) {
            throw refuse(DirectoryErrors.JOB_TITLE_UNKNOWN, null, Map.of("title", title));
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // --- rules ---------------------------------------------------------------------------------

    /** The initiating administrator, as the forwarded token described them. */
    public record Administrator(UUID userId, String username, String service) {
        public String attribution() {
            return "user:" + username;
        }
    }

    private void requireRolesExist(UUID tenantId, List<String> roles) {
        for (String role : roles) {
            Integer found = tx.jdbc()
                    .queryForObject(
                            "SELECT count(*)::int FROM auth.roles WHERE tenant_id = ? AND name = ?",
                            Integer.class,
                            tenantId,
                            role);
            if (found == null || found == 0) {
                throw refuse(DirectoryErrors.ROLE_UNKNOWN, null, Map.of("role", role));
            }
        }
    }

    /**
     * Guardrail 1. The proposed roles resolve to a permission set; the administrator's own roles
     * resolve to theirs; the first must be contained in the second.
     */
    private void requireGrantorHolds(UUID tenantId, Administrator admin, List<String> roles) {
        if (roles.isEmpty()) {
            return;
        }
        Set<String> granting = new LinkedHashSet<>();
        for (String role : roles) {
            granting.addAll(tx.jdbc()
                    .queryForList(
                            "SELECT permission FROM auth.role_permissions WHERE tenant_id = ? AND role_name = ?",
                            String.class,
                            tenantId,
                            role));
        }
        Set<String> held = Set.copyOf(staff.permissions(tenantId, admin.userId()));
        List<String> exceeded = granting.stream().filter(p -> !held.contains(p)).sorted().toList();
        if (!exceeded.isEmpty()) {
            throw refuse(
                    DirectoryErrors.PERMISSION_NOT_HELD_BY_GRANTOR, null, Map.of("permissions", exceeded));
        }
    }

    /** Every string must be in the platform catalog — a role never invents a permission. */
    private List<String> requirePermissions(List<String> permissions) {
        List<String> wanted = clean(permissions);
        for (String permission : wanted) {
            if (!PermissionCatalog.PERMISSIONS.contains(permission)) {
                throw refuse(DirectoryErrors.PERMISSION_UNKNOWN, null, Map.of("permission", permission));
            }
        }
        return wanted;
    }

    /** Guardrail 1, applied to a bare permission set rather than to roles. */
    /**
     * Refuses to act on somebody who holds authority the actor does not.
     *
     * <p>Guardrail 1 stated for a *person* rather than for a list of permissions. Taking over an
     * account is not weaker than being granted what it holds — it is the same thing, arrived at
     * through the credential rather than through the role — so the check has to be the same.
     *
     * <p>Self is always allowed: an administrator resetting their own credential gains nothing,
     * and refusing it would lock somebody out of their own account for being senior.
     *
     * <p>The refusal names the permissions that stopped it, because the alternative is an
     * administrator who cannot tell whether the platform is broken or they are outranked.
     */
    private void requireNoAuthorityAbove(UUID tenantId, Administrator admin, UUID userId, User target) {
        if (userId.equals(admin.userId())) {
            return;
        }
        Set<String> mine = Set.copyOf(staff.permissions(tenantId, admin.userId()));
        List<String> theirsAlone =
                staff.permissions(tenantId, userId).stream()
                        .filter(permission -> !mine.contains(permission))
                        .sorted()
                        .toList();
        if (!theirsAlone.isEmpty()) {
            throw refuse(
                    DirectoryErrors.PERMISSION_NOT_HELD_BY_GRANTOR,
                    null,
                    Map.of("permissions", theirsAlone, "username", target.username()));
        }
    }

    private void requireGrantorHoldsPermissions(UUID tenantId, Administrator admin, List<String> permissions) {
        Set<String> held = Set.copyOf(staff.permissions(tenantId, admin.userId()));
        List<String> exceeded = permissions.stream().filter(p -> !held.contains(p)).sorted().toList();
        if (!exceeded.isEmpty()) {
            throw refuse(DirectoryErrors.PERMISSION_NOT_HELD_BY_GRANTOR, null, Map.of("permissions", exceeded));
        }
    }

    private int holdersOf(UUID tenantId, String role) {
        Integer count = tx.jdbc()
                .queryForObject(
                        "SELECT count(*)::int FROM auth.user_roles WHERE tenant_id = ? AND role_name = ?",
                        Integer.class, tenantId, role);
        return count == null ? 0 : count;
    }

    /** True when the role is a platform template. Refuses when it does not exist. */
    private boolean requireRole(UUID tenantId, String role) {
        List<Map<String, Object>> rows = tx.jdbc()
                .queryForList("SELECT template FROM auth.roles WHERE tenant_id = ? AND name = ?", tenantId, role);
        if (rows.isEmpty()) {
            throw refuse(DirectoryErrors.ROLE_UNKNOWN, null, Map.of("role", role));
        }
        return Boolean.TRUE.equals(rows.get(0).get("template"));
    }

    /**
     * Guardrail 2. Checked after the write and inside the same transaction, so the question asked
     * is the one that matters — "does an administrator remain?" — rather than a prediction about
     * what the write was going to do. The refusal rolls it back.
     */
    private void requireAnAdministratorRemains(UUID tenantId) {
        Integer remaining = tx.jdbc()
                .queryForObject(
                        "SELECT count(DISTINCT ur.user_id)::int FROM auth.user_roles ur"
                                + " JOIN auth.role_permissions rp"
                                + "   ON rp.tenant_id = ur.tenant_id AND rp.role_name = ur.role_name"
                                + " JOIN auth.users u ON u.tenant_id = ur.tenant_id AND u.id = ur.user_id"
                                + " WHERE ur.tenant_id = ? AND rp.permission = ? AND u.status = 'ACTIVE'",
                        Integer.class,
                        tenantId,
                        "users:manage");
        if (remaining == null || remaining == 0) {
            throw refuse(DirectoryErrors.LAST_ADMINISTRATOR, null, Map.of());
        }
    }

    private User requireUser(UUID tenantId, UUID userId) {
        User found = user(tenantId, userId);
        if (found == null) {
            throw refuse(DirectoryErrors.USER_NOT_FOUND, null, Map.of());
        }
        return found;
    }

    // --- plumbing ------------------------------------------------------------------------------

    private User hydrate(UUID tenantId, Map<String, Object> row) {
        UUID id = (UUID) row.get("id");
        return new User(
                id,
                (String) row.get("username"),
                (String) row.get("email"),
                (String) row.get("first_name"),
                (String) row.get("last_name"),
                (String) row.get("status"),
                Boolean.TRUE.equals(row.get("credential_temporary")),
                tx.jdbc()
                        .queryForList(
                                "SELECT role_name FROM auth.user_roles WHERE tenant_id = ? AND user_id = ?"
                                        + " ORDER BY role_name",
                                String.class,
                                tenantId,
                                id),
                tx.jdbc()
                        .queryForList(
                                "SELECT unit_code FROM auth.user_units WHERE tenant_id = ? AND user_id = ?"
                                        + " ORDER BY unit_code",
                                String.class,
                                tenantId,
                                id),
                (String) row.get("staff_number"),
                (String) row.get("job_title"),
                text(row.get("started_on")),
                (String) row.get("phone"),
                text(row.get("profile_completed_at")));
    }

    /** Dates and timestamps cross this wire as strings; the client formats, the service does not. */
    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> array(Object value) {
        if (value instanceof java.sql.Array sqlArray) {
            try {
                Object[] items = (Object[]) sqlArray.getArray();
                return java.util.Arrays.stream(items).map(String::valueOf).toList();
            } catch (java.sql.SQLException e) {
                return List.of();
            }
        }
        if (value instanceof Object[] items) {
            return java.util.Arrays.stream(items).map(String::valueOf).toList();
        }
        return List.of();
    }

    private static List<String> clean(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw refuse(DirectoryErrors.FIELD_REQUIRED, field, Map.of("field", field));
        }
        return value;
    }

    private static IdentityErrors.Refused refuse(String code, String reason, Map<String, Object> details) {
        return new IdentityErrors.Refused(code, reason, details);
    }
}
