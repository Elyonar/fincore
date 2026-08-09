package org.elyonar.fincore.identity.directory;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.elyonar.fincore.identity.internal.Tx;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The service-facing staff directory (admin-surface §5, ADR 0018).
 *
 * <p>Never routed by the edge and never called by a browser: Core's administration surface is the
 * product, this is the record beneath it. {@link DirectoryAuth} enforces that independently of the
 * proxy, so the boundary survives a misconfiguration.
 *
 * <p>What is deliberately <em>absent</em>: role authoring, replacing an existing user's roles, and
 * deactivation. ADR 0017 guardrail 3 requires a second signature on each, Core's approval
 * machinery is bound to a transaction id and an amount today, and a maker-checked operation
 * shipped without its second signature is worse than one not yet shipped. They arrive with the
 * approval-kind work; the rows are listed in the service CHANGELOG so nobody mistakes the gap for
 * an oversight.
 */
@Tag(name = "Directory (admin)", description = "Staff records driven by Core's administration surface")
@RestController
@RequestMapping("/v1/directory")
public class DirectoryController {

    private final Directory directory;
    private final DirectoryAuth auth;
    private final Tx tx;

    public DirectoryController(Directory directory, DirectoryAuth auth, Tx tx) {
        this.directory = directory;
        this.auth = auth;
        this.tx = tx;
    }

    /** The platform's permission vocabulary. Static, but authenticated: it is a map of the estate. */
    @GetMapping("/permissions")
    public List<Directory.Permission> permissions(HttpServletRequest http) {
        auth.identify(http);
        return directory.permissions();
    }

    /** The tenant's roles — seeded templates and anything it has authored — with their contents. */
    @GetMapping("/roles")
    public List<Directory.Role> roles(HttpServletRequest http) {
        var caller = auth.identify(http);
        return tx.inTenant(caller.tenantId(), () -> directory.roles(caller.tenantId()));
    }

    /** Staff, filtered by role and unit, keyset-paged by username. */
    @GetMapping("/users")
    public Directory.UserPage users(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String unit,
            @RequestParam(required = false) String cursor,
            HttpServletRequest http) {
        var caller = auth.identify(http);
        return tx.inTenant(
                caller.tenantId(), () -> directory.listUsers(caller.tenantId(), role, unit, cursor));
    }

    /** One user with roles, units and status. */
    @GetMapping("/users/{id}")
    public ResponseEntity<Directory.User> user(@PathVariable UUID id, HttpServletRequest http) {
        var caller = auth.identify(http);
        Directory.User found = tx.inTenant(caller.tenantId(), () -> directory.user(caller.tenantId(), id));
        return found == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(found);
    }

    /**
     * Creates a member of staff. The temporary credential is in the response and nowhere else —
     * it is not logged, not stored in the clear, and not retrievable afterwards. An administrator
     * who loses it uses {@code reset-password}.
     */
    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public Directory.CreatedUser create(@RequestBody Directory.NewUser request, HttpServletRequest http) {
        var caller = auth.identify(http);
        return tx.inTenant(
                caller.tenantId(),
                () -> directory.createUser(
                        caller.tenantId(), caller.administrator(), request, source(http)));
    }

    /** Replaces unit assignments — the claim half of Core's two-record write. */
    @PutMapping("/users/{id}/units")
    public ResponseEntity<Void> units(
            @PathVariable UUID id, @RequestBody UnitCodes request, HttpServletRequest http) {
        var caller = auth.identify(http);
        tx.inTenant(caller.tenantId(), () -> {
            directory.setUnits(
                    caller.tenantId(), id, request.units(), caller.administrator(), source(http));
            return null;
        });
        return ResponseEntity.noContent().build();
    }

    /** A fresh temporary credential, forced change, every session revoked. Returned once. */
    @PostMapping("/users/{id}/reset-password")
    public Map<String, String> resetPassword(@PathVariable UUID id, HttpServletRequest http) {
        var caller = auth.identify(http);
        String temporary = tx.inTenant(
                caller.tenantId(),
                () -> directory.resetPassword(
                        caller.tenantId(), id, caller.administrator(), source(http)));
        return Map.of("temporaryCredential", temporary);
    }

    /** Clears a lockout early. */
    @PostMapping("/users/{id}/unlock")
    public ResponseEntity<Void> unlock(@PathVariable UUID id, HttpServletRequest http) {
        var caller = auth.identify(http);
        tx.inTenant(caller.tenantId(), () -> {
            directory.unlock(caller.tenantId(), id, caller.administrator(), source(http));
            return null;
        });
        return ResponseEntity.noContent().build();
    }

    /** Creates a tenant-authored role. The `role:` namespace is applied here, not by the caller. */
    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    public Directory.Role createRole(@RequestBody NewRole request, HttpServletRequest http) {
        var caller = auth.identify(http);
        return tx.inTenant(
                caller.tenantId(),
                () -> directory.createRole(
                        caller.tenantId(), caller.administrator(), request.name(), request.permissions(), source(http)));
    }

    /** Replaces what a role grants, as a set. */
    @PutMapping("/roles/{role}/permissions")
    public Directory.Role setRolePermissions(
            @PathVariable String role, @RequestBody PermissionNames request, HttpServletRequest http) {
        var caller = auth.identify(http);
        return tx.inTenant(
                caller.tenantId(),
                () -> directory.setRolePermissions(
                        caller.tenantId(), caller.administrator(), role, request.permissions(), source(http)));
    }

    /** Removes a custom role. Refused while held; templates are the platform's starting position. */
    @DeleteMapping("/roles/{role}")
    public ResponseEntity<Void> deleteRole(@PathVariable String role, HttpServletRequest http) {
        var caller = auth.identify(http);
        tx.inTenant(caller.tenantId(), () -> {
            directory.deleteRole(caller.tenantId(), caller.administrator(), role, source(http));
            return null;
        });
        return ResponseEntity.noContent().build();
    }

    /** Replaces a user's role grants. Takes effect on that user's next token. */
    @PutMapping("/users/{id}/roles")
    public ResponseEntity<Void> setUserRoles(
            @PathVariable UUID id, @RequestBody RoleNames request, HttpServletRequest http) {
        var caller = auth.identify(http);
        tx.inTenant(caller.tenantId(), () -> {
            directory.setUserRoles(caller.tenantId(), id, request.roles(), caller.administrator(), source(http));
            return null;
        });
        return ResponseEntity.noContent().build();
    }

    public record NewRole(String name, List<String> permissions) {}

    public record PermissionNames(List<String> permissions) {}

    public record RoleNames(List<String> roles) {}

    public record UnitCodes(List<String> units) {}

    /** Where the call came from, for the audit row. The proxy chain's first hop, or the socket. */
    private static String source(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }
}
