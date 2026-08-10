package org.elyonar.fincore.core.app.admin;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.elyonar.fincore.auth.Authorization;
import org.elyonar.fincore.core.organization.api.OrganizationUnits;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Staff administration — admin-surface §5, the product surface a tenant's administrator uses.
 *
 * <p>Core owns the surface; the identity service owns the records beneath it (ADR 0018). The split
 * is not ceremony: permission checks, the organizational-unit record, and — when it arrives — the
 * maker-checker workflow all live in Core, while credentials and the token's claims live in a
 * service whose blast radius is deliberately smaller.
 *
 * <p><b>What is here.</b> The rows of §5 that the AGREED table does not mark maker-checked:
 * reading the permission catalog and the tenant's roles, creating a member of staff, reading
 * staff, and replacing a user's units. Plus two operational rows added by amendment —
 * reset-password and unlock — because an administrator who cannot restore a colleague's access on
 * the first morning does not have an administration surface, they have a demo.
 *
 * <p><b>What is here without its second signature, and why that is defensible.</b> Role authoring,
 * recomposition and grants. ADR 0017 guardrail 3 asks for a second signature on each, and Core's
 * approval machinery is still bound to a transaction id and an amount, so what these carry instead
 * is guardrail 1, enforced server-side in the directory: nobody composes or grants a permission
 * they do not already hold. That leaves an accountability gap — one administrator can rearrange
 * authority within their own — rather than an escalation one, which is the trade that makes an
 * institution administrable on its first day, and is named here rather than discovered.
 *
 * <p><b>What is deliberately not here.</b> Deactivate and reactivate. Guardrail 2 already refuses
 * to remove the last administrator; the hazard those two would add — one administrator quietly
 * disabling another — is precisely what a second signature exists for, so they wait for it.
 *
 * <p>Creating a user is not maker-checked, and that is the table's decision rather than an
 * oversight: a tenant is seeded with exactly one administrator, so requiring a second signature on
 * the operation that creates the second human is a deadlock. The guardrail that matters at
 * creation — nobody grants a permission they do not hold — is enforced in the directory, against
 * the forwarded administrator's own effective permissions.
 */
@Tag(name = "Administration", description = "Staff, roles and the permission catalog (admin-surface §5)")
@RestController
@RequestMapping("/v1")
public class AdminController {

    private final IdentityDirectory directory;
    private final OrganizationUnits units;

    public AdminController(IdentityDirectory directory, OrganizationUnits units) {
        this.directory = directory;
        this.units = units;
    }

    /** The platform's permission vocabulary, with what each grants. Read-only, forever. */
    @GetMapping("/permissions")
    public JsonNode permissions() {
        Authorization.require("users:read");
        return directory.permissions();
    }

    /** The tenant's roles — the templates it was seeded with, and anything it has authored. */
    @GetMapping("/roles")
    public JsonNode roles() {
        Authorization.require("users:read");
        return directory.roles();
    }

    /**
     * Creates a tenant-authored role.
     *
     * <p>Not maker-checked yet, which ADR 0017 guardrail 3 asks for and the CHANGELOG records as
     * owed. What makes the gap an accountability one rather than a security one: guardrail 1 is
     * enforced in the directory, so an administrator can only compose permissions they already
     * hold. Authoring a role cannot hand anyone access its author lacks.
     */
    @PostMapping("/roles")
    public JsonNode createRole(@RequestBody NewRole request) {
        Authorization.require("users:manage");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", request.name());
        body.put("permissions", request.permissions() == null ? List.of() : request.permissions());
        return directory.createRole(body);
    }

    /** Replaces what a role grants, as a set. */
    @PutMapping("/roles/{role}/permissions")
    public JsonNode setRolePermissions(@PathVariable String role, @RequestBody PermissionNames request) {
        Authorization.require("users:manage");
        return directory.setRolePermissions(role, request.permissions() == null ? List.of() : request.permissions());
    }

