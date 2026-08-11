/**
 * Admin — the staff and role administration surface, proxying the identity service.
 *
 * <p>Core owns the surface; the identity service owns the records beneath it (ADR 0018,
 * admin-surface §5). The controller translates an administrator's request into directory calls,
 * enforces Core's permission checks, keeps unit assignments and the token's {@code units} claim
 * moving together, and translates the directory's refusals into Core's own catalog.
 *
 * <h2>The deviation, stated plainly</h2>
 *
 * <p><strong>This module owns no schema and no database role.</strong> Every other Core module
 * follows ADR 0006's shape — one domain, one schema, one database role granted only on that
 * schema. Admin deviates deliberately: it holds no state, so there is nothing for a schema to own
 * or a role to guard. Every read and every write on this surface is a proxied HTTP call to the
 * identity service. A module exists to own a boundary; this one's boundary is the identity
 * service's <em>client</em>, not a schema. Giving it an empty schema and an unused role would
 * dress the deviation up as conformance — a privilege grant that guards nothing, a Flyway location
 * that migrates nothing — and the first person to audit the roles would rightly ask why.
 *
 * <p>The one durable record this surface does touch — unit assignments — belongs to Organization
 * and is written through {@code OrganizationUnits}, its published api. That is the module boundary
 * working as designed, not an exception to it.
 *
 * <h2>How this module is packaged</h2>
 *
 * <ul>
 *   <li>{@code internal} — {@code IdentityDirectory}, Core's authenticated client for the
 *       directory (the ledger-client pattern: service credential plus the administrator's own
 *       forwarded token)
 *   <li>{@code internal.api} — the controller and its advice, the HTTP surface
 * </ul>
 *
 * <p>There is no {@code api} package, and that is the second half of the same fact: no other
 * module consumes anything from this one on the classpath. Admin's published surface is HTTP —
 * upward to channels, outward to identity. A port with zero consumers would be ceremony, and it
 * appears the day a consumer does.
 *
 * @see <a href="../../../../../../../docs/admin-surface.md">docs/admin-surface.md</a>
 */
package org.elyonar.fincore.core.admin;
