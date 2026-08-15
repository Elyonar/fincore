package org.elyonar.fincore.identity.internal;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The tenant registry, and the answer to "which institution is this login for?"
 *
 * <p>A deployed instance serves one institution (ADR 0021), and on such an instance the answer is
 * the one active tenant — no caller has to say. Where an instance deliberately holds several — the
 * sandbox case ADR 0021 reserves and <a href="../../../../../../../../docs/adr/0023-the-simulation-runs-as-one-multi-tenant-sandbox.md">ADR
 * 0023</a> exercises — the login names its institution by realm, and this class resolves it.
 *
 * <p><strong>Ambiguity is no longer fatal.</strong> This used to throw when more than one tenant
 * was active, and {@code StartupSummary} made that fatal at boot. That was the right guard while
 * login had no way to name an institution, because guessing among them is a wrong-bank bug wearing
 * a convenience. Now that a realm exists, several active tenants is a legitimate configuration and
 * the only thing left to refuse is a login that names none of them on an instance holding more than
 * one — which is an {@code AuthFailed}, in the one voice, like every other failed login.
 *
 * <p>Registration is provisioning and runs as the owner, deliberately unreachable from any
 * request path — the same split as every other registry on the platform.
 */
@Component
public class Tenants {

    /**
     * What an institution is, beyond an id and a legal name.
     *
     * <p>Every field is the manifest's own vocabulary (ADR 0016) — validated there since the
     * beginning and, until ADR 0023, thrown away for want of a column.
     */
    public record Profile(
            UUID id,
            String realm,
            String legalName,
            String displayName,
            String countryCode,
            String segment,
            String webOrigin,
            String status) {}

    private static final String PROFILE_COLUMNS =
            "id, realm, name, display_name, country_code, segment, web_origin, status";

    private final JdbcTemplate read;
    private final JdbcTemplate provision;
    private final IdentityProperties properties;

    public Tenants(
            @Qualifier("appJdbcTemplate") JdbcTemplate read,
            @Qualifier("dataSource") DataSource owner,
            IdentityProperties properties) {
        this.read = read;
        this.provision = new JdbcTemplate(owner);
        this.properties = properties;
    }

    /** Provisioning only (manifest seeding), for a tenant carrying no profile of its own. */
    public void register(UUID tenantId, String name, String createdBy) {
        register(tenantId, name, createdBy, null);
    }

    /**
     * Provisioning only. Registers the tenant and converges its profile.
     *
     * <p>The profile is written on every call rather than only on insert, because this runs on
     * every boot and the manifest is the source of truth for what an institution is called. A
     * tenant registered before the profile columns existed is filled in on the next boot, which is
     * the same convergence contract the seeder applies to roles, job titles and staff numbering.
     * {@code COALESCE} keeps a field that the manifest has stopped supplying rather than blanking
     * it, so a partial manifest never erases what a full one wrote.
     */
    public void register(UUID tenantId, String name, String createdBy, Profile profile) {
        provision.update(
                "INSERT INTO auth.tenants (id, name, created_by) VALUES (?,?,?)"
                        + " ON CONFLICT (id) DO NOTHING",
                tenantId,
                name,
                createdBy);
        if (profile == null) {
            return;
        }
        provision.update(
                "UPDATE auth.tenants SET"
                        + "   realm        = COALESCE(?, realm),"
                        + "   name         = COALESCE(?, name),"
                        + "   display_name = COALESCE(?, display_name),"
                        + "   country_code = COALESCE(?, country_code),"
                        + "   segment      = COALESCE(?, segment),"
                        + "   web_origin   = COALESCE(?, web_origin)"
                        + " WHERE id = ?",
                profile.realm(),
                profile.legalName(),
                profile.displayName(),
                profile.countryCode(),
                profile.segment(),
                profile.webOrigin(),
                tenantId);
    }

    public List<UUID> activeTenants() {
        return read.query(
                "SELECT id FROM auth.tenants WHERE status = 'ACTIVE' ORDER BY id",
                (rs, i) -> rs.getObject(1, UUID.class));
    }

    /** Every registered institution, for an operator and for the canvas that draws them. */
    public List<Profile> profiles() {
        return read.query("SELECT " + PROFILE_COLUMNS + " FROM auth.tenants ORDER BY name", ROW);
    }

    /** One institution's profile, or null when no such tenant is registered. */
    public Profile profile(UUID tenantId) {
        return read.query(
                "SELECT " + PROFILE_COLUMNS + " FROM auth.tenants WHERE id = ?",
                rs -> rs.next() ? ROW.mapRow(rs, 1) : null,
                tenantId);
    }

    /**
     * The active tenant a realm names, or null when the realm names none.
     *
     * <p>Case-insensitive, matching the unique index. A suspended tenant resolves to null rather
     * than to itself: an institution that has been suspended must not authenticate, and refusing
     * here means every credential flow inherits that without repeating the check.
     */
    public UUID resolve(String realm) {
        if (realm == null || realm.isBlank()) {
            return null;
        }
        return read.query(
                "SELECT id FROM auth.tenants WHERE lower(realm) = lower(?) AND status = 'ACTIVE'",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                realm.trim());
    }

    /**
     * The tenant a credential flow is for: the one the realm names, or — when no realm was
     * supplied — the instance's own, which is unambiguous on the instance-per-institution
     * deployment that is still the default.
     *
     * @return null when the caller named no realm and the instance cannot answer for it, which the
     *     caller turns into the same refusal every other failed login gets
     */
    public UUID tenantFor(String realm) {
        return (realm == null || realm.isBlank()) ? instanceTenant() : resolve(realm);
    }

    /**
     * The instance's tenant: the one named by configuration, or the single active one, or null.
     *
     * <p>Null on ambiguity rather than an exception. Several active tenants is a supported
     * configuration now (ADR 0023) and no longer something to refuse startup over — it simply means
     * this instance has no single answer, and a caller that needs one must name a realm.
     */
    public UUID instanceTenant() {
        String configured = properties.getTenantId();
        List<UUID> active = activeTenants();
        if (configured != null && !configured.isBlank()) {
            UUID wanted = UUID.fromString(configured.trim());
            return active.contains(wanted) ? wanted : null;
        }
        return active.size() == 1 ? active.get(0) : null;
    }

    private static final org.springframework.jdbc.core.RowMapper<Profile> ROW = (rs, i) -> new Profile(
            rs.getObject(1, UUID.class),
            rs.getString(2),
            rs.getString(3),
            rs.getString(4),
            rs.getString(5),
            rs.getString(6),
            rs.getString(7),
            rs.getString(8));
}