    /** Removes a custom role. Refused while held; template roles are not deletable. */
    @DeleteMapping("/roles/{role}")
    public JsonNode deleteRole(@PathVariable String role) {
        Authorization.require("users:manage");
        return directory.deleteRole(role);
    }

    /** Replaces a user's role grants. Takes effect on that user's next token, never the current one. */
    @PutMapping("/users/{id}/roles")
    public Map<String, Object> setUserRoles(@PathVariable UUID id, @RequestBody RoleNames request) {
        Authorization.require("users:manage");
        directory.setUserRoles(id, request.roles() == null ? List.of() : request.roles());
        return Map.of("roles", request.roles() == null ? List.of() : request.roles(), "effective", "NEXT_TOKEN");
    }

    /** Staff, filtered by role and unit, keyset-paged. */
    @GetMapping("/users")
    public JsonNode users(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String unit,
            @RequestParam(required = false) String cursor) {
        Authorization.require("users:read");
        return directory.users(role, unit, cursor);
    }

    /** One user with roles, units and status. */
    @GetMapping("/users/{id}")
    public JsonNode user(@PathVariable UUID id) {
        Authorization.require("users:read");
        return directory.user(id);
    }

    /**
     * Creates a member of staff with roles and units, and returns their temporary credential once.
     *
     * <p>Units are validated against this tenant's organizational units and written to <em>both</em>
     * stores before the response returns: Core's assignment record and the directory's claim. ADR
     * 0017 names their divergence as a live defect — assignment was the documented system of
     * record while nothing derived the claim, so putting a teller in a branch had no effect on
     * authorization.
     */
    @PostMapping("/users")
    public JsonNode createUser(@RequestBody NewUser request) {
        var identity = Authorization.require("users:manage");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", request.username());
        body.put("email", request.email());
        body.put("firstName", request.firstName());
        body.put("lastName", request.lastName());
        body.put("roles", request.roles() == null ? List.of() : request.roles());
        body.put("units", request.units() == null ? List.of() : request.units());
        body.put("staffNumber", request.staffNumber());
        body.put("jobTitle", request.jobTitle());
        body.put("startedOn", request.startedOn());

        JsonNode created = directory.createUser(body);

        if (request.units() != null && !request.units().isEmpty()) {
            units.replaceAssignments(
                    identity.tenantId(),
                    "user:" + request.username(),
                    request.units(),
                    Authorization.initiatedBy());
        }
        return created;
    }

    /**
     * Replaces a user's unit assignments in both stores.
     *
     * <p>Core's record first: it is the one that can refuse. A code naming no active unit stops
     * the whole operation, so the two stores cannot disagree because half of it succeeded.
     */
    @PutMapping("/users/{id}/units")
    public Map<String, Object> setUnits(@PathVariable UUID id, @RequestBody UnitCodes request) {
        var identity = Authorization.require("users:manage");

        JsonNode user = directory.user(id);
        String username = user == null || user.get("username") == null ? null : user.get("username").asString();
        if (username == null) {
            throw new IdentityDirectory.DirectoryRefused(404, null);
        }

        List<String> wanted = request.units() == null ? List.of() : request.units();
        List<String> applied = units.replaceAssignments(
                identity.tenantId(), "user:" + username, wanted, Authorization.initiatedBy());
        directory.setUnits(id, applied);

        // Stated on the wire because it is a property, not a detail: the claim moves with the
        // record, and both take effect on that user's next token — not on their current one.
        return Map.of("units", applied, "effective", "NEXT_TOKEN");
    }

    /** A fresh temporary credential with a forced change, and every session revoked. Once. */
    @PostMapping("/users/{id}/reset-password")
    public JsonNode resetPassword(@PathVariable UUID id) {
        Authorization.require("users:manage");
        return directory.resetPassword(id);
    }

    /** Clears a lockout before it expires. */
    @PostMapping("/users/{id}/unlock")
    public JsonNode unlock(@PathVariable UUID id) {
        Authorization.require("users:manage");
        return directory.unlock(id);
    }

