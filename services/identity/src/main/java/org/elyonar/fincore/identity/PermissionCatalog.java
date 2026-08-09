package org.elyonar.fincore.identity;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The platform's permission vocabulary — the list of strings the code is capable of enforcing
 * (ADR 0017). Closed to tenants: roles are tenant composition *over* this list, never additions
 * to it. The directory refuses to store a grant naming a string absent from here.
 *
 * <p>This is the code-level catalog ADR 0017 names as its precondition, seeded from what the
 * services' {@code require} call sites actually check. It lives here until the ADR 0017 build
 * moves it to a platform library alongside its reconciling {@code PermissionCatalogTest}; when
 * that happens this class delegates rather than duplicates.
 *
 * <p>It also holds the two things role authoring cannot be built without: the job templates a
 * tenant starts from, and the naming rules of ADR 0017's guardrail 0.
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
            "loans:tiers",
            // admin-surface §8: staff administration and internal accounts. Enforced by Core's
            // administration surface; named here because a permission the catalog does not know
            // is a permission no role may contain.
            "users:read",
            "users:manage",
            "accounts:read",
            "accounts:manage");

    /**
     * What each permission allows, in one line — the {@code grants} field of {@code
     * GET /v1/permissions}. An administrator composing a role is choosing from this list, and a
     * list of bare strings asks them to guess; guessing wrong here is a privilege mistake.
     */
    public static final Map<String, String> GRANTS = grants();

    /** The administrator template — the successor of the realm template's {@code job:admin}. */
    public static final String ADMIN_ROLE = "job:admin";

    /**
     * The role the manifest's super-administrator is seeded with, holding the whole catalog.
     *
     * <p>It exists because of ADR 0017 guardrail 1: nobody grants a permission they do not hold.
     * That rule is right — an administrator who could grant {@code cash:transact} without holding
     * it can create a user, keep their temporary credential, sign in as them and transact, which
     * is self-escalation wearing a second username. But it means the seeded administrator can
     * only staff the institution with roles inside their own access, and {@code job:admin}
     * deliberately excludes the money path. A tenant seeded with only {@code job:admin} could
     * therefore never create its first teller.
     *
     * <p>So the bootstrap identity is what its name always claimed: a <em>super</em>-administrator
     * holding everything, whose first act is usually to create narrower administrators. The
     * separation of duties {@code job:admin} encodes is preserved for every administrator after
     * the first — which is where it does its work, since one person alone cannot be two.
     */
    public static final String SUPER_ADMIN_ROLE = "job:super-admin";

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
            "loans:tiers",
            "users:read",
            "users:manage",
            "accounts:read",
            "accounts:manage");

    /**
     * The job templates a tenant starts from, carried over from the retired realm template.
     *
     * <p>Templates, not law: ADR 0017 is explicit that "nothing in the manifest is privileged
     * after provisioning — it is a starting position, not a permanent structure". They exist so
     * that an institution can staff itself on day one without first authoring a role, which is
     * the operation that needs a second signature and therefore a second administrator.
     *
     * <p>{@code job:teller} carries the four permissions the portal roadmap records it was
     * missing — {@code tills:read}, {@code customers:create}, {@code customers:link} and
     * {@code approvals:make} — without which, in its own words, "job:teller cannot do the teller
     * job": it could not find the till its own deposits require. The realm template flattened
     * composites of composites; here {@code job:supervisor} spells out the teller permissions it
     * used to inherit, because this directory's model is role → permissions and nothing else.
     */
    public static final Map<String, Set<String>> ROLE_TEMPLATES = templates();

    // --- guardrail 0: a role name may never be, or become, a permission name -------------------

    /** The namespace tenant-authored roles live in. Applied by the service, never by the caller. */
    public static final String TENANT_ROLE_PREFIX = "role:";

    /**
     * Prefixes a caller may not name. {@code job:} and {@code machine:} are the platform's own;
     * the last three are provider defaults that historically rode along in the same claim.
     */
    public static final Set<String> RESERVED_PREFIXES =
            Set.of("job:", "machine:", "default-roles", "offline_access", "uma_authorization");

    /** Lowercase, hyphen-separated, 3–50 characters. The shape of a name in a URL path. */
    private static final Pattern WELL_FORMED = Pattern.compile("^[a-z0-9][a-z0-9-]{1,48}[a-z0-9]$");

    /** Why a proposed role name was refused, or null when it is acceptable. */
    public static String roleNameViolation(String proposed) {
        if (proposed == null || proposed.isBlank()) {
            return "MALFORMED";
        }
        String candidate = proposed.trim().toLowerCase(Locale.ROOT);
        // Guardrail 0's whole point: the role's own name travels in the claim beside the
        // permissions it resolves to, so a role named for a permission grants that permission
        // to everyone who holds it, invisibly, and no review of its contents would show it.
        if (PERMISSIONS.contains(candidate)) {
            return "COLLIDES_WITH_PERMISSION";
        }
        for (String reserved : RESERVED_PREFIXES) {
            if (candidate.startsWith(reserved)) {
                return "RESERVED_PREFIX";
            }
        }
        if (candidate.startsWith(TENANT_ROLE_PREFIX)) {
            // The namespace is applied server-side. A caller supplying it is either confused or
            // probing; either way the answer is the same.
            return "RESERVED_PREFIX";
        }
        return WELL_FORMED.matcher(candidate).matches() ? null : "MALFORMED";
    }

    /** The stored name for a tenant-authored role: namespaced here, never by the caller. */
    public static String tenantRoleName(String proposed) {
        return TENANT_ROLE_PREFIX + proposed.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, Set<String>> templates() {
        Set<String> teller = Set.of(
                "cash:transact",
                "channel:teller",
                "customers:read",
                "customers:create",
                "customers:link",
                "products:read",
                "transfers:create",
                "transfers:read",
                "tills:read",
                "approvals:make");

        Map<String, Set<String>> templates = new LinkedHashMap<>();
        templates.put(SUPER_ADMIN_ROLE, PERMISSIONS);
        templates.put(ADMIN_ROLE, ADMIN_TEMPLATE);
        templates.put("job:teller", teller);
        templates.put(
                "job:supervisor",
                union(
                        teller,
                        Set.of(
                                "approvals:check",
                                "loans:approve",
                                "loans:disburse",
                                "loans:read",
                                "org:read",
                                "transfers:reverse")));
        templates.put(
                "job:loan-officer",
                Set.of(
                        "customers:read",
                        "loans:apply",
                        "loans:offer",
                        "loans:read",
                        "loans:repay",
                        "products:read"));
        templates.put(
                "job:compliance",
                Set.of(
                        "customers:consent",
                        "customers:read",
                        "customers:tier",
                        "notifications:read",
                        "transfers:read"));
        templates.put("job:ops", Set.of("notifications:read", "ops:read", "ops:resolve", "transfers:read"));
        templates.put(
                "job:api-partner",
                Set.of("channel:api", "customers:read", "transfers:create", "transfers:read"));
        return Map.copyOf(templates);
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        var all = new java.util.HashSet<>(a);
        all.addAll(b);
        return Set.copyOf(all);
    }

    private static Map<String, String> grants() {
        Map<String, String> g = new LinkedHashMap<>();
        g.put("transfers:create", "Move money between accounts");
        g.put("transfers:read", "Read transactions and statements");
        g.put("transfers:reverse", "Reverse a posted transaction (maker-checked)");
        g.put("cash:transact", "Take deposits and pay out withdrawals at a till");
        g.put("customers:create", "Register a customer");
        g.put("customers:read", "Search and read customers");
        g.put("customers:tier", "Change a customer's KYC tier");
        g.put("customers:link", "Link an account to a customer");
        g.put("customers:contact", "Read a customer's contact details");
        g.put("customers:consent", "Record and withdraw consent");
        g.put("products:read", "Read products and their versions");
        g.put("products:create", "Author products and draft versions");
        g.put("products:publish", "Publish a product version (maker-checked: never your own draft)");
        g.put("approvals:make", "Raise an approval for a colleague to check");
        g.put("approvals:check", "Give the second signature on an approval");
        g.put("ops:read", "Read the unresolved-outcome queue");
        g.put("ops:resolve", "Ask Core to re-determine an uncertain outcome");
        g.put("notifications:read", "Read message templates, policy and delivery history");
        g.put("templates:create", "Author message templates");
        g.put("templates:publish", "Publish a message template version");
        g.put("policy:write", "Set notification policy — quiet hours, categories");
        g.put("channel:teller", "Act through the teller channel");
        g.put("channel:api", "Act through the API channel");
        g.put("org:read", "Read organizational units and assignments");
        g.put("org:manage", "Create, close and staff organizational units");
        g.put("tills:read", "Read tills and their activity");
        g.put("tills:manage", "Provision and close tills");
        g.put("loans:apply", "Take a loan application");
        g.put("loans:read", "Read applications, loans and schedules");
        g.put("loans:approve", "Sign a loan approval in the tiered chain");
        g.put("loans:offer", "Accept an offer on the customer's behalf");
        g.put("loans:disburse", "Disburse an accepted loan");
        g.put("loans:repay", "Record a repayment");
        g.put("loans:portfolio", "Read portfolio at risk and delinquency");
        g.put("loans:tiers", "Set the loan approval tiers");
        g.put("users:read", "Read staff, roles and the permission catalog");
        g.put("users:manage", "Create staff, compose roles and grant them");
        g.put("accounts:read", "Read the institution's own internal accounts");
        g.put("accounts:manage", "Open the institution's own internal accounts");
        return Map.copyOf(g);
    }

    private PermissionCatalog() {}
}
