package org.elyonar.fincore.identity;

import java.util.Set;

/**
 * The platform's permission vocabulary — the list of strings the code is capable of enforcing
 * (ADR 0017). Closed to tenants: roles are tenant composition *over* this list, never additions
 * to it. The directory refuses to store a grant naming a string absent from here.
 *
 * <p>This is the code-level catalog ADR 0017 names as its precondition, seeded from what the
 * services' {@code require} call sites actually check. It lives here until the ADR 0017 build
 * moves it to a platform library alongside its reconciling {@code PermissionCatalogTest}; when
 * that happens this class delegates rather than duplicates.
 */
public final class PermissionCatalog {

    public static final Set<String> PERMISSIONS = Set.of(
            "transfers:create",
            "transfers:read",
            "transfers:reverse",
            "cash:transact",
            "customers:create",
            "customers:read",
            "customers:tier",
            "customers:link",
            "customers:contact",
            "customers:consent",
            "products:read",
            "products:create",
            "products:publish",
            "approvals:make",
            "approvals:check",
            "ops:read",
            "ops:resolve",
            "notifications:read",
            "templates:create",
            "templates:publish",
            "policy:write",
            "channel:teller",
            "channel:api",
            "org:read",
            "org:manage",
            "tills:read",
            "tills:manage",
            "loans:apply",
            "loans:read",
            "loans:approve",
            "loans:offer",
            "loans:disburse",
            "loans:repay",
            "loans:portfolio",
            "loans:tiers");

    /**
     * The administrator template role seeded for a manifest tenant — the successor of the realm
     * template's {@code job:admin} composite, permission for permission. A template, not law:
     * once ADR 0017's authoring surface lands, a tenant may recompose or delete it.
     */
    public static final Set<String> ADMIN_TEMPLATE = Set.of(
            "customers:create",
            "customers:read",
            "customers:link",
            "products:read",
            "products:create",
            "products:publish",
            "templates:create",
            "templates:publish",
            "policy:write",
            "notifications:read",
            "org:read",
            "org:manage",
            "tills:read",
            "tills:manage",
            "channel:api",
            "loans:read",
            "loans:portfolio",
            "loans:tiers");

    public static final String ADMIN_ROLE = "job:admin";

    private PermissionCatalog() {}
}