    // --- the institution's vocabulary ----------------------------------------------------------

    /**
     * The job titles this institution recognises.
     *
     * <p>A title is not a role and this surface keeps them apart: a role is what somebody may do
     * and is checked on every request; a title is what they are called and is checked nowhere.
     * Institutions that conflate the two end up encoding place and seniority into permission sets.
     *
     * <p>Readable with {@code users:read} rather than {@code users:manage}, because every screen
     * showing a person shows their title, and a list of job names discloses nothing a staff
     * directory does not already.
     */
    @GetMapping("/job-titles")
    public JsonNode jobTitles() {
        Authorization.require("users:read");
        return directory.jobTitles();
    }

    /** Adds a title to the vocabulary. */
    @PostMapping("/job-titles")
    public JsonNode createJobTitle(@RequestBody NewJobTitle request) {
        Authorization.require("users:manage");
        return directory.createJobTitle(request.title());
    }

    /** Retires a title. Refused while anybody holds it. */
    @DeleteMapping("/job-titles/{title}")
    public JsonNode deleteJobTitle(@PathVariable String title) {
        Authorization.require("users:manage");
        return directory.deleteJobTitle(title);
    }

    /** How staff numbers are formed, and what the next hire would be given. */
    @GetMapping("/staff-numbering")
    public JsonNode numbering() {
        Authorization.require("users:read");
        return directory.numbering();
    }

    /** Changes the numbering rule. */
    @PutMapping("/staff-numbering")
    public JsonNode setNumbering(@RequestBody Numbering request) {
        Authorization.require("users:manage");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prefix", request.prefix() == null ? "" : request.prefix());
        body.put("width", request.width());
        body.put("nextValue", request.nextValue());
        return directory.setNumbering(body);
    }

    /**
     * Sets somebody's administered employment facts after they were created.
     *
     * <p>Deliberately not part of the self-service profile: staff number, job title and start date
     * are facts about the job, and a person editing their own job title is not a control. Until
     * this existed they could only be set at creation, which left everybody hired before an
     * institution settled on its titles permanently blank.
     */
    @PutMapping("/users/{id}/employment")
    public JsonNode setEmployment(@PathVariable UUID id, @RequestBody Employment request) {
        Authorization.require("users:manage");
        Map<String, Object> body = new LinkedHashMap<>();
        // Nulls are meaningful here — they clear the field — so the map keeps them rather than
        // using Map.of, which refuses a null value outright.
        body.put("staffNumber", request.staffNumber());
        body.put("jobTitle", request.jobTitle());
        body.put("startedOn", request.startedOn());
        body.put("autoAssignStaffNumber", request.autoAssignStaffNumber());
        return directory.setEmployment(id, body);
    }

    public record NewJobTitle(String title) {}

    /** @param width how many digits the numeric part is padded to, 1–12 */
    public record Numbering(String prefix, int width, long nextValue) {}

    /**
     * @param startedOn ISO date, or null to clear
     * @param autoAssignStaffNumber take the next number from the institution's rule rather than the
     *     string given. A flag rather than an inference from a blank field, because blank
     *     legitimately means "clear this".
     */
    public record Employment(
            String staffNumber, String jobTitle, String startedOn, Boolean autoAssignStaffNumber) {}

    /** @param roles role names as the directory spells them, e.g. {@code job:teller} */
    /** @param startedOn ISO date; the administered half a person may never edit themselves */
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

    public record UnitCodes(List<String> units) {}

    public record NewRole(String name, List<String> permissions) {}

    public record PermissionNames(List<String> permissions) {}

    public record RoleNames(List<String> roles) {}

    /** Convenience for the rare caller wanting every role name only. */
    static List<String> names(JsonNode roles) {
        List<String> out = new ArrayList<>();
        if (roles != null && roles.isArray()) {
            roles.forEach(role -> out.add(role.get("name").asString()));
        }
        return out;
    }
}
